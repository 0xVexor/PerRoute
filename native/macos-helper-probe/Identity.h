#import <Foundation/Foundation.h>
#import <Security/Security.h>

static NSString *const PRProbeApp = @"app.perroute.helper-probe";
static NSString *const PRProbeService = @"app.perroute.helper-probe.daemon";

@protocol PRProbeProtocol
- (void)request:(NSDictionary *)request reply:(void (^)(NSDictionary *))reply;
@end

NSString *PRProbeRequirement(NSString *identifier, NSString *fingerprint);
BOOL PRProbeCheckSelf(NSString *identifier);
BOOL PRProbeCheckFile(NSURL *url, NSString *identifier);
NSDictionary *PRProbeReply(NSDictionary *request, uid_t caller, uid_t console);
void PRProbePrint(NSDictionary *value);
