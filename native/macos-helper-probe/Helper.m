#import "Identity.h"
#import "SigningPin.h"
#import <SystemConfiguration/SystemConfiguration.h>
#import <unistd.h>

static uid_t ConsoleUser(void) {
    uid_t uid = 0;
    CFStringRef name = SCDynamicStoreCopyConsoleUser(NULL, &uid, NULL);
    if (name) CFRelease(name);
    return uid;
}

@interface PRProbeClient : NSObject <PRProbeProtocol>
@property(nonatomic) uid_t uid;
@end
@implementation PRProbeClient
- (void)request:(NSDictionary *)request reply:(void (^)(NSDictionary *))reply {
    reply(PRProbeReply(request, self.uid, ConsoleUser()));
}
@end

@interface PRProbeListener : NSObject <NSXPCListenerDelegate>
@end
@implementation PRProbeListener
- (BOOL)listener:(NSXPCListener *)listener shouldAcceptNewConnection:(NSXPCConnection *)connection {
    (void)listener;
    uid_t uid = ConsoleUser();
    if (uid == 0 || connection.effectiveUserIdentifier != uid) return NO;
    PRProbeClient *client = [PRProbeClient new];
    client.uid = uid;
    connection.exportedInterface = [NSXPCInterface interfaceWithProtocol:@protocol(PRProbeProtocol)];
    connection.exportedObject = client;
    [connection resume];
    return YES;
}
@end

static NSXPCListener *Listener;
static PRProbeListener *Delegate;

int main(void) {
    @autoreleasepool {
        if (geteuid() != 0 || !PRProbeCheckSelf(PRProbeService)) return 78;
        // No preference writes, recovery journal, startup restoration or proxy code is linked here.
        Listener = [[NSXPCListener alloc] initWithMachServiceName:PRProbeService];
        [Listener setConnectionCodeSigningRequirement:PRProbeRequirement(PRProbeApp, @PR_PROBE_CERT_SHA1)];
        Delegate = [PRProbeListener new];
        Listener.delegate = Delegate;
        [Listener resume];
        [NSRunLoop.currentRunLoop run];
    }
    return 0;
}
