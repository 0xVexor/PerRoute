#import "TunStore.h"

typedef NSData *(^PRTunConfigurationBuilder)(NSString *stagedEngine, NSError **error);

// Internal orchestration only. Production callers supply fixed verified payload paths
// and native-generated configuration, never paths or engine JSON from an XPC request.
@interface PRTunSession : NSObject
@property(nonatomic,readonly) NSString *identifier;
@property(nonatomic,readonly) pid_t workerPID;
@property(nonatomic,readonly) BOOL spawned;
- (instancetype)initWithStore:(PRTunStore *)store user:(uid_t)uid
    engine:(NSString *)engine worker:(NSString *)worker configuration:(NSData *)configuration
    engineRequirement:(NSString *)engineRequirement workerRequirement:(NSString *)workerRequirement error:(NSError **)error;
- (instancetype)initWithStore:(PRTunStore *)store user:(uid_t)uid
    engine:(NSString *)engine worker:(NSString *)worker configurationBuilder:(PRTunConfigurationBuilder)builder
    engineRequirement:(NSString *)engineRequirement workerRequirement:(NSString *)workerRequirement error:(NSError **)error;
- (BOOL)poll:(NSError **)error;
- (BOOL)stop:(NSError **)error;
@end
// Called only by the isolated, signed worker entry point. It owns control until return.
int PRRunStagedTunWorker(PRTunStore *store, NSString *identifier, int control);
