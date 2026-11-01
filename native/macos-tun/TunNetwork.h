#import <Foundation/Foundation.h>
// Typed native failure, never inferred from a localized message or caller input.
static inline NSError *PRTunNetworkChangedError(void) {
    return [NSError errorWithDomain:@"app.perroute.tun.network" code:1
        userInfo:@{NSLocalizedDescriptionKey:@"The primary network changed; the routing session must be restarted"}];
}
static inline BOOL PRTunIsNetworkChange(NSError *error) {
    return [error.domain isEqual:@"app.perroute.tun.network"] && error.code == 1;
}
BOOL PRTunPrimaryMatches(NSDictionary *baseline, NSString *primary, unsigned index);
extern NSString *const PRTunInterface;
NSArray<NSDictionary *> *PRTunDecodeRoutes(NSData *bytes, NSError **error);
NSArray<NSDictionary *> *PRTunReadRoutes(NSError **error);
NSDictionary *PRTunNetworkBaseline(NSError **error);
BOOL PRTunRoutesCoverCaptureRange(NSArray<NSDictionary *> *routes, unsigned index);
BOOL PRTunNetworkReady(NSDictionary *baseline, NSError **error);
