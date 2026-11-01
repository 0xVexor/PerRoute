#import "Identity.h"
#import "SigningPin.h"
#import <ServiceManagement/ServiceManagement.h>

static int Remote(NSString *operation) {
    NSXPCConnection *connection = [[NSXPCConnection alloc] initWithMachServiceName:PRProbeService options:NSXPCConnectionPrivileged];
    connection.remoteObjectInterface = [NSXPCInterface interfaceWithProtocol:@protocol(PRProbeProtocol)];
    [connection setCodeSigningRequirement:PRProbeRequirement(PRProbeService, @PR_PROBE_CERT_SHA1)];
    [connection resume];
    // A late reply and timeout can race. A lock protects the reply while bounded waiting avoids
    // publishing an unsynchronized result or treating a submitted request as success.
    NSObject *lock = [NSObject new];
    __block NSDictionary *result = nil;
    dispatch_semaphore_t ready = dispatch_semaphore_create(0);
    id<PRProbeProtocol> proxy = [connection remoteObjectProxyWithErrorHandler:^(NSError *error) {
        @synchronized(lock) { result = @{@"ok": @NO, @"error": error.localizedDescription}; }
        dispatch_semaphore_signal(ready);
    }];
    [proxy request:@{@"version": @1, @"operation": operation} reply:^(NSDictionary *reply) {
        @synchronized(lock) { result = reply; }
        dispatch_semaphore_signal(ready);
    }];
    BOOL timedOut = dispatch_semaphore_wait(ready, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0;
    NSDictionary *reply;
    @synchronized(lock) { reply = timedOut ? @{@"ok": @NO, @"error": @"Authenticated XPC timed out"} : result; }
    [connection invalidate];
    PRProbePrint(reply ?: @{@"ok": @NO, @"error": @"No reply"});
    if ([operation isEqual:@"apply"]) return !timedOut && [reply[@"error"] isEqual:@"This probe supports only status; proxy mutation is unavailable"] ? 0 : 1;
    return [reply[@"ok"] boolValue] && [reply[@"version"] isEqual:@1] && [reply[@"readOnly"] boolValue] && [reply[@"euid"] isEqual:@0] && [reply[@"pid"] intValue] > 0 ? 0 : 1;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 2) { fprintf(stderr, "Use status, register, unregister, request-status or reject-apply\n"); return 64; }
        NSString *operation = [NSString stringWithUTF8String:argv[1]];
        if (!PRProbeCheckSelf(PRProbeApp) || ![NSBundle.mainBundle.bundleIdentifier isEqual:PRProbeApp]) {
            PRProbePrint(@{@"ok": @NO, @"error": @"Host certificate or bundle identity mismatch"}); return 78;
        }
        NSURL *helper = [NSBundle.mainBundle.bundleURL URLByAppendingPathComponent:@"Contents/Library/HelperTools/PerRouteHelperProbe"];
        if (!PRProbeCheckFile(helper, PRProbeService)) {
            PRProbePrint(@{@"ok": @NO, @"error": @"Embedded helper certificate or identifier mismatch"}); return 78;
        }
        SMAppService *service = [SMAppService daemonServiceWithPlistName:[PRProbeService stringByAppendingString:@".plist"]];
        if ([operation isEqual:@"request-status"] || [operation isEqual:@"reject-apply"])
            return Remote([operation isEqual:@"request-status"] ? @"status" : @"apply");
        NSError *error = nil;
        BOOL ok = YES;
        if ([operation isEqual:@"register"]) ok = [service registerAndReturnError:&error];
        else if ([operation isEqual:@"unregister"]) ok = [service unregisterAndReturnError:&error];
        else if (![operation isEqual:@"status"]) return 64;
        PRProbePrint(@{@"ok": @(ok), @"status": @(service.status), @"errorDomain": error.domain ?: @"",
            @"errorCode": @(error.code), @"error": error.localizedDescription ?: @"", @"readOnly": @YES});
        return ok ? 0 : 1;
    }
}
