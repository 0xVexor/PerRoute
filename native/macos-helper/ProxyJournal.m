#import "ProxyJournal.h"
#import "ProxyHelper.h"
#import <sys/stat.h>
#import <fcntl.h>
#import <unistd.h>
#import <errno.h>

@implementation PRRootJournal {
    int _directory;
}
- (instancetype)initWithError:(NSError **)error {
    self = [super init];
    if (!self) return nil;
    _directory = -1;
    int parent = open("/Library/Application Support", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    for (const char **name = (const char *[]){"PerRoute", "ProxyHelper", NULL}; parent >= 0 && *name; name++) {
        if (mkdirat(parent, *name, 0700) != 0 && errno != EEXIST) { close(parent); parent = -1; break; }
        int child = openat(parent, *name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(parent);
        struct stat info;
        if (child < 0 || fstat(child, &info) != 0 || info.st_uid != 0 || (info.st_mode & 0022)) {
            if (child >= 0) close(child);
            parent = -1; break;
        }
        parent = child;
    }
    if (parent < 0) { if (error) *error = PRError(@"The helper recovery directory is unsafe or unavailable"); return nil; }
    _directory = parent;
    return self;
}
- (void)dealloc { if (_directory >= 0) close(_directory); }
- (instancetype)initWithDirectoryDescriptor:(int)directory error:(NSError **)error {
    self = [super init];
    if (!self) return nil;
    _directory = -1;
    struct stat info;
    if (fstat(directory, &info) != 0 || !S_ISDIR(info.st_mode) || info.st_uid != geteuid() || (info.st_mode & 0022) ||
        (_directory = fcntl(directory, F_DUPFD_CLOEXEC, 0)) < 0) {
        if (error) *error = PRError(@"Unsafe recovery directory descriptor"); return nil;
    }
    return self;
}
- (NSDictionary *)load:(NSError **)error {
    int fd = openat(_directory, "lease.plist", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        if (errno != ENOENT && error) *error = PRError(@"Cannot open helper recovery record");
        return nil;
    }
    struct stat info;
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != geteuid() || info.st_nlink != 1 ||
        (info.st_mode & 0077) || info.st_size < 1 || info.st_size > 1048576) {
        close(fd); if (error) *error = PRError(@"Unsafe helper recovery record; existing data was preserved"); return nil;
    }
    NSMutableData *data = [NSMutableData dataWithLength:(NSUInteger)info.st_size];
    NSUInteger offset = 0;
    while (offset < data.length) {
        ssize_t count = read(fd, (char *)data.mutableBytes + offset, data.length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += (NSUInteger)count;
    }
    close(fd);
    if (offset != data.length) { if (error) *error = PRError(@"Incomplete helper recovery record"); return nil; }
    id value = [NSPropertyListSerialization propertyListWithData:data options:NSPropertyListImmutable format:NULL error:error];
    if (![value isKindOfClass:NSDictionary.class] || ![value[@"version"] isEqual:@1] ||
        ![value[@"uid"] isKindOfClass:NSNumber.class] || [value[@"uid"] longLongValue] <= 0 ||
        ![value[@"service"] isKindOfClass:NSString.class] || ![[NSUUID alloc] initWithUUIDString:value[@"service"]] ||
        ![value[@"original"] isKindOfClass:NSDictionary.class] || ![value[@"applied"] isKindOfClass:NSDictionary.class]) {
        if (error) *error = PRError(@"Invalid helper recovery record; existing data was preserved"); return nil;
    }
    return value;
}
- (BOOL)save:(NSDictionary *)lease error:(NSError **)error {
    NSData *data = [NSPropertyListSerialization dataWithPropertyList:lease format:NSPropertyListBinaryFormat_v1_0 options:0 error:error];
    if (!data || data.length > 1048576) return NO;
    NSString *temporary = [@"lease-" stringByAppendingString:NSUUID.UUID.UUIDString];
    int fd = openat(_directory, temporary.fileSystemRepresentation, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) { if (error) *error = PRError(@"Cannot create helper recovery intent"); return NO; }
    NSUInteger offset = 0;
    while (offset < data.length) {
        ssize_t count = write(fd, (const char *)data.bytes + offset, data.length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += (NSUInteger)count;
    }
    BOOL ok = offset == data.length && fsync(fd) == 0;
    if (close(fd) != 0) ok = NO;
    if (ok) ok = renameat(_directory, temporary.fileSystemRepresentation, _directory, "lease.plist") == 0;
    if (ok) ok = fsync(_directory) == 0;
    if (!ok) { unlinkat(_directory, temporary.fileSystemRepresentation, 0); if (error) *error = PRError(@"Cannot persist helper recovery intent"); }
    return ok;
}
- (BOOL)clear:(NSError **)error {
    if ((unlinkat(_directory, "lease.plist", 0) != 0 && errno != ENOENT) || fsync(_directory) != 0) {
        if (error) *error = PRError(@"Cannot finish helper recovery record"); return NO;
    }
    return YES;
}
@end
