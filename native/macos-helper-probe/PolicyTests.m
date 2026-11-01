#import "Identity.h"

static int Checks;
static void Check(BOOL value) { if (!value) abort(); Checks++; }
int main(void) {
    @autoreleasepool {
        NSString *pin = [@"a" stringByPaddingToLength:40 withString:@"a" startingAtIndex:0];
        Check(PRProbeRequirement(PRProbeApp, pin) != nil);
        Check(PRProbeRequirement(PRProbeService, pin) != nil);
        for (NSString *invalid in @[@"", @"a", [pin stringByAppendingString:@"a"], [pin stringByReplacingCharactersInRange:NSMakeRange(0, 1) withString:@"z"], @"\" or anchor apple"]) Check(PRProbeRequirement(PRProbeApp, invalid) == nil);
        Check(PRProbeRequirement(@"untrusted.identifier", pin) == nil);
        Check(PRProbeRequirement(nil, pin) == nil);
        Check(PRProbeRequirement(PRProbeApp, nil) == nil);
        Check([PRProbeReply(@{@"version": @1, @"operation": @"status"}, 501, 501)[@"ok"] boolValue]);
        Check(![PRProbeReply(@{@"version": @1, @"operation": @"status"}, 501, 502)[@"ok"] boolValue]);
        Check(![PRProbeReply(@{@"version": @1, @"operation": @"status"}, 0, 0)[@"ok"] boolValue]);
        for (NSString *op in @[@"apply", @"restore", @"prepare", @"unregister", @""]) Check(![PRProbeReply(@{@"version": @1, @"operation": op}, 501, 501)[@"ok"] boolValue]);
        Check(![PRProbeReply(@{@"version": @2, @"operation": @"status"}, 501, 501)[@"ok"] boolValue]);
        Check(![PRProbeReply(@{@"version": @1, @"operation": @"status", @"extra": @YES}, 501, 501)[@"ok"] boolValue]);
        Check(![PRProbeReply(@{}, 501, 501)[@"ok"] boolValue]);
        Check(!PRProbeCheckSelf(PRProbeApp));
        printf("Helper probe policy: %d assertions passed\n", Checks);
    }
}
