#import "TunSession.h"
#import <fcntl.h>
#import <libproc.h>
#import <unistd.h>
int main(int argc, const char **argv) { @autoreleasepool {
    if (geteuid() == 0 || argc != 3 || strcmp(argv[1],"--tun-worker")) return 78;
    char executable[PROC_PIDPATHINFO_MAXSIZE];
    if (proc_pidpath(getpid(),executable,sizeof(executable)) <= 0) return 78;
    NSString *root = [[[NSString stringWithUTF8String:executable] stringByDeletingLastPathComponent] stringByDeletingLastPathComponent];
    int directory = open(root.fileSystemRepresentation,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    PRTunStore *store = [[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL];
    if (directory >= 0) close(directory);
    return PRRunStagedTunWorker(store,[NSString stringWithUTF8String:argv[2]],3);
} }
