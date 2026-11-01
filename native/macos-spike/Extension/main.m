#import <Foundation/Foundation.h>
#import <NetworkExtension/NetworkExtension.h>

@interface PerRouteSpikeProvider : NETransparentProxyProvider
@end

@implementation PerRouteSpikeProvider
- (void)startProxyWithOptions:(NSDictionary<NSString *, id> *)options completionHandler:(void (^)(NSError *))completionHandler {
    // Activation is the first feasibility gate. Never report a usable proxy before a relay exists.
    completionHandler([NSError errorWithDomain:@"app.perroute.spike" code:1 userInfo:@{
        NSLocalizedDescriptionKey: @"The feasibility provider does not implement forwarding."
    }]);
}
- (void)stopProxyWithReason:(NEProviderStopReason)reason completionHandler:(void (^)(void))completionHandler {
    completionHandler();
}
- (BOOL)handleNewFlow:(NEAppProxyFlow *)flow { return NO; }
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        [NEProvider startSystemExtensionMode];
        dispatch_main();
    }
}
