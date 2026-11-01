#import <Foundation/Foundation.h>
#import <SystemExtensions/SystemExtensions.h>

// Keep callbacks native. Java polls snapshots and never runs on Apple's callback queue.
@interface PerRouteExtensionRequest : NSObject <OSSystemExtensionRequestDelegate>
@property(nonatomic, strong) OSSystemExtensionRequest *request;
@property(nonatomic, copy) NSString *state;
@property(nonatomic, copy) NSString *detail;
@end

@implementation PerRouteExtensionRequest
- (void)requestNeedsUserApproval:(OSSystemExtensionRequest *)request {
    @synchronized(self) { self.state = @"approval-required"; self.detail = @"Approve the extension in System Settings while this process remains running."; }
}
- (OSSystemExtensionReplacementAction)request:(OSSystemExtensionRequest *)request
                 actionForReplacingExtension:(OSSystemExtensionProperties *)existing
                               withExtension:(OSSystemExtensionProperties *)extension {
    @synchronized(self) { self.state = @"replacement-refused"; self.detail = @"This spike does not replace an installed extension."; }
    return OSSystemExtensionReplacementActionCancel;
}
- (void)request:(OSSystemExtensionRequest *)request didFinishWithResult:(OSSystemExtensionRequestResult)result {
    @synchronized(self) {
        self.state = result == OSSystemExtensionRequestWillCompleteAfterReboot ? @"reboot-required" : @"completed";
        self.detail = @"System extension request finished. This does not establish proxy forwarding.";
        self.request = nil;
    }
}
- (void)request:(OSSystemExtensionRequest *)request didFailWithError:(NSError *)error {
    @synchronized(self) {
        self.state = @"failed";
        self.detail = [NSString stringWithFormat:@"%@: %ld: %@", error.domain, (long)error.code, error.localizedDescription];
        self.request = nil;
    }
}
@end

static PerRouteExtensionRequest *controller(void) {
    static PerRouteExtensionRequest *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        instance = [PerRouteExtensionRequest new];
        instance.state = @"idle";
        instance.detail = @"No activation or proxy configuration has been requested.";
    });
    return instance;
}

// Returned UTF-8 buffers belong to the caller and must be released with perroute_extension_free.
char *perroute_extension_snapshot(void) {
    @autoreleasepool {
        PerRouteExtensionRequest *value = controller();
        @synchronized(value) {
            NSBundle *bundle = NSBundle.mainBundle;
            NSDictionary *snapshot = @{
                @"state": value.state,
                @"detail": value.detail,
                @"bundleIdentifier": bundle.bundleIdentifier ?: @"",
                @"bundlePath": bundle.bundlePath ?: @"",
                @"embeddedExtensionsPath": [bundle.bundlePath stringByAppendingPathComponent:@"Contents/Library/SystemExtensions"],
                @"requestPending": value.request != nil ? @YES : @NO
            };
            NSData *data = [NSJSONSerialization dataWithJSONObject:snapshot options:0 error:nil];
            return strdup([[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String);
        }
    }
}

void perroute_extension_free(void *buffer) { free(buffer); }

int perroute_extension_request(const char *identifier, int activate) {
    @autoreleasepool {
        PerRouteExtensionRequest *value = controller();
        @synchronized(value) {
            if (value.request != nil) return 0;
            NSString *target = identifier == NULL ? nil : [NSString stringWithUTF8String:identifier];
            NSBundle *bundle = NSBundle.mainBundle;
            NSString *extensions = [bundle.bundlePath stringByAppendingPathComponent:@"Contents/Library/SystemExtensions"];
            BOOL embedded = NO;
            for (NSString *file in [NSFileManager.defaultManager contentsOfDirectoryAtPath:extensions error:nil]) {
                NSBundle *candidate = [NSBundle bundleWithPath:[extensions stringByAppendingPathComponent:file]];
                if ([candidate.bundleIdentifier isEqualToString:target] && [file.pathExtension isEqualToString:@"systemextension"]) embedded = YES;
            }
            if (target.length == 0 || ![bundle.bundlePath.pathExtension isEqualToString:@"app"] || !embedded) {
                value.state = @"invalid-container";
                value.detail = @"Run from the actual app bundle with the matching extension embedded in Contents/Library/SystemExtensions.";
                return 0;
            }
            // Apple validates signatures, entitlements and installation location when processing this request.
            dispatch_queue_t queue = dispatch_queue_create("app.perroute.spike.extension-events", DISPATCH_QUEUE_SERIAL);
            value.request = activate
                ? [OSSystemExtensionRequest activationRequestForExtension:target queue:queue]
                : [OSSystemExtensionRequest deactivationRequestForExtension:target queue:queue];
            value.request.delegate = value;
            value.state = @"submitted";
            value.detail = activate ? @"Activation submitted." : @"Deactivation submitted.";
            [OSSystemExtensionManager.sharedManager submitRequest:value.request];
            return 1;
        }
    }
}
