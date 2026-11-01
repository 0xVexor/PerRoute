#import "TunService.h"
#import "../macos-tun/CoreOwnership.h"
#import "../macos-tun/TunNetwork.h"
#import "../macos-tun/TunPolicy.h"
#import <libproc.h>
#import <SystemConfiguration/SystemConfiguration.h>
#import <unistd.h>
static NSString *const Engine = @"/Applications/PerRoute.app/Contents/Library/Engines/sing-box";
static NSString *const Gateway = @"/Applications/PerRoute.app/Contents/MacOS/PerRoute";
static NSArray *CorePaths(void) { return @[@"/Applications/PerRoute.app/Contents/app/resources/xray/macos-arm64/xray",
    @"/Applications/PerRoute.app/Contents/app/resources/xray/macos-x64/xray"]; }
static id Reject(NSError **error, NSString *message) { if (error) *error = PRError(message); return nil; }
NSDictionary *PRTunCapabilities(NSString *team) {
    BOOL core = NO;
    for (NSString *path in CorePaths()) if (PRTunSigned(path,PRRequirement(PRXrayIdentifier,team))) core = YES;
    BOOL payload = core && PRTunSigned(Engine,PRRequirement(PRTunEngineIdentifier,team));
    return @{@"protocol":@1,@"endpointProtocol":@2,@"payloadAvailable":payload ? @YES : @NO,@"euid":@(geteuid())};
}
NSDictionary *PRStartTun(NSDictionary *request, uid_t uid, NSXPCConnection *connection,
    NSString *team, PRTunStore *store, PRTunAuthority *authority, NSError **error) {
    NSSet *keys = [NSSet setWithArray:@[@"version",@"operation",@"bindings",@"corePid",@"upstreamPorts"]];
    id pid = request[@"corePid"], version = request[@"version"];
    if (![keys isEqual:[NSSet setWithArray:request.allKeys]] || ![request[@"operation"] isEqual:@"start-tun"] ||
        ![version isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)version) == CFBooleanGetTypeID() || ![version isEqual:@1] ||
        ![pid isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)pid) == CFBooleanGetTypeID() ||
        [pid doubleValue] != [pid integerValue] || [pid integerValue] < 2 || [pid integerValue] > INT_MAX)
        return Reject(error,@"Invalid typed per-app routing request");
    NSData *preparation = [NSJSONSerialization dataWithJSONObject:@{@"version":@1,@"operation":@"prepare-tun",@"bindings":request[@"bindings"] ?: NSNull.null}
        options:0 error:error];
    // Validate every binding before reading it as executable/port authority.
    if (!PRTunPrepareConfiguration(preparation,[CorePaths() arrayByAddingObject:Gateway],error)) return nil;
    if (![PRTunCapabilities(team)[@"payloadAvailable"] isEqual:@YES]) return Reject(error,@"Install the matching signed PerRoute routing engine and Xray bundle before using per-app mode");
    NSMutableSet *ports = [NSMutableSet set];
    for (NSDictionary *binding in request[@"bindings"]) [ports addObject:binding[@"socksPort"]];
    NSSet *upstreamPorts = PRTunUpstreamPorts(request[@"upstreamPorts"],ports,error);
    if (!upstreamPorts) return nil;
    NSString *coreRequirement = PRRequirement(PRXrayIdentifier,team);
    NSString *gatewayRequirement = PRRequirement(PRAppIdentifier,team);
    // The bridge runs inside the JVM. XPC provides its PID; no request field can
    // substitute another local process as the gateway endpoint owner.
    NSDictionary *chain = PRTunObserveForwardingChain(connection.processIdentifier,[pid intValue],uid,
        @[Gateway],CorePaths(),gatewayRequirement,coreRequirement,ports,upstreamPorts,error);
    if (!chain) return nil;
    NSDictionary *baseline = PRTunNetworkBaseline(error);
    if (!baseline) return nil;
    char executable[PROC_PIDPATHINFO_MAXSIZE];
    if (proc_pidpath(getpid(),executable,sizeof(executable)) <= 0) return Reject(error,@"Cannot resolve the signed native worker");
    NSString *worker = [NSString stringWithUTF8String:executable];
    return [authority startOwner:connection user:uid create:^PRTunSession *(NSError **createError) {
        return [[PRTunSession alloc] initWithStore:store user:uid engine:Engine worker:worker
            configurationBuilder:^NSData *(NSString *stagedEngine, NSError **buildError) {
                NSData *prepared = PRTunActiveConfiguration(preparation,@[chain[@"gateway"][@"path"],chain[@"core"][@"path"],stagedEngine],uid,buildError);
                if (!prepared || !PRTunForwardingStillOwned(chain,gatewayRequirement,coreRequirement,buildError)) return nil;
                NSString *session = stagedEngine.stringByDeletingLastPathComponent.lastPathComponent;
                NSData *intent = [NSJSONSerialization dataWithJSONObject:@{@"version":@1,@"interface":PRTunInterface} options:0 error:buildError];
                if (![store write:intent session:session file:@"capture.json" error:buildError]) return nil;
                // All capture fields are native constants. The request never contains
                // engine JSON, routes, DNS, interfaces, commands or payload locations.
                return prepared;
            } engineRequirement:PRRequirement(PRTunEngineIdentifier,team) workerRequirement:PRRequirement(PRHelperLabel,team) error:createError];
    } health:^BOOL(BOOL *ready, NSError **healthError) {
        uid_t console = 0;
        CFStringRef user = SCDynamicStoreCopyConsoleUser(NULL,&console,NULL);
        if (user) CFRelease(user);
        if (console != uid) { Reject(healthError,@"The active console user changed; per-app routing was stopped"); return NO; }
        if (!PRTunForwardingStillOwned(chain,gatewayRequirement,coreRequirement,healthError)) return NO;
        *ready = PRTunNetworkReady(baseline,healthError);
        return !healthError || !*healthError;
    } error:error];
}
