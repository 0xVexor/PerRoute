#import "TunStore.h"
#import "TunCapture.h"
#import <net/if.h>
#import <fcntl.h>
#import <sys/acl.h>
#import <membership.h>
#import <libproc.h>
#import <spawn.h>
#import <sys/proc_info.h>
#import <sys/stat.h>
#import <sys/wait.h>
#import <unistd.h>

static unsigned Checks;
static NSMutableDictionary<NSNumber *,NSString *> *Readiness;
static pid_t Children[32];
static unsigned ChildCount;
static void ReapFixtures(void) {
    for (unsigned i=0;i<ChildCount;i++) {
        if (waitpid(Children[i],NULL,WNOHANG) == 0) {
            kill(Children[i],SIGKILL); waitpid(Children[i],NULL,0);
        }
    }
}
#define CHECK(value) do { Checks++; if (!(value)) { fprintf(stderr,"Store check failed at line %d: %s\n",__LINE__,#value); exit(1); } } while(0)
static NSData *JSON(id value) { return [NSJSONSerialization dataWithJSONObject:value options:0 error:NULL]; }
static void Write(PRTunStore *store, NSString *session, id value) {
    CHECK([store write:JSON(value) session:session file:@"lease.json" error:NULL]);
}
static struct PRWorkerIdentity Identity(pid_t pid) {
    struct proc_bsdinfo info = {0};
    CHECK(proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) == sizeof(info));
    return (struct PRWorkerIdentity){pid,info.pbi_start_tvsec,info.pbi_start_tvusec};
}
static pid_t Spawn(NSString *path) {
    posix_spawnattr_t attributes;
    CHECK(!posix_spawnattr_init(&attributes));
    CHECK(!posix_spawnattr_setflags(&attributes, POSIX_SPAWN_START_SUSPENDED | POSIX_SPAWN_CLOEXEC_DEFAULT));
    NSString *ready = [@"/private/tmp/perroute-store-ready-" stringByAppendingString:NSUUID.UUID.UUIDString];
    char *args[] = {(char *)path.fileSystemRepresentation, (char *)ready.fileSystemRepresentation, NULL};
    char *environment[] = {"PATH=/usr/bin:/bin", NULL};
    pid_t pid = 0;
    CHECK(!posix_spawn(&pid, path.fileSystemRepresentation, NULL, &attributes, args, environment));
    posix_spawnattr_destroy(&attributes);
    CHECK(ChildCount < 32); Children[ChildCount++] = pid;
    Readiness[@(pid)] = ready;
    return pid;
}
static void Activate(pid_t pid) {
    CHECK(!kill(pid,SIGCONT));
    NSString *ready = Readiness[@(pid)];
    for (int i = 0; i < 200 && access(ready.fileSystemRepresentation,F_OK); i++) usleep(25000);
    CHECK([[NSData dataWithContentsOfFile:ready] isEqual:[@"R" dataUsingEncoding:NSUTF8StringEncoding]]);
    CHECK(!unlink(ready.fileSystemRepresentation));
}
int main(int argc, const char **argv) { @autoreleasepool {
    atexit(ReapFixtures);
    Readiness = [NSMutableDictionary dictionary];
    CHECK(argc == 2);
    int standardInput = fcntl(STDIN_FILENO,F_GETFD);
    if (geteuid() != 0) CHECK(![[PRTunStore alloc] initSystem:NULL]);
    CHECK(fcntl(STDIN_FILENO,F_GETFD) == standardInput);
    NSString *fixture = [NSString stringWithUTF8String:argv[1]];
    NSString *requirement = @"identifier \"app.perroute.store-fixture\"";
    CHECK(PRTunSigned(fixture, requirement));
    CHECK(!PRTunSigned(fixture, @"identifier \"wrong.identifier\""));
    char temporary[] = "/private/tmp/perroute-store-XXXXXX";
    CHECK(mkdtemp(temporary));
    int directory = open(temporary, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    CHECK(directory >= 0);
    PRTunStore *store = [[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL];
    CHECK(store);
    CHECK([store sessions:NULL].count == 0);
    CHECK(![store createForUser:0 error:NULL]);
    CHECK(![store path:@"../outside" file:@"engine"]);
    CHECK(![store path:NSUUID.UUID.UUIDString file:@"../outside"]);
    NSString *session = [store createForUser:501 error:NULL];
    CHECK(session);
    CHECK([[store sessions:NULL] isEqual:@[session]]);
    CHECK([[store lease:session error:NULL][@"uid"] isEqual:@501]);
    NSString *leasePath = [store path:session file:@"lease.json"];
    struct stat info;
    CHECK(!stat(leasePath.fileSystemRepresentation, &info));
    CHECK(info.st_uid == geteuid() && (info.st_mode & 0777) == 0600);
    for (id uid in @[@YES,@NO,@0,@(-1),@1.5,@4294967296ULL,@"501"]) {
        Write(store,session,@{@"version":@1,@"uid":uid,@"session":session});
        CHECK(![store lease:session error:NULL]);
    }
    Write(store,session,@{@"version":@YES,@"uid":@501,@"session":session});
    CHECK(![store lease:session error:NULL]);
    Write(store,session,@{@"version":@1,@"uid":@501,@"session":NSUUID.UUID.UUIDString});
    CHECK(![store lease:session error:NULL]);
    Write(store,session,@{@"version":@1,@"uid":@501,@"session":session,@"extra":@1});
    CHECK(![store lease:session error:NULL]);
    Write(store,session,@{@"version":@1,@"uid":@501,@"session":session});
    CHECK(![store write:[NSData data] session:session file:@"config.json" error:NULL]);
    CHECK(![store write:[NSMutableData dataWithLength:1048577] session:session file:@"config.json" error:NULL]);
    CHECK(![store write:JSON(@{}) session:session file:@"engine" error:NULL]);
    CHECK(![store copySigned:fixture session:session file:@"engine" requirement:@"identifier \"wrong\"" sourceUser:geteuid() error:NULL]);
    CHECK(access([store path:session file:@"engine"].fileSystemRepresentation,F_OK));
    NSString *sourceAlias = [[NSString stringWithUTF8String:temporary] stringByAppendingPathComponent:@"source-alias"];
    CHECK(!symlink(fixture.fileSystemRepresentation,sourceAlias.fileSystemRepresentation));
    CHECK(![store copySigned:sourceAlias session:session file:@"engine" requirement:requirement sourceUser:geteuid() error:NULL]);
    CHECK(!unlink(sourceAlias.fileSystemRepresentation));
    CHECK([store copySigned:fixture session:session file:@"engine" requirement:requirement sourceUser:geteuid() error:NULL]);
    CHECK(PRTunSigned([store path:session file:@"engine"],requirement));
    CHECK(![store copySigned:fixture session:session file:@"engine" requirement:requirement sourceUser:geteuid() error:NULL]);
    CHECK(PRTunSigned([store path:session file:@"engine"],requirement)); // O_EXCL failure must preserve an existing payload.
    CHECK([store copySigned:fixture session:session file:@"worker" requirement:requirement sourceUser:geteuid() error:NULL]);
    CHECK(!stat([store path:session file:@"worker"].fileSystemRepresentation,&info));
    CHECK((info.st_mode & 0777) == 0500 && info.st_uid == geteuid());
    CHECK(![store copySigned:@"relative" session:session file:@"worker" requirement:requirement sourceUser:geteuid() error:NULL]);
    NSString *unknown = [[leasePath stringByDeletingLastPathComponent] stringByAppendingPathComponent:@"unknown"];
    CHECK([JSON(@{}) writeToFile:unknown atomically:NO]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK(!access(leasePath.fileSystemRepresentation,F_OK));
    CHECK(!unlink(unknown.fileSystemRepresentation));
    NSString *alias = [[leasePath stringByDeletingLastPathComponent] stringByAppendingPathComponent:@"config.json"];
    CHECK(!symlink(leasePath.fileSystemRepresentation,alias.fileSystemRepresentation));
    CHECK(![store cleanup:session error:NULL]);
    CHECK(!unlink(alias.fileSystemRepresentation));
    CHECK(!link(leasePath.fileSystemRepresentation,alias.fileSystemRepresentation));
    CHECK(![store lease:session error:NULL]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK(!unlink(alias.fileSystemRepresentation));
    CHECK(!chmod(leasePath.fileSystemRepresentation,0644));
    CHECK(![store lease:session error:NULL]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK(!chmod(leasePath.fileSystemRepresentation,0600));
    pid_t unrelated = Spawn(fixture);
    pid_t worker = Spawn([store path:session file:@"worker"]);
    pid_t engine = Spawn([store path:session file:@"engine"]);
    struct PRWorkerIdentity workerIdentity = Identity(worker), engineIdentity = Identity(engine), wrong = engineIdentity;
    wrong.start_microseconds++;
    CHECK(![store record:&wrong session:session worker:NO error:NULL]);
    CHECK(![store record:&workerIdentity session:session worker:NO error:NULL]);
    CHECK([store record:&workerIdentity session:session worker:YES error:NULL]);
    CHECK([store record:&engineIdentity session:session worker:NO error:NULL]);
    Activate(worker); Activate(engine);
    NSError *cleanupError = nil;
    BOOL cleaned = [store cleanup:session error:&cleanupError];
    if (!cleaned) fprintf(stderr,"cleanup: %s\n",cleanupError.description.UTF8String);
    CHECK(cleaned);
    int status;
    pid_t waited = waitpid(worker,&status,0);
    CHECK(waited == worker && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    CHECK(waitpid(engine,&status,0) == engine && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    CHECK(!kill(unrelated,0)); CHECK(!kill(unrelated,SIGKILL)); CHECK(waitpid(unrelated,&status,0) == unrelated);
    CHECK([store sessions:NULL].count == 0);
    CHECK([store cleanup:session error:NULL]);
    // No PID record exists in this suspended-start crash gap. Unique protected paths still recover it.
    session = [store createForUser:501 error:NULL];
    CHECK([store copySigned:fixture session:session file:@"engine" requirement:requirement sourceUser:geteuid() error:NULL]);
    engine = Spawn([store path:session file:@"engine"]);
    CHECK([store cleanup:session error:NULL]);
    CHECK(waitpid(engine,&status,0) == engine && WIFSIGNALED(status));
    session = [store createForUser:501 error:NULL];
    CHECK([store copySigned:fixture session:session file:@"engine" requirement:requirement sourceUser:geteuid() error:NULL]);
    engine = Spawn([store path:session file:@"engine"]);
    CHECK([store write:JSON(@{@"pid":@YES,@"seconds":@1,@"microseconds":@0}) session:session file:@"engine.json" error:NULL]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK(waitpid(engine,&status,0) == engine && WIFSIGNALED(status));
    CHECK(!access([store path:session file:@"engine.json"].fileSystemRepresentation,F_OK));
    CHECK(!unlink([store path:session file:@"engine.json"].fileSystemRepresentation));
    CHECK([store cleanup:session error:NULL]);
    // A stored PID alone is never authority to signal an unrelated executable.
    session = [store createForUser:501 error:NULL];
    unrelated = Spawn(fixture);
    struct PRWorkerIdentity other = Identity(unrelated);
    CHECK([store write:JSON(@{@"pid":@(other.pid),@"seconds":@(other.start_seconds),@"microseconds":@(other.start_microseconds)})
        session:session file:@"engine.json" error:NULL]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK(!kill(unrelated,0));
    CHECK(!unlink([store path:session file:@"engine.json"].fileSystemRepresentation));
    CHECK([store cleanup:session error:NULL]);
    CHECK(!kill(unrelated,SIGKILL)); CHECK(waitpid(unrelated,&status,0) == unrelated);
    session = [store createForUser:501 error:NULL];
    CHECK([store write:JSON(@{@"version":@YES,@"interface":@PR_TUN_INTERFACE}) session:session file:@"capture.json" error:NULL]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK([store write:JSON(@{@"version":@1,@"interface":@"lo0"}) session:session file:@"capture.json" error:NULL]);
    CHECK(![store cleanup:session error:NULL]);
    CHECK([store write:JSON(@{@"version":@1,@"interface":@PR_TUN_INTERFACE}) session:session file:@"capture.json" error:NULL]);
    BOOL absent = if_nametoindex(PR_TUN_INTERFACE) == 0;
    CHECK([store cleanup:session error:NULL] == absent);
    if (!absent) { CHECK(!unlink([store path:session file:@"capture.json"].fileSystemRepresentation)); CHECK([store cleanup:session error:NULL]); }
    CHECK(!symlink("/private/tmp",[[NSString stringWithUTF8String:temporary] stringByAppendingPathComponent:NSUUID.UUID.UUIDString].fileSystemRepresentation));
    CHECK(![store sessions:NULL]);
    acl_t acl = acl_init(1);
    acl_entry_t entry;
    uuid_t qualifier;
    acl_permset_t permissions;
    CHECK(acl && !acl_create_entry(&acl,&entry));
    CHECK(!acl_set_tag_type(entry,ACL_EXTENDED_ALLOW));
    CHECK(!mbr_uid_to_uuid(geteuid(),qualifier));
    CHECK(!acl_set_qualifier(entry,qualifier));
    CHECK(!acl_get_permset(entry,&permissions));
    CHECK(!acl_add_perm(permissions,ACL_READ_DATA));
    CHECK(!acl_set_permset(entry,permissions));
    CHECK(!acl_set_fd_np(directory,acl,ACL_TYPE_EXTENDED));
    CHECK(![[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL]);
    acl_free(acl);
    acl = acl_init(0); CHECK(acl);
    CHECK(!acl_set_fd_np(directory,acl,ACL_TYPE_EXTENDED));
    acl_free(acl);
    CHECK([[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL]);
    CHECK(!fchmod(directory,0755));
    CHECK(![[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL]);
    CHECK([[NSFileManager defaultManager] removeItemAtPath:[NSString stringWithUTF8String:temporary] error:NULL]);
    close(directory);
    printf("TUN protected store: %u checks passed; disposable same-user signed fixtures only\n",Checks);
    return 0;
} }
