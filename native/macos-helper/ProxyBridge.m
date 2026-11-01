#import "ProxyHelper.h"
#import <ServiceManagement/ServiceManagement.h>
#import <Security/Security.h>
#import <sys/stat.h>

static NSXPCConnection *Connection;
static NSObject *ConnectionLock;

static NSDictionary *Failure(NSString *message) { return @{@"ok": @NO, @"error": message, @"version": @(PRProtocolVersion)}; }

static BOOL TrustedHelper(NSString *team) {
    NSURL *url = [NSBundle.mainBundle.bundleURL URLByAppendingPathComponent:@"Contents/Library/HelperTools/PerRouteProxyHelper"];
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    BOOL valid = SecStaticCodeCreateWithPath((__bridge CFURLRef)url, kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)PRRequirement(PRHelperLabel, team), kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate | kSecCSCheckAllArchitectures, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return valid;
}

static NSDictionary *Remote(NSDictionary *request, NSString *team) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ ConnectionLock = [NSObject new]; });
    __block NSDictionary *result = nil;
    NSObject *replyLock = [NSObject new];
    dispatch_semaphore_t ready = dispatch_semaphore_create(0);
    NSXPCConnection *connection;
    @synchronized(ConnectionLock) {
        if (!Connection) {
            Connection = [[NSXPCConnection alloc] initWithMachServiceName:PRHelperLabel options:NSXPCConnectionPrivileged];
            Connection.remoteObjectInterface = PRHelperInterface();
            [Connection setCodeSigningRequirement:PRRequirement(PRHelperLabel, team)];
            [Connection resume];
        }
        connection = Connection;
    }
    // The connection stays alive between calls so its invalidation identifies desktop process death.
    id<PRProxyHelper> proxy = [connection remoteObjectProxyWithErrorHandler:^(NSError *error) {
        (void)error;
        @synchronized(replyLock) { result = Failure(@"The background proxy service is unavailable. Check its approval in System Settings and retry"); }
        dispatch_semaphore_signal(ready);
    }];
    [proxy request:request reply:^(NSDictionary *reply) { @synchronized(replyLock) { result = reply; } dispatch_semaphore_signal(ready); }];
    if (dispatch_semaphore_wait(ready, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
        @synchronized(ConnectionLock) { if (Connection == connection) { [Connection invalidate]; Connection = nil; } }
        return Failure(@"Background proxy service timed out. Recovery will be retried; do not assume the settings were unchanged");
    }
    NSDictionary *response;
    @synchronized(replyLock) { response = result; }
    // Probing an older helper must not invalidate an unrelated live proxy lease.
    if (![response[@"ok"] boolValue] && ![request[@"operation"] isEqual:@"tun-capabilities"]) {
        @synchronized(ConnectionLock) { if (Connection == connection) { [Connection invalidate]; Connection = nil; } }
    }
    return response;
}

#ifdef PR_LOCAL_CERT_SHA1
static BOOL LocalInstalled(void) {
    for (NSString *path in @[@"/Library/PrivilegedHelperTools", @"/Library/PrivilegedHelperTools/app.perroute.proxy-helper", @"/Library/LaunchDaemons", @"/Library/LaunchDaemons/app.perroute.proxy-helper.plist"]) {
        struct stat info;
        if (lstat(path.fileSystemRepresentation, &info) != 0 || info.st_uid != 0 || (info.st_mode & 0022) ||
            (S_ISLNK(info.st_mode)) || (!S_ISDIR(info.st_mode) && !S_ISREG(info.st_mode))) return NO;
    }
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    BOOL ok = SecStaticCodeCreateWithPath((__bridge CFURLRef)[NSURL fileURLWithPath:@"/Library/PrivilegedHelperTools/app.perroute.proxy-helper"], kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)PRRequirement(PRHelperLabel, @"local"), kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate | kSecCSCheckAllArchitectures, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return ok;
}
static BOOL LocalArtifactsExist(void) {
    struct stat info;
    return lstat("/Library/PrivilegedHelperTools/app.perroute.proxy-helper", &info) == 0 ||
        lstat("/Library/LaunchDaemons/app.perroute.proxy-helper.plist", &info) == 0;
}
static BOOL InstallLocal(BOOL remove) {
    NSString *path = [NSBundle.mainBundle.bundleURL.path stringByAppendingPathComponent:@"Contents/MacOS/PerRouteHelperInstaller"];
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    BOOL signedInstaller = SecStaticCodeCreateWithPath((__bridge CFURLRef)[NSURL fileURLWithPath:path], kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)PRRequirement(@"app.perroute.proxy-installer", @"local"), kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate | kSecCSCheckAllArchitectures, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    if (!signedInstaller || ![NSBundle.mainBundle.bundleURL.path isEqual:@"/Applications/PerRoute.app"]) return NO;
    NSString *quoted = [@"'" stringByAppendingString:[[path stringByReplacingOccurrencesOfString:@"'" withString:@"'\\''"] stringByAppendingString:@"'"]];
    if (remove) quoted = [quoted stringByAppendingString:@" --uninstall"];
    NSString *escaped = [[quoted stringByReplacingOccurrencesOfString:@"\\" withString:@"\\\\"] stringByReplacingOccurrencesOfString:@"\"" withString:@"\\\""];
    NSTask *task = [NSTask new];
    task.executableURL = [NSURL fileURLWithPath:@"/usr/bin/osascript"];
    task.arguments = @[@"-e", [NSString stringWithFormat:@"do shell script \"%@\" with administrator privileges with prompt \"%@\"", escaped, remove ? @"PerRoute needs to remove its restored background helper." : @"PerRoute needs to install its background proxy helper."]];
    task.standardOutput = [NSPipe pipe]; task.standardError = [NSPipe pipe];
    NSError *error = nil;
    if (![task launchAndReturnError:&error]) return NO;
    [task waitUntilExit];
    return task.terminationStatus == 0 && (remove ? !LocalArtifactsExist() : LocalInstalled());
}
static NSDictionary *LocalPerform(NSDictionary *request) {
    NSString *operation = request[@"operation"];
    BOOL installed = LocalInstalled();
    if ([operation isEqual:@"prepare-existing"]) {
        if (request.count != 2 || !installed) return Failure(@"Complete helper setup explicitly before reconnecting");
        NSDictionary *status = Remote(@{@"version": @(PRProtocolVersion), @"operation": @"status"}, @"local");
        return [status[@"ok"] isEqual:@YES] && [status[@"result"][@"euid"] isEqual:@0] ? status : Failure(@"The installed helper is unavailable");
    }
    if ([operation isEqual:@"prepare"]) {
        if (!installed && (LocalArtifactsExist() || !InstallLocal(NO)))
            return Failure(@"Helper setup was cancelled or failed. Existing services are never overwritten; retry setup from /Applications/PerRoute.app");
        NSDictionary *status = Remote(@{@"version": @(PRProtocolVersion), @"operation": @"status"}, @"local");
        return [status[@"ok"] isEqual:@YES] && [status[@"result"][@"euid"] isEqual:@0] ? status : Failure(@"The installed helper did not return an authenticated root reply");
    }
    if ([operation isEqual:@"service-status"]) {
        if (!installed) return LocalArtifactsExist() ? Failure(@"Installed helper identity or permissions do not match this app") :
            @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"status": @0, @"registered": @NO, @"installation": @"local-launchdaemon"}};
        NSDictionary *status = Remote(@{@"version": @(PRProtocolVersion), @"operation": @"status"}, @"local");
        if (![status[@"ok"] isEqual:@YES] || ![status[@"result"][@"euid"] isEqual:@0]) return Failure(@"The local helper is installed but unavailable");
        NSMutableDictionary *result = [status[@"result"] mutableCopy];
        [result addEntriesFromDictionary:@{@"status": @1, @"registered": @YES, @"installation": @"local-launchdaemon"}];
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": result};
    }
    if ([operation isEqual:@"unregister"]) {
        if (request.count != 2 || !installed) return Failure(@"The original local helper must be available before removal");
        NSDictionary *retired = Remote(@{@"version": @(PRProtocolVersion), @"operation": @"retire"}, @"local");
        if (![retired[@"ok"] isEqual:@YES] || ![retired[@"result"][@"retired"] isEqual:@YES] || ![retired[@"result"][@"pending"] isEqual:@NO])
            return Failure(@"Recovery could not be confirmed; the helper was not removed");
        if (!InstallLocal(YES)) return Failure(@"Administrator removal failed or was cancelled. Keep the original app and recovery data");
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"registered": @NO, @"pending": @NO}};
    }
    if (!installed) {
        if (!LocalArtifactsExist() && [operation isEqual:@"stop-tun"])
            return @{@"ok":@YES,@"version":@(PRProtocolVersion),@"result":@{@"pending":@NO,@"owned":@NO,@"phase":@"stopped",@"registered":@NO}};
        if (!LocalArtifactsExist() && [operation isEqual:@"restore"])
            return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"pending": @NO, @"registered": @NO}};
        return Failure(@"The local helper is missing or its identity changed. Complete setup before connecting");
    }
    return Remote(request, @"local");
}
#endif

static NSDictionary *Perform(NSDictionary *request) {
    NSString *operation = request[@"operation"];
    NSString *team = PRSigningTeam();
    BOOL packaged = [NSBundle.mainBundle.bundleIdentifier isEqual:PRAppIdentifier];
    BOOL managed = packaged && team != nil;
    if ([operation isEqual:@"availability"]) {
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"managed": @(managed)}};
    }
    if (!managed || !TrustedHelper(team)) return Failure(@"The background proxy service requires a consistently signed PerRoute installation. Use Local proxy until setup is complete");
    #ifdef PR_LOCAL_CERT_SHA1
    return LocalPerform(request);
    #else
    SMAppService *service = [SMAppService daemonServiceWithPlistName:[PRHelperLabel stringByAppendingString:@".plist"]];
    if ([operation isEqual:@"service-status"]) {
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result":
            @{@"status": @(service.status), @"registered": @(service.status != SMAppServiceStatusNotRegistered)}};
    }
    if ([operation isEqual:@"unregister"]) {
        if (request.count != 2) return Failure(@"Invalid maintenance request");
        if (service.status != SMAppServiceStatusEnabled)
            return Failure(@"Enable the service and restore pending settings before removing it");
        NSDictionary *restored = Remote(@{@"version": @(PRProtocolVersion), @"operation": @"retire"}, team);
        if (![restored[@"ok"] isEqual:@YES] || ![restored[@"result"][@"pending"] isEqual:@NO] || ![restored[@"result"][@"retired"] isEqual:@YES])
            return Failure(@"Proxy recovery could not be confirmed. The service was not unregistered");
        NSError *error = nil;
        if (![service unregisterAndReturnError:&error]) return Failure(@"Could not unregister the restored service. Retry before replacing or removing the app");
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result":
            @{@"pending": @NO, @"registered": @(service.status != SMAppServiceStatusNotRegistered)}};
    }
    if ([operation isEqual:@"prepare-existing"]) {
        if (request.count != 2 || service.status != SMAppServiceStatusEnabled)
            return Failure(@"Complete helper setup explicitly before reconnecting");
        NSDictionary *status = Remote(@{@"version": @(PRProtocolVersion), @"operation": @"status"}, team);
        return [status[@"ok"] isEqual:@YES] && [status[@"result"][@"euid"] isEqual:@0] ? status : Failure(@"The enabled helper is unavailable");
    }
    if ([operation isEqual:@"prepare"]) {
        NSError *error = nil;
        if (service.status == SMAppServiceStatusNotRegistered || service.status == SMAppServiceStatusNotFound) {
            if (![service registerAndReturnError:&error] && service.status != SMAppServiceStatusRequiresApproval)
                return Failure(@"Could not register the background proxy service. Install PerRoute in Applications and retry");
        }
        if (service.status == SMAppServiceStatusRequiresApproval) {
            [SMAppService openSystemSettingsLoginItems];
            return Failure(@"Approve the PerRoute background service in System Settings → General → Login Items & Extensions, then click Connect again");
        }
        return service.status == SMAppServiceStatusEnabled ? @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{}} : Failure(@"The background proxy service is not enabled");
    }
    // Startup/Disconnect never registers a service or opens an authorization dialog.
    if (service.status == SMAppServiceStatusNotRegistered && [operation isEqual:@"stop-tun"])
        return @{@"ok":@YES,@"version":@(PRProtocolVersion),@"result":@{@"pending":@NO,@"owned":@NO,@"phase":@"stopped",@"registered":@NO}};
    if (service.status == SMAppServiceStatusNotRegistered && [operation isEqual:@"restore"])
        return @{@"ok": @YES, @"version": @(PRProtocolVersion), @"result": @{@"pending": @NO, @"registered": @NO}};
    if (service.status != SMAppServiceStatusEnabled) return Failure(@"Enable the PerRoute background service in System Settings to restore pending proxy settings");
    return Remote(request, team);
    #endif
}

__attribute__((visibility("default"))) char *perroute_helper_request(const char *input) {
    @autoreleasepool {
        NSDictionary *result;
        @try {
            if (!input || strnlen(input, 65537) > 65536) result = Failure(@"Invalid helper request");
            else {
                NSData *data = [NSData dataWithBytes:input length:strlen(input)];
                id request = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
                result = [request isKindOfClass:NSDictionary.class] && [request[@"version"] isEqual:@(PRProtocolVersion)] ? Perform(request) : Failure(@"Unsupported helper protocol");
            }
        } @catch (NSException *exception) { (void)exception; result = Failure(@"Could not initialize the background proxy service"); }
        NSData *output = [NSJSONSerialization dataWithJSONObject:result options:0 error:NULL];
        return output ? strndup(output.bytes, output.length) : NULL;
    }
}
__attribute__((visibility("default"))) void perroute_helper_free(void *value) { free(value); }
