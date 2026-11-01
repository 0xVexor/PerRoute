#import "TunSession.h"
typedef PRTunSession *(^PRTunCreate)(NSError **error);
typedef BOOL (^PRTunHealth)(BOOL *ready, NSError **error);

// All methods run on the helper's one serial work queue. Owner identity is the
// authenticated connection object supplied by the listener, never a request field.
@interface PRTunAuthority : NSObject
@property(nonatomic,readonly) BOOL active;
- (instancetype)initWithStore:(PRTunStore *)store;
- (BOOL)recoveryForUser:(uid_t)uid;
- (BOOL)ownedBy:(id)owner user:(uid_t)uid;
- (NSDictionary *)startOwner:(id)owner user:(uid_t)uid create:(PRTunCreate)create health:(PRTunHealth)health error:(NSError **)error;
- (NSDictionary *)statusOwner:(id)owner user:(uid_t)uid error:(NSError **)error;
- (BOOL)stopOwner:(id)owner user:(uid_t)uid error:(NSError **)error;
- (void)invalidate:(id)owner;
- (BOOL)recover:(NSError **)error;
@end
