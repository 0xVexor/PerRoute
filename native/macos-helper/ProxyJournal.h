#import <Foundation/Foundation.h>

@interface PRRootJournal : NSObject
- (instancetype)initWithError:(NSError **)error;
- (instancetype)initWithDirectoryDescriptor:(int)directory error:(NSError **)error;
- (NSDictionary *)load:(NSError **)error;
- (BOOL)save:(NSDictionary *)lease error:(NSError **)error;
- (BOOL)clear:(NSError **)error;
@end
