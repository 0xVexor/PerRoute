#import "ProxyHelper.h"
#import "ProxyJournal.h"
#import "TunService.h"
#import <SystemConfiguration/SystemConfiguration.h>
#import <unistd.h>

// Every request and invalidation runs on this queue. The preferences lock is held only during a write,
// while the root-owned intent survives client death, helper restart and partial commit/apply failure.
static dispatch_queue_t Work;
static PRRootJournal *Journal;
static PRTunStore *TunStore;
static PRTunAuthority *TunAuthority;

static BOOL RecoverTun(NSError **error) { return [TunAuthority recover:error]; }

static NSXPCConnection *LeaseConnection;
static NSXPCConnection *RetiringConnection;
static dispatch_source_t RecoveryTimer;
static NSXPCListener *Listener;
static id ListenerDelegate;

static NSDictionary *Read(SCPreferencesRef prefs, NSString *identifier, SCNetworkProtocolRef *protocol, NSError **error) {
    SCNetworkServiceRef service = SCNetworkServiceCopy(prefs, (__bridge CFStringRef)identifier);
    SCNetworkProtocolRef proxy = service ? SCNetworkServiceCopyProtocol(service, kSCNetworkProtocolTypeProxies) : NULL;
    NSDictionary *settings = proxy ? [(__bridge NSDictionary *)SCNetworkProtocolGetConfiguration(proxy) copy] : nil;
    if (service) CFRelease(service);
    if (!settings) {
        if (proxy) CFRelease(proxy);
        if (error) *error = PRError(@"The original network service is unavailable; recovery remains pending");
        return nil;
    }
    *protocol = proxy;
    return settings;
}

static SCPreferencesRef Lock(NSError **error) {
    SCPreferencesRef prefs = SCPreferencesCreate(NULL, CFSTR("PerRoute proxy helper"), NULL);
    if (prefs && SCPreferencesLock(prefs, false)) return prefs;
    if (prefs) CFRelease(prefs);
    if (error) *error = PRError(@"Network preferences are busy or unavailable; retry later");
    return NULL;
}

static BOOL Write(SCPreferencesRef prefs, SCNetworkProtocolRef protocol, NSDictionary *desired, NSError **error) {
    BOOL ok = SCNetworkProtocolSetConfiguration(protocol, (__bridge CFDictionaryRef)desired) &&
        SCPreferencesCommitChanges(prefs) && SCPreferencesApplyChanges(prefs);
    if (!ok && error) *error = PRError(@"Could not commit and apply proxy settings; recovery remains pending");
    return ok;
}

static BOOL Verify(NSString *identifier, NSDictionary *desired, NSError **error) {
    // Configured values and published interface scope must agree in two successive observations.
    // This is bounded read-back, not an atomic dynamic-store snapshot or a traffic test.
    int stable = 0;
    for (int attempt = 0; attempt < 60; attempt++) {
        SCPreferencesRef prefs = SCPreferencesCreate(NULL, CFSTR("PerRoute helper read-back"), NULL);
        SCNetworkServiceRef service = prefs ? SCNetworkServiceCopy(prefs, (__bridge CFStringRef)identifier) : NULL;
        SCNetworkProtocolRef protocol = service ? SCNetworkServiceCopyProtocol(service, kSCNetworkProtocolTypeProxies) : NULL;
        NSDictionary *configured = protocol ? (__bridge NSDictionary *)SCNetworkProtocolGetConfiguration(protocol) : nil;
        SCNetworkInterfaceRef interface = service ? SCNetworkServiceGetInterface(service) : NULL;
        NSString *bsd = interface ? (__bridge NSString *)SCNetworkInterfaceGetBSDName(interface) : nil;
        CFDictionaryRef copied = SCDynamicStoreCopyProxies(NULL);
        NSDictionary *scopes = [(__bridge NSDictionary *)copied objectForKey:@"__SCOPED__"];
        NSDictionary *effective = [scopes isKindOfClass:NSDictionary.class] && bsd ? scopes[bsd] : nil;
        BOOL matches = configured && PRSettingsEquivalent(desired, configured) && PREffectiveEquivalent(desired, effective);
        if (copied) CFRelease(copied);
        if (protocol) CFRelease(protocol);
        if (service) CFRelease(service);
        if (prefs) CFRelease(prefs);
        stable = matches ? stable + 1 : 0;
        if (stable >= 2) return YES;
        usleep(100000);
    }
    *error = PRError(@"Proxy settings did not settle. The helper kept the recovery record for retry");
    return NO;
}

static BOOL Eligible(SCPreferencesRef prefs, NSString *identifier) {
    SCNetworkServiceRef service = SCNetworkServiceCopy(prefs, (__bridge CFStringRef)identifier);
    SCNetworkProtocolRef protocol = service ? SCNetworkServiceCopyProtocol(service, kSCNetworkProtocolTypeProxies) : NULL;
    SCNetworkInterfaceRef interface = service ? SCNetworkServiceGetInterface(service) : NULL;
    NSString *bsd = interface ? (__bridge NSString *)SCNetworkInterfaceGetBSDName(interface) : nil;
    BOOL eligible = service && protocol && SCNetworkServiceGetEnabled(service) && SCNetworkProtocolGetEnabled(protocol) && [bsd hasPrefix:@"en"];
    NSMutableSet *primary = [NSMutableSet set];
    SCDynamicStoreRef store = SCDynamicStoreCreate(NULL, CFSTR("PerRoute helper eligibility"), NULL, NULL);
    for (NSString *key in @[@"State:/Network/Global/IPv4", @"State:/Network/Global/IPv6"]) {
        CFPropertyListRef state = store ? SCDynamicStoreCopyValue(store, (__bridge CFStringRef)key) : NULL;
        if (state && CFGetTypeID(state) == CFDictionaryGetTypeID()) {
            id candidate = [(__bridge NSDictionary *)state objectForKey:@"PrimaryService"];
            if ([candidate isKindOfClass:NSString.class]) [primary addObject:candidate];
        }
        if (state) CFRelease(state);
    }
    eligible = eligible && primary.count == 1 && [primary containsObject:identifier];
    if (store) CFRelease(store);
    if (protocol) CFRelease(protocol);
    if (service) CFRelease(service);
    return eligible;
}

static NSDictionary *Restore(uid_t caller, NSError **error) {
    NSDictionary *lease = [Journal load:error];
    if (!lease) return *error ? nil : @{@"pending": @NO};
    if (caller != 0 && [lease[@"uid"] unsignedIntValue] != caller) {
        *error = PRError(@"Another user owns the pending proxy recovery"); return nil;
    }
    SCPreferencesRef prefs = Lock(error);
    if (!prefs) return nil;
    SCNetworkProtocolRef protocol = NULL;
    NSDictionary *current = Read(prefs, lease[@"service"], &protocol, error);
    BOOL conflict = NO, ok = NO;
    NSDictionary *desired = current ? PRRestoreSettings(current, lease[@"original"], lease[@"applied"], &conflict) : nil;
    // Reapply even an already restored persistent dictionary: a prior commit may have failed to apply.
    if (desired) ok = Write(prefs, protocol, desired, error);
    if (protocol) CFRelease(protocol);
    SCPreferencesUnlock(prefs); CFRelease(prefs);
    if (!ok) return nil;
    if (conflict) { *error = PRError(@"External proxy changes were preserved. Recovery remains pending; review the original network service"); return nil; }
    if (!Verify(lease[@"service"], desired, error)) return nil;
    if (![Journal clear:error]) return nil;
    LeaseConnection = nil;
    return @{@"pending": @NO, @"service": lease[@"service"], @"settings": desired};
}

static NSDictionary *Apply(NSDictionary *request, uid_t uid, NSXPCConnection *connection, NSError **error) {
    if (TunAuthority.active) { *error = PRError(@"Stop per-app routing before applying system proxies"); return nil; }
    if (!RecoverTun(error)) return nil;
    if (RetiringConnection) { *error = PRError(@"The helper is being removed; no new proxy lease is allowed"); return nil; }
    id identifier = request[@"service"], http = request[@"http"], socks = request[@"socks"], expected = request[@"expected"];
    NSSet *keys = [NSSet setWithArray:@[@"version", @"operation", @"service", @"http", @"socks", @"expected"]];
    if (![keys isEqualToSet:[NSSet setWithArray:request.allKeys]] ||
        ![identifier isKindOfClass:NSString.class] || ![[NSUUID alloc] initWithUUIDString:identifier] ||
        ![http isKindOfClass:NSNumber.class] || ![socks isKindOfClass:NSNumber.class] ||
        [http doubleValue] != [http integerValue] || [socks doubleValue] != [socks integerValue] ||
        ![expected isKindOfClass:NSDictionary.class]) {
        *error = PRError(@"Invalid helper request"); return nil;
    }
    NSDictionary *existing = [Journal load:error];
    if (*error) return nil;
    if (existing) { *error = PRError(@"Resolve the pending proxy recovery before connecting"); return nil; }
    SCPreferencesRef prefs = Lock(error);
    if (!prefs) return nil;
    SCNetworkProtocolRef protocol = NULL;
    NSDictionary *original = nil;
    if (!Eligible(prefs, identifier)) *error = PRError(@"System proxy requires one supported primary network service");
    else original = Read(prefs, identifier, &protocol, error);
    NSDictionary *applied = nil;
    BOOL ok = NO;
    if (original && ![original isEqual:expected]) *error = PRError(@"Proxy settings changed before connection; retry");
    else if (original) applied = PRApplySettings(original, [http integerValue], [socks integerValue], error);
    if (applied) {
        NSDictionary *lease = @{@"version": @1, @"uid": @(uid), @"service": identifier, @"original": original, @"applied": applied};
        if ([Journal save:lease error:error]) {
            LeaseConnection = connection;
            ok = Write(prefs, protocol, applied, error);
        }
    }
    if (protocol) CFRelease(protocol);
    SCPreferencesUnlock(prefs); CFRelease(prefs);
    if (ok) ok = Verify(identifier, applied, error);
    return ok ? @{@"pending": @YES, @"service": identifier, @"settings": applied} : nil;
}

@interface PRClient : NSObject <PRProxyHelper>
@property(nonatomic, weak) NSXPCConnection *connection;
@property(nonatomic) uid_t uid;
@end
@implementation PRClient
- (void)request:(NSDictionary *)request reply:(void (^)(NSDictionary *))reply {
    NSXPCConnection *connection = self.connection;
    dispatch_async(Work, ^{
        NSError *error = nil;
        NSDictionary *result = nil;
        NSData *encoded = [NSPropertyListSerialization dataWithPropertyList:request format:NSPropertyListBinaryFormat_v1_0 options:0 error:&error];
        if (!encoded || encoded.length > 65536 || ![request[@"version"] isEqual:@(PRProtocolVersion)]) error = PRError(@"Unsupported or oversized helper request");
        else if ((TunAuthority.active && ![TunAuthority ownedBy:connection user:self.uid] &&
            !([TunAuthority recoveryForUser:self.uid] && [@[@"status",@"tun-status",@"tun-capabilities",@"stop-tun",@"retire"] containsObject:request[@"operation"]])) || (LeaseConnection && LeaseConnection != connection) || (RetiringConnection && RetiringConnection != connection)) error = PRError(@"Another PerRoute session owns the proxy connection");
        else {
            uid_t console = 0;
            CFStringRef user = SCDynamicStoreCopyConsoleUser(NULL, &console, NULL);
            if (user) CFRelease(user);
            if (console != self.uid) { reply(@{@"ok": @NO, @"version": @(PRProtocolVersion), @"error": @"Only the active console user may change proxy settings"}); return; }
            NSString *operation = request[@"operation"];
            if ([operation isEqual:@"tun-capabilities"] && request.count == 2) result = PRTunCapabilities(PRSigningTeam());
            else if ([operation isEqual:@"tun-status"] && request.count == 2) result = [TunAuthority statusOwner:connection user:self.uid error:&error];
            else if ([operation isEqual:@"stop-tun"] && request.count == 2) {
                if ([TunAuthority stopOwner:connection user:self.uid error:&error]) result = @{@"pending":@NO,@"owned":@NO,@"phase":@"stopped"};
            } else if ([operation isEqual:@"start-tun"]) {
                NSDictionary *proxyLease = [Journal load:&error];
                if (RetiringConnection) error = PRError(@"The helper is retiring; no routing session may start");
                else if (proxyLease) error = PRError(@"Restore system proxies before starting per-app routing");
                else if (!error) result = PRStartTun(request,self.uid,connection,PRSigningTeam(),TunStore,TunAuthority,&error);
            } else if ([operation isEqual:@"status"] && request.count == 2) {
                NSDictionary *lease = [Journal load:&error];
                // A journal surviving helper restart represents recovery intent, not a live client lease.
                BOOL owned = lease && LeaseConnection == connection && [lease[@"uid"] unsignedIntValue] == self.uid;
                result = PRSessionStatus(lease != nil, owned);
            } else if ([operation isEqual:@"retire"] && request.count == 2) {
                result = [TunAuthority stopOwner:connection user:self.uid error:&error] && RecoverTun(&error) ? Restore(self.uid, &error) : nil;
                if (result && !error) {
                    // Keep this authenticated connection as a maintenance barrier until unregister
                    // completes or its client exits. No other client may apply between restore/remove.
                    RetiringConnection = connection;
                    result = @{@"pending": @NO, @"retired": @YES};
                }
            } else if ([operation isEqual:@"restore"] && request.count == 2) result = Restore(self.uid, &error);
            else if ([operation isEqual:@"apply"]) result = Apply(request, self.uid, connection, &error);
            else error = PRError(@"Unsupported helper operation");
        }
        reply(error ? @{@"ok": @NO, @"version": @(PRProtocolVersion), @"error": error.localizedDescription} :
            @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": result ?: @{}});
    });
}
@end

@interface PRListener : NSObject <NSXPCListenerDelegate>
@end
@implementation PRListener
- (BOOL)listener:(NSXPCListener *)listener shouldAcceptNewConnection:(NSXPCConnection *)connection {
    (void)listener;
    uid_t console = 0;
    CFStringRef user = SCDynamicStoreCopyConsoleUser(NULL, &console, NULL);
    if (user) CFRelease(user);
    // The listener's signing requirement is checked by XPC before this delegate runs.
    if (console == 0 || connection.effectiveUserIdentifier != console) return NO;
    PRClient *client = [PRClient new];
    client.connection = connection; client.uid = console;
    connection.exportedInterface = PRHelperInterface();
    connection.exportedObject = client;
    __weak NSXPCConnection *weakConnection = connection;
    connection.invalidationHandler = ^{
        NSXPCConnection *invalidated = weakConnection;
        dispatch_async(Work, ^{
            [TunAuthority invalidate:invalidated];
            if (invalidated && RetiringConnection == invalidated) RetiringConnection = nil;
            if (invalidated && LeaseConnection == invalidated) {
                NSError *error = nil;
                Restore(0, &error);
                // Release the dead session even if external conflicts need a later recovery retry.
                LeaseConnection = nil;
                if (error) NSLog(@"PerRoute proxy recovery remains pending");
            }
        });
    };
    [connection resume];
    return YES;
}
@end

int main(int argc, const char **argv) {
    @autoreleasepool {
        NSString *team = PRSigningTeam();
        if (geteuid() != 0 || !team) return 78;
        NSError *error = nil;
        TunStore = [[PRTunStore alloc] initSystem:&error];
        if (!TunStore) return 78;
        // Only the authenticated root helper launches this copied, signed executable.
        // Its fixed internal mode never initializes XPC or accepts engine paths/configuration.
        if (argc == 3 && !strcmp(argv[1],"--tun-worker"))
            return PRRunStagedTunWorker(TunStore,[NSString stringWithUTF8String:argv[2]],3);
        if (argc != 1) return 78;
        TunAuthority = [[PRTunAuthority alloc] initWithStore:TunStore];
        Journal = [[PRRootJournal alloc] initWithError:&error];
        if (!Journal) return 78;
        Work = dispatch_queue_create("app.perroute.proxy-helper.work", dispatch_queue_attr_make_with_autorelease_frequency(DISPATCH_QUEUE_SERIAL, DISPATCH_AUTORELEASE_FREQUENCY_WORK_ITEM));
        // A helper restart cannot leave an old owned lease behind indefinitely. Conflicts stay journaled.
        if (!RecoverTun(&error)) NSLog(@"PerRoute startup TUN recovery remains pending");
        error = nil;
        Restore(0, &error);
        if (error) NSLog(@"PerRoute startup proxy recovery remains pending");
        RecoveryTimer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, Work);
        dispatch_source_set_timer(RecoveryTimer, dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC), NSEC_PER_SEC, NSEC_PER_SEC/10);
        __block unsigned recoveryTick = 0;
        dispatch_source_set_event_handler(RecoveryTimer, ^{
            NSError *tunError = nil;
            if (!RecoverTun(&tunError)) NSLog(@"PerRoute TUN recovery remains pending");
            if (++recoveryTick % 15 == 0 && !LeaseConnection) { NSError *retryError = nil; Restore(0, &retryError); }
        });
        dispatch_resume(RecoveryTimer);
        Listener = [[NSXPCListener alloc] initWithMachServiceName:PRHelperLabel];
        [Listener setConnectionCodeSigningRequirement:PRRequirement(PRAppIdentifier, team)];
        ListenerDelegate = [PRListener new];
        Listener.delegate = ListenerDelegate;
        [Listener resume];
        [NSRunLoop.currentRunLoop run];
    }
    return 0;
}
