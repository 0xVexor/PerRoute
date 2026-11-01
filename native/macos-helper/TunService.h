#import "ProxyHelper.h"
#import "../macos-tun/TunAuthority.h"
NSDictionary *PRTunCapabilities(NSString *team);
NSDictionary *PRStartTun(NSDictionary *request, uid_t uid, NSXPCConnection *connection,
    NSString *team, PRTunStore *store, PRTunAuthority *authority, NSError **error);
