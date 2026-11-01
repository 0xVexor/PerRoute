#import <Foundation/Foundation.h>

static NSString *const PRHelperLabel = @"app.perroute.proxy-helper";
static NSString *const PRAppIdentifier = @"app.perroute.spike";
static NSString *const PRTunEngineIdentifier = @"app.perroute.tun-engine";
static NSString *const PRXrayIdentifier = @"app.perroute.xray";
static const NSUInteger PRProtocolVersion = 1;

@protocol PRProxyHelper
- (void)request:(NSDictionary *)request reply:(void (^)(NSDictionary *))reply;
@end

NSXPCInterface *PRHelperInterface(void);
NSString *PRSigningTeam(void);
BOOL PRSignedSelf(NSString *identifier);
NSString *PRRequirement(NSString *identifier, NSString *team);
NSError *PRError(NSString *message);
NSDictionary *PRSessionStatus(BOOL pending, BOOL owned);
NSDictionary *PRApplySettings(NSDictionary *original, NSInteger http, NSInteger socks, NSError **error);
NSDictionary *PRRestoreSettings(NSDictionary *current, NSDictionary *original, NSDictionary *applied, BOOL *conflict);
BOOL PRSettingsEquivalent(NSDictionary *a, NSDictionary *b);
BOOL PREffectiveEquivalent(NSDictionary *desired, NSDictionary *observed);
