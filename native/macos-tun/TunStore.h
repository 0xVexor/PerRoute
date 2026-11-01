#import <Foundation/Foundation.h>
#import "EngineWorker.h"

// Protected native storage, never a wire API. The production initializer is root-only;
// the descriptor initializer lets isolated tests exercise identical filesystem behavior.
@interface PRTunStore : NSObject
- (instancetype)initSystem:(NSError **)error;
- (instancetype)initWithDirectoryDescriptor:(int)directory error:(NSError **)error;
- (NSArray<NSString *> *)sessions:(NSError **)error;
- (NSString *)createForUser:(uid_t)uid error:(NSError **)error;
- (NSString *)path:(NSString *)session file:(NSString *)name;
- (NSDictionary *)lease:(NSString *)session error:(NSError **)error;
- (BOOL)write:(NSData *)data session:(NSString *)session file:(NSString *)name error:(NSError **)error;
- (BOOL)copySigned:(NSString *)source session:(NSString *)session file:(NSString *)name
       requirement:(NSString *)requirement sourceUser:(uid_t)user error:(NSError **)error;
- (BOOL)record:(const struct PRWorkerIdentity *)identity session:(NSString *)session worker:(BOOL)worker error:(NSError **)error;
- (BOOL)cleanup:(NSString *)session error:(NSError **)error;
@end
NSError *PRTunError(NSString *message);
BOOL PRTunSigned(NSString *path, NSString *requirement);
