#import "TunStore.h"
#import "TunCapture.h"
#import <net/if.h>
#import <Security/Security.h>
#import <libproc.h>
#import <sys/proc_info.h>
#import <sys/proc.h>
#import <sys/sysctl.h>
#import <limits.h>
#import <sys/stat.h>
#import <sys/acl.h>
#import <fcntl.h>
#import <dirent.h>
#import <signal.h>
#import <unistd.h>
#import <errno.h>

NSError *PRTunError(NSString *message) {
    return [NSError errorWithDomain:@"app.perroute.tun" code:1 userInfo:@{NSLocalizedDescriptionKey:message}];
}
static BOOL Fail(NSError **error, NSString *message) { if (error) *error = PRTunError(message); return NO; }
static BOOL UUIDName(NSString *name) {
    return [name isKindOfClass:NSString.class] && [[[[NSUUID alloc] initWithUUIDString:name] UUIDString] isEqual:name];
}
static BOOL PrivateACL(int fd) {
    errno = 0;
    acl_t acl = acl_get_fd_np(fd,ACL_TYPE_EXTENDED);
    // Darwin reports ENOENT for a valid descriptor without an extended ACL.
    // Reject present ACLs conservatively, including empty explicit ACL objects.
    BOOL absent = !acl && errno == ENOENT;
    if (acl) acl_free(acl);
    return absent;
}
static BOOL OwnedDirectory(int fd) {
    struct stat info;
    return fd >= 0 && !fstat(fd, &info) && S_ISDIR(info.st_mode) && info.st_uid == geteuid() && !(info.st_mode & 0077) && PrivateACL(fd);
}
static BOOL KnownFile(NSString *name) {
    return [@[@"lease.json", @"engine.json", @"worker.json", @"config.json", @"capture.json", @"engine", @"worker"] containsObject:name];
}
BOOL PRTunSigned(NSString *path, NSString *text) {
    if (![path isKindOfClass:NSString.class] || !path.isAbsolutePath || ![text isKindOfClass:NSString.class] || !text.length) return NO;
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    BOOL ok = SecStaticCodeCreateWithPath((__bridge CFURLRef)[NSURL fileURLWithPath:path], kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)text, kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate | kSecCSCheckAllArchitectures, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return ok;
}
static NSDictionary *Process(pid_t pid, NSString *path) {
    struct proc_bsdinfo info = {0};
    char executable[PROC_PIDPATHINFO_MAXSIZE];
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) != sizeof(info) || info.pbi_uid != geteuid() ||
        proc_pidpath(pid, executable, sizeof(executable)) <= 0 || ![path isEqual:[NSString stringWithUTF8String:executable]]) return nil;
    return @{@"pid":@(pid), @"seconds":@(info.pbi_start_tvsec), @"microseconds":@(info.pbi_start_tvusec)};
}
static BOOL Integer(id value, unsigned long long minimum, unsigned long long maximum) {
    return [value isKindOfClass:NSNumber.class] && CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID() &&
        [value doubleValue] >= minimum && [value doubleValue] <= maximum &&
        [value doubleValue] == [value unsignedLongLongValue];
}
// A failed path lookup is not evidence that a recorded process exited. Verify the
// kernel identity first and retain recovery state whenever its status is uncertain.
static int IdentityState(NSDictionary *identity, NSString *path) {
    pid_t pid = [identity[@"pid"] intValue];
    struct proc_bsdinfo info = {0};
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) != sizeof(info)) {
        // libproc omits some zombies while kill(pid, 0) still succeeds. A
        // kernel zombie cannot execute; its actual parent remains responsible for reaping.
        struct kinfo_proc kernel = {0};
        size_t length = sizeof(kernel);
        int query[] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, pid};
        if (!sysctl(query, 4, &kernel, &length, NULL, 0) &&
            (!length || kernel.kp_proc.p_stat == SZOMB)) return 0;
        return kill(pid, 0) && errno == ESRCH ? 0 : -1;
    }
    if (info.pbi_start_tvsec != [identity[@"seconds"] unsignedLongLongValue] ||
        info.pbi_start_tvusec != [identity[@"microseconds"] unsignedLongLongValue] || info.pbi_status == SZOMB) return 0;
    return [Process(pid, path) isEqual:identity] ? 1 : -1;
}
static BOOL Stop(NSDictionary *identity, NSString *path) {
    pid_t pid = [identity[@"pid"] intValue];
    for (NSNumber *signal in @[@(SIGTERM), @(SIGKILL)]) {
        int state = IdentityState(identity, path);
        if (state != 1) return state == 0;
        if (kill(pid, signal.intValue) && errno != ESRCH) return NO;
        for (int attempt = 0; attempt < 40; attempt++) {
            state = IdentityState(identity, path);
            if (state != 1) return state == 0;
            usleep(25000);
        }
    }
    return IdentityState(identity, path) == 0;
}
static NSArray<NSString *> *Files(int directory) {
    int duplicate = openat(directory, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    DIR *entries = duplicate >= 0 ? fdopendir(duplicate) : NULL;
    if (!entries) { if (duplicate >= 0) close(duplicate); return nil; }
    NSMutableArray *names = [NSMutableArray array];
    struct dirent *entry;
    BOOL ok = YES;
    errno = 0;
    while ((entry = readdir(entries))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        NSString *name = [NSString stringWithUTF8String:entry->d_name];
        struct stat info;
        BOOL temporary = [name hasPrefix:@".write-"] && UUIDName([name substringFromIndex:7]);
        if ((!KnownFile(name) && !temporary) || fstatat(directory, entry->d_name, &info, AT_SYMLINK_NOFOLLOW) ||
            !S_ISREG(info.st_mode) || info.st_uid != geteuid() || info.st_nlink != 1 || (info.st_mode & 0077)) { ok = NO; break; }
        [names addObject:name]; errno = 0;
    }
    if (errno) ok = NO;
    closedir(entries);
    return ok ? [names copy] : nil;
}
static BOOL CaptureGone(int directory) {
    int fd = openat(directory,"capture.json",O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC);
    if (fd < 0) return errno == ENOENT;
    struct stat info;
    BOOL ok = !fstat(fd,&info) && S_ISREG(info.st_mode) && info.st_uid == geteuid() && info.st_nlink == 1 &&
        !(info.st_mode & 0077) && info.st_size > 0 && info.st_size <= 4096;
    NSMutableData *data = ok ? [NSMutableData dataWithLength:info.st_size] : nil;
    NSUInteger offset = 0;
    while (ok && offset < data.length) {
        ssize_t count = read(fd,(char *)data.mutableBytes+offset,data.length-offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { ok = NO; break; } offset += count;
    }
    close(fd);
    id marker = ok ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil;
    if (![marker isKindOfClass:NSDictionary.class] || [marker count] != 2 || !Integer(marker[@"version"],1,1) ||
        ![marker[@"interface"] isEqual:@PR_TUN_INTERFACE]) return NO;
    for (int i=0;i<20 && if_nametoindex(PR_TUN_INTERFACE);i++) usleep(25000);
    // An engine exit alone is insufficient to clear capture recovery ownership.
    return if_nametoindex(PR_TUN_INTERFACE) == 0;
}
static BOOL StopStaged(NSString *path) {
    const int capacity = 32768;
    pid_t *pids = calloc(capacity, sizeof(pid_t));
    int bytes = pids ? proc_listpids(PROC_ALL_PIDS, 0, pids, capacity * sizeof(pid_t)) : -1;
    BOOL ok = bytes > 0 && bytes < (int)(capacity * sizeof(pid_t));
    for (int i = 0; ok && i < bytes / (int)sizeof(pid_t); i++) {
        if (pids[i] <= 0 || pids[i] == getpid()) continue;
        NSDictionary *identity = Process(pids[i], path);
        if (identity && !Stop(identity, path)) ok = NO;
    }
    free(pids);
    return ok;
}

@implementation PRTunStore { int _directory; NSString *_path; }
- (instancetype)initSystem:(NSError **)error {
    _directory = -1;
    if (geteuid() != 0) { Fail(error, @"TUN storage requires the root helper"); return nil; }
    int parent = open("/Library/Application Support", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat info;
    if (parent < 0 || fstat(parent, &info) || info.st_uid || (info.st_mode & 0022)) {
        if (parent >= 0) close(parent); Fail(error, @"Unsafe TUN storage parent"); return nil;
    }
    for (const char **name = (const char *[]){"PerRoute", "TunHelper", NULL}; parent >= 0 && *name; name++) {
        if (mkdirat(parent, *name, 0700) && errno != EEXIST) { close(parent); parent = -1; break; }
        int child = openat(parent, *name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        BOOL persisted = !fsync(parent);
        close(parent);
        if (!persisted || !OwnedDirectory(child)) { if (child >= 0) close(child); parent = -1; break; }
        parent = child;
    }
    if (parent < 0) { Fail(error, @"Unsafe or unavailable TUN recovery directory"); return nil; }
    self = [self initWithDirectoryDescriptor:parent error:error]; close(parent); return self;
}
- (instancetype)initWithDirectoryDescriptor:(int)directory error:(NSError **)error {
    self = [super init]; if (!self) return nil;
    _directory = -1;
    char path[PATH_MAX];
    if (!OwnedDirectory(directory) || fcntl(directory, F_GETPATH, path) ||
        (_directory = fcntl(directory, F_DUPFD_CLOEXEC, 3)) < 0) {
        Fail(error, @"Unsafe TUN storage descriptor"); return nil;
    }
    _path = [[NSString stringWithUTF8String:path] copy]; return self;
}
- (void)dealloc { if (_directory >= 0) close(_directory); }
- (int)openSession:(NSString *)session {
    if (!UUIDName(session)) { errno = EINVAL; return -1; }
    int fd = openat(_directory, session.fileSystemRepresentation, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd >= 0 && !OwnedDirectory(fd)) { close(fd); errno = EPERM; return -1; }
    return fd;
}
- (NSString *)path:(NSString *)session file:(NSString *)name {
    if (!UUIDName(session) || !KnownFile(name)) return nil;
    return [[_path stringByAppendingPathComponent:session] stringByAppendingPathComponent:name];
}
- (NSArray<NSString *> *)sessions:(NSError **)error {
    int duplicate = openat(_directory, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    DIR *entries = duplicate >= 0 ? fdopendir(duplicate) : NULL;
    if (!entries) { if (duplicate >= 0) close(duplicate); Fail(error, @"Cannot inspect TUN recovery"); return nil; }
    NSMutableArray *names = [NSMutableArray array];
    struct dirent *entry;
    BOOL ok = YES;
    errno = 0;
    while ((entry = readdir(entries))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        NSString *name = [NSString stringWithUTF8String:entry->d_name];
        if (!UUIDName(name) || names.count >= 128) { ok = NO; break; }
        int child = [self openSession:name];
        if (child < 0) { ok = NO; break; } close(child);
        [names addObject:name]; errno = 0;
    }
    if (errno) ok = NO;
    closedir(entries);
    if (!ok) { Fail(error, @"Unknown or unsafe TUN recovery entry; existing data preserved"); return nil; }
    return [names copy];
}
- (NSString *)createForUser:(uid_t)uid error:(NSError **)error {
    if (!uid) { Fail(error, @"Invalid TUN session user"); return nil; }
    NSString *session = NSUUID.UUID.UUIDString;
    if (mkdirat(_directory, session.fileSystemRepresentation, 0700) || fsync(_directory)) {
        Fail(error, @"Cannot persist TUN session directory"); return nil;
    }
    NSData *lease = [NSJSONSerialization dataWithJSONObject:@{@"version":@1,@"uid":@(uid),@"session":session} options:0 error:error];
    if (![self write:lease session:session file:@"lease.json" error:error]) return nil;
    return session;
}
- (BOOL)write:(NSData *)data session:(NSString *)session file:(NSString *)name error:(NSError **)error {
    if (![data isKindOfClass:NSData.class] || !data.length || data.length > 1048576 ||
        ![@[@"lease.json", @"engine.json", @"worker.json", @"config.json", @"capture.json"] containsObject:name]) return Fail(error, @"Invalid TUN recovery write");
    int directory = [self openSession:session];
    if (directory < 0) return Fail(error, @"Cannot open TUN recovery session");
    NSString *temporary = [@".write-" stringByAppendingString:NSUUID.UUID.UUIDString];
    int fd = openat(directory, temporary.fileSystemRepresentation, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    BOOL ok = fd >= 0;
    NSUInteger offset = 0;
    while (ok && offset < data.length) {
        ssize_t count = write(fd, (const char *)data.bytes + offset, data.length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { ok = NO; break; } offset += count;
    }
    if (ok) ok = !fchmod(fd, 0600) && !fsync(fd);
    if (fd >= 0 && close(fd)) ok = NO;
    if (ok) ok = !renameat(directory, temporary.fileSystemRepresentation, directory, name.fileSystemRepresentation) && !fsync(directory);
    if (!ok) unlinkat(directory, temporary.fileSystemRepresentation, 0);
    close(directory);
    return ok ? YES : Fail(error, @"TUN recovery write failed; recovery remains pending");
}
- (NSDictionary *)lease:(NSString *)session error:(NSError **)error {
    int directory = [self openSession:session];
    int fd = directory >= 0 ? openat(directory, "lease.json", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC) : -1;
    if (directory >= 0) close(directory);
    struct stat info;
    if (fd < 0 || fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_uid != geteuid() || info.st_nlink != 1 ||
        (info.st_mode & 0077) || info.st_size < 1 || info.st_size > 4096) {
        if (fd >= 0) close(fd); Fail(error, @"Unsafe TUN recovery lease"); return nil;
    }
    NSMutableData *bytes = [NSMutableData dataWithLength:(NSUInteger)info.st_size];
    NSUInteger offset = 0;
    while (offset < bytes.length) {
        ssize_t count = read(fd, (char *)bytes.mutableBytes + offset, bytes.length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break; offset += count;
    }
    close(fd);
    id lease = offset == bytes.length ? [NSJSONSerialization JSONObjectWithData:bytes options:0 error:NULL] : nil;
    if (![lease isKindOfClass:NSDictionary.class] || [lease count] != 3 || !Integer(lease[@"version"], 1, 1) ||
        !Integer(lease[@"uid"], 1, UINT_MAX) || ![lease[@"session"] isEqual:session]) {
        Fail(error, @"Invalid TUN recovery lease; existing data preserved"); return nil;
    }
    return lease;
}
- (BOOL)copySigned:(NSString *)source session:(NSString *)session file:(NSString *)name
       requirement:(NSString *)requirement sourceUser:(uid_t)user error:(NSError **)error {
    if (![source isKindOfClass:NSString.class] || !source.isAbsolutePath || ![@[@"engine", @"worker"] containsObject:name] ||
        ![requirement isKindOfClass:NSString.class] || !requirement.length) return Fail(error, @"Invalid native payload specification");
    int input = open(source.fileSystemRepresentation, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    struct stat info;
    if (input < 0 || fstat(input, &info) || !S_ISREG(info.st_mode) || (info.st_uid != 0 && info.st_uid != user) ||
        (info.st_mode & 0022) || info.st_size < 1 || info.st_size > 200 * 1024 * 1024) {
        if (input >= 0) close(input); return Fail(error, @"Unsafe TUN payload source");
    }
    int directory = [self openSession:session];
    int output = directory >= 0 ? openat(directory, name.fileSystemRepresentation, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0500) : -1;
    BOOL created = output >= 0;
    BOOL ok = created;
    off_t total = 0;
    char buffer[65536];
    while (ok) {
        ssize_t count = read(input, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { ok = NO; break; } if (!count) break;
        total += count;
        if (total > info.st_size) { ok = NO; break; }
        for (ssize_t offset = 0; offset < count;) {
            ssize_t written = write(output, buffer + offset, count - offset);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) { ok = NO; break; } offset += written;
        }
    }
    close(input);
    if (ok) ok = total == info.st_size && !fchmod(output, 0500) && !fsync(output);
    if (output >= 0 && close(output)) ok = NO;
    // Verify the copied bytes inside protected staging, not a mutable caller path.
    if (ok) ok = PRTunSigned([self path:session file:name], requirement) && !fsync(directory);
    if (!ok && created) unlinkat(directory, name.fileSystemRepresentation, 0);
    if (directory >= 0) close(directory);
    return ok ? YES : Fail(error, @"TUN payload signature or staging verification failed");
}
- (BOOL)record:(const struct PRWorkerIdentity *)identity session:(NSString *)session worker:(BOOL)worker error:(NSError **)error {
    NSString *name = worker ? @"worker" : @"engine";
    NSDictionary *observed = identity ? Process(identity->pid, [self path:session file:name]) : nil;
    if (!observed || [observed[@"seconds"] unsignedLongLongValue] != identity->start_seconds ||
        [observed[@"microseconds"] unsignedLongLongValue] != identity->start_microseconds) return Fail(error, @"TUN process identity changed before recording");
    NSData *bytes = [NSJSONSerialization dataWithJSONObject:observed options:0 error:error];
    return bytes && [self write:bytes session:session file:[name stringByAppendingString:@".json"] error:error];
}
- (BOOL)stopRecorded:(NSString *)session name:(NSString *)name directory:(int)directory {
    NSString *file = [name stringByAppendingString:@".json"];
    int fd = openat(directory,file.fileSystemRepresentation,O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC);
    if (fd < 0) return errno == ENOENT; // Suspended spawn may precede the record commit.
    struct stat info;
    BOOL ok = !fstat(fd,&info) && S_ISREG(info.st_mode) && info.st_uid == geteuid() &&
        info.st_nlink == 1 && !(info.st_mode & 0077) && info.st_size > 0 && info.st_size <= 4096;
    NSMutableData *data = ok ? [NSMutableData dataWithLength:info.st_size] : nil;
    NSUInteger offset = 0;
    while (ok && offset < data.length) {
        ssize_t count = read(fd,(char *)data.mutableBytes+offset,data.length-offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { ok = NO; break; }
        offset += count;
    }
    close(fd);
    id identity = ok ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil;
    if (![identity isKindOfClass:NSDictionary.class] || [identity count] != 3 ||
        !Integer(identity[@"pid"],2,INT_MAX) || !Integer(identity[@"seconds"],1,9007199254740991ULL) ||
        !Integer(identity[@"microseconds"],0,999999)) return NO;
    if ([identity[@"pid"] intValue] == getpid()) return [Process(getpid(),[self path:session file:name]) isEqual:identity];
    return Stop(identity,[self path:session file:name]);
}
- (BOOL)cleanup:(NSString *)session error:(NSError **)error {
    int directory = [self openSession:session];
    if (directory < 0) return errno == ENOENT ? YES : Fail(error, @"Unsafe TUN cleanup session");
    NSArray<NSString *> *files = Files(directory);
    // Stop the worker first so it cannot spawn another engine during recovery. The
    // caller serializes session activation with cleanup; workers clean only after
    // reaping their own engine. Unique protected paths cover the pre-record crash gap.
    // Attempt every scoped shutdown even when a record is damaged. Damage keeps the
    // journal for inspection but must not prevent stopping another known staged child.
    BOOL workerRecord = [self stopRecorded:session name:@"worker" directory:directory];
    BOOL workerStopped = StopStaged([self path:session file:@"worker"]);
    BOOL engineRecord = [self stopRecorded:session name:@"engine" directory:directory];
    BOOL engineStopped = StopStaged([self path:session file:@"engine"]);
    BOOL ok = files && workerRecord && workerStopped && engineRecord && engineStopped && CaptureGone(directory);
    if (ok) {
        files = Files(directory);
        ok = files != nil;
        for (NSString *name in files) {
            if (unlinkat(directory, name.fileSystemRepresentation, 0) && errno != ENOENT) { ok = NO; break; }
        }
    }
    close(directory);
    if (ok) ok = (!unlinkat(_directory, session.fileSystemRepresentation, AT_REMOVEDIR) || errno == ENOENT) && !fsync(_directory);
    return ok ? YES : Fail(error, @"TUN cleanup is incomplete; recovery state preserved");
}
@end
