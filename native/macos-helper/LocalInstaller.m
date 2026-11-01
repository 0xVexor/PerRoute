#import "ProxyHelper.h"
#import "../macos-tun/TunStore.h"
#import <Security/Security.h>
#import <sys/stat.h>
#import <fcntl.h>
#import <unistd.h>
#import <errno.h>

static NSString *const Binary = @"/Library/PrivilegedHelperTools/app.perroute.proxy-helper";
static NSString *const Plist = @"/Library/LaunchDaemons/app.perroute.proxy-helper.plist";

static BOOL Directory(NSString *path) {
    struct stat info;
    return lstat(path.fileSystemRepresentation, &info) == 0 && S_ISDIR(info.st_mode) && info.st_uid == 0 && !(info.st_mode & 0022);
}
static BOOL TunRecoveryClear(void) {
    int directory = open("/Library/Application Support/PerRoute/TunHelper", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return errno == ENOENT;
    PRTunStore *store = [[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL];
    close(directory);
    NSArray *sessions = [store sessions:NULL];
    return sessions && sessions.count == 0;
}
static BOOL Signed(NSString *path) {
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    BOOL ok = SecStaticCodeCreateWithPath((__bridge CFURLRef)[NSURL fileURLWithPath:path], kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)PRRequirement(PRHelperLabel, @"local"), kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate | kSecCSCheckAllArchitectures, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return ok;
}
static NSData *ReadBinary(NSString *path) {
    int fd = open(path.fileSystemRepresentation, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return nil;
    struct stat info;
    NSMutableData *data = nil;
    if (fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && info.st_size > 0 && info.st_size <= 4194304) {
        data = [NSMutableData dataWithLength:(NSUInteger)info.st_size];
        NSUInteger offset = 0;
        while (offset < data.length) {
            ssize_t count = read(fd, (char *)data.mutableBytes + offset, data.length - offset);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) break;
            offset += (NSUInteger)count;
        }
        if (offset != data.length) data = nil;
    }
    close(fd);
    return data;
}
static BOOL Write(NSString *path, NSData *data, mode_t mode) {
    int fd = open(path.fileSystemRepresentation, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode);
    if (fd < 0) return NO;
    NSUInteger offset = 0;
    while (offset < data.length) {
        ssize_t count = write(fd, (const char *)data.bytes + offset, data.length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += (NSUInteger)count;
    }
    BOOL ok = offset == data.length && fchmod(fd, mode) == 0 && fsync(fd) == 0;
    if (close(fd) != 0) ok = NO;
    return ok;
}
static int Launchctl(NSArray *arguments) {
    NSTask *task = [NSTask new];
    task.executableURL = [NSURL fileURLWithPath:@"/bin/launchctl"];
    task.arguments = arguments;
    NSError *error = nil;
    if (![task launchAndReturnError:&error]) return -1;
    [task waitUntilExit];
    return task.terminationStatus;
}
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        // The administrator approves this fixed-purpose installer, never a caller-provided script,
        // source path, command, destination, certificate or launchd configuration.
        BOOL remove = argc == 2 && strcmp(argv[1], "--uninstall") == 0;
        if ((argc != 1 && !remove) || geteuid() != 0 || !PRSignedSelf(@"app.perroute.proxy-installer")) return 78;
        NSString *selfPath = [[NSString stringWithUTF8String:argv[0]] stringByResolvingSymlinksInPath];
        NSString *contents = [[selfPath stringByDeletingLastPathComponent] stringByDeletingLastPathComponent];
        NSString *source = [contents stringByAppendingPathComponent:@"Library/HelperTools/PerRouteProxyHelper"];
        struct stat info;
        if (lstat(source.fileSystemRepresentation, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0 || info.st_size > 4194304 || !Signed(source)) return 78;
        for (NSString *directory in @[@"/Library", @"/Library/PrivilegedHelperTools", @"/Library/LaunchDaemons"]) {
            if (mkdir(directory.fileSystemRepresentation, 0755) != 0 && errno != EEXIST) return 78;
            if (!Directory(directory)) return 78;
        }
        if (remove) {
            for (NSString *path in @[Binary, Plist]) {
                if (lstat(path.fileSystemRepresentation, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != 0 || (info.st_mode & 0022)) return 78;
            }
            if (!Signed(Binary)) return 78;
            NSString *lease = @"/Library/Application Support/PerRoute/ProxyHelper/lease.plist";
            if (lstat(lease.fileSystemRepresentation, &info) == 0 || errno != ENOENT || !TunRecoveryClear()) return 78;
            if (Launchctl(@[@"bootout", @"system", Plist]) != 0) return 78;
            // The normal caller holds the authenticated retirement barrier. Also protect direct
            // administrator invocation racing an apply: keep artifacts and restart recovery.
            if (lstat(lease.fileSystemRepresentation, &info) == 0 || errno != ENOENT || !TunRecoveryClear()) {
                Launchctl(@[@"bootstrap", @"system", Plist]); return 78;
            }
            if (unlink(Plist.fileSystemRepresentation) != 0 || unlink(Binary.fileSystemRepresentation) != 0) return 78;
            puts("PerRoute helper removed; recovery directory retained"); return 0;
        }
        // First installation only. Replacing or removing an existing service needs a separately
        // authenticated recovery/retirement transaction; never overwrite live helper artifacts.
        for (NSString *path in @[Binary, Plist, @"/Library/Application Support/PerRoute/ProxyHelper/lease.plist"]) {
            if (lstat(path.fileSystemRepresentation, &info) == 0 || errno != ENOENT) {
                fprintf(stderr, "Existing helper or recovery state requires explicit maintenance; installation refused\n"); return 78;
            }
        }
        if (!TunRecoveryClear()) return 78;
        char staging[] = "/Library/PrivilegedHelperTools/.perroute-install-XXXXXX";
        if (!mkdtemp(staging)) return 78;
        NSString *directory = [NSString stringWithUTF8String:staging];
        NSString *binary = [directory stringByAppendingPathComponent:@"helper"];
        NSString *plist = [directory stringByAppendingPathComponent:@"service.plist"];
        BOOL binaryLinked = NO, plistLinked = NO, success = NO;
        @try {
            NSData *bytes = ReadBinary(source);
            if (!bytes || bytes.length > 4194304 || !Write(binary, bytes, 0755) || !Signed(binary)) return 78;
            NSDictionary *job = @{@"Label": PRHelperLabel, @"ProgramArguments": @[Binary],
                @"MachServices": @{PRHelperLabel: @YES}, @"RunAtLoad": @YES, @"ProcessType": @"Interactive"};
            NSData *data = [NSPropertyListSerialization dataWithPropertyList:job format:NSPropertyListXMLFormat_v1_0 options:0 error:NULL];
            if (!data || !Write(plist, data, 0644)) return 78;
            // link is exclusive and cannot follow/overwrite an existing destination symlink.
            binaryLinked = link(binary.fileSystemRepresentation, Binary.fileSystemRepresentation) == 0;
            if (!binaryLinked) return 78;
            plistLinked = link(plist.fileSystemRepresentation, Plist.fileSystemRepresentation) == 0;
            if (!plistLinked) return 78;
            if (Launchctl(@[@"bootstrap", @"system", Plist]) != 0) return 78;
            success = YES;
            puts("PerRoute helper installed; no proxy settings requested");
        } @finally {
            if (!success) {
                if (plistLinked) unlink(Plist.fileSystemRepresentation);
                if (binaryLinked) unlink(Binary.fileSystemRepresentation);
            }
            unlink(binary.fileSystemRepresentation); unlink(plist.fileSystemRepresentation); rmdir(staging);
        }
        return success ? 0 : 78;
    }
}
