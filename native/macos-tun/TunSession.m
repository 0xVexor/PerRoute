#import "TunSession.h"
#import <fcntl.h>
#import <libproc.h>
#import <poll.h>
#import <signal.h>
#import <spawn.h>
#import <sys/proc_info.h>
#import <sys/socket.h>
#import <sys/wait.h>
#import <unistd.h>
#import <time.h>

static double Now(void) { struct timespec time; clock_gettime(CLOCK_MONOTONIC,&time); return time.tv_sec + time.tv_nsec / 1e9; }
static BOOL Failed(NSError **error, NSString *message) { if (error) *error = PRTunError(message); return NO; }
static int StartWorker(NSString *path, NSString *session, int socket, pid_t *pid) {
    posix_spawnattr_t attributes;
    posix_spawn_file_actions_t actions;
    int code = posix_spawnattr_init(&attributes);
    if (code) return code;
    code = posix_spawn_file_actions_init(&actions);
    if (code) { posix_spawnattr_destroy(&attributes); return code; }
    int opened = open("/dev/null",O_RDWR|O_CLOEXEC);
    int null = opened >= 0 ? fcntl(opened,F_DUPFD_CLOEXEC,4) : -1;
    int descriptorError = errno;
    if (opened >= 0) close(opened);
    if (null < 0) code = descriptorError;
    if (!code) code = posix_spawn_file_actions_adddup2(&actions,socket,3);
    for (int fd = 0; fd < 3 && !code; fd++) code = posix_spawn_file_actions_adddup2(&actions,null,fd);
    sigset_t mask, defaults;
    sigemptyset(&mask); sigemptyset(&defaults);
    sigaddset(&defaults,SIGTERM); sigaddset(&defaults,SIGINT); sigaddset(&defaults,SIGPIPE);
    if (!code) code = posix_spawnattr_setsigmask(&attributes,&mask);
    if (!code) code = posix_spawnattr_setsigdefault(&attributes,&defaults);
    if (!code) code = posix_spawnattr_setpgroup(&attributes,0);
    if (!code) code = posix_spawnattr_setflags(&attributes,POSIX_SPAWN_START_SUSPENDED|POSIX_SPAWN_CLOEXEC_DEFAULT|
        POSIX_SPAWN_SETPGROUP|POSIX_SPAWN_SETSIGMASK|POSIX_SPAWN_SETSIGDEF);
    char *args[] = {(char *)path.fileSystemRepresentation,"--tun-worker",(char *)session.UTF8String,NULL};
    char *environment[] = {"PATH=/usr/bin:/bin","HOME=/var/empty","TMPDIR=/private/tmp",NULL};
    if (!code) code = posix_spawn(pid,path.fileSystemRepresentation,&actions,&attributes,args,environment);
    if (null >= 0) close(null);
    posix_spawn_file_actions_destroy(&actions); posix_spawnattr_destroy(&attributes);
    return code;
}

@implementation PRTunSession {
    PRTunStore *_store;
    int _control;
    unsigned char _event[sizeof(struct PRWorkerEvent)];
    size_t _received;
    BOOL _stopped;
}
- (instancetype)initWithStore:(PRTunStore *)store user:(uid_t)uid
    engine:(NSString *)engine worker:(NSString *)worker configuration:(NSData *)configuration
    engineRequirement:(NSString *)engineRequirement workerRequirement:(NSString *)workerRequirement error:(NSError **)error {
    return [self initWithStore:store user:uid engine:engine worker:worker
        configurationBuilder:^NSData *(NSString *stagedEngine, NSError **builderError) {
            (void)stagedEngine; (void)builderError; return configuration;
        } engineRequirement:engineRequirement workerRequirement:workerRequirement error:error];
}
- (instancetype)initWithStore:(PRTunStore *)store user:(uid_t)uid
    engine:(NSString *)engine worker:(NSString *)worker configurationBuilder:(PRTunConfigurationBuilder)builder
    engineRequirement:(NSString *)engineRequirement workerRequirement:(NSString *)workerRequirement error:(NSError **)error {
    self = [super init]; if (!self) return nil;
    _control = -1; _store = store;
    _identifier = [store createForUser:uid error:error];
    if (!_identifier) return nil;
    NSData *configuration = builder ? builder([store path:_identifier file:@"engine"],error) : nil;
    if (!configuration || ![store write:configuration session:_identifier file:@"config.json" error:error] ||
        ![store copySigned:engine session:_identifier file:@"engine" requirement:engineRequirement sourceUser:uid error:error] ||
        ![store copySigned:worker session:_identifier file:@"worker" requirement:workerRequirement sourceUser:uid error:error]) {
        [self stop:NULL]; return nil;
    }
    int sockets[2];
    if (socketpair(AF_UNIX,SOCK_STREAM,0,sockets)) { Failed(error,@"Cannot create private TUN worker control"); [self stop:NULL]; return nil; }
    fcntl(sockets[0],F_SETFD,FD_CLOEXEC); fcntl(sockets[1],F_SETFD,FD_CLOEXEC);
    _control = sockets[0];
    pid_t workerPID = 0;
    int code = StartWorker([store path:_identifier file:@"worker"],_identifier,sockets[1],&workerPID);
    close(sockets[1]);
    if (code) { Failed(error,@"Cannot start isolated TUN worker"); [self stop:NULL]; return nil; }
    _workerPID = workerPID;
    struct proc_bsdinfo info = {0};
    BOOL observed = proc_pidinfo(workerPID,PROC_PIDTBSDINFO,0,&info,sizeof(info)) == sizeof(info) && info.pbi_uid == geteuid();
    struct PRWorkerIdentity identity = {workerPID,info.pbi_start_tvsec,info.pbi_start_tvusec};
    if (!observed || ![store record:&identity session:_identifier worker:YES error:error] || kill(workerPID,SIGCONT)) {
        if (error && !*error) Failed(error,@"TUN worker activation refused");
        [self stop:NULL]; return nil;
    }
    if (fcntl(_control,F_SETFL,fcntl(_control,F_GETFL)|O_NONBLOCK)) { Failed(error,@"Cannot observe TUN worker"); [self stop:NULL]; return nil; }
    double deadline = Now() + 5;
    while (!_spawned && Now() < deadline) {
        if (![self poll:error]) { [self stop:NULL]; return nil; }
        if (!_spawned) usleep(25000);
    }
    if (!_spawned) { Failed(error,@"TUN engine startup timed out"); [self stop:NULL]; return nil; }
    return self;
}
- (void)dealloc {
    if (_control >= 0) close(_control); // EOF makes the isolated worker stop its owned child.
    pid_t child = _workerPID;
    if (child > 0) {
        PRTunStore *store = _store;
        NSString *session = _identifier;
        // The helper must reap its worker even when its owning session is released.
        // Recovery on a different queue is safe only after control ownership is closed.
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY,0), ^{
            double deadline = Now()+3;
            while (Now() < deadline) {
                pid_t waited = waitpid(child,NULL,WNOHANG);
                if (waited == child || (waited < 0 && errno == ECHILD)) return;
                usleep(25000);
            }
            if ([store cleanup:session error:NULL]) {
                for (int i = 0; i < 80; i++) {
                    pid_t waited = waitpid(child,NULL,WNOHANG);
                    if (waited == child || (waited < 0 && errno == ECHILD)) return;
                    usleep(25000);
                }
            }
            NSLog(@"PerRoute TUN worker recovery remains pending");
        });
    }
}
- (BOOL)poll:(NSError **)error {
    if (_stopped || _control < 0) return Failed(error,@"TUN session is stopped");
    for (;;) {
        ssize_t count = recv(_control,_event+_received,sizeof(_event)-_received,MSG_DONTWAIT);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return YES;
        if (count <= 0) return Failed(error,@"TUN worker control was lost; recovery required");
        _received += count;
        if (_received != sizeof(_event)) continue;
        struct PRWorkerEvent event; memcpy(&event,_event,sizeof(event)); _received = 0;
        if (event.version != 1 || event.state != PR_WORKER_RUNNING || event.pid <= 0 || event.reason || _spawned)
            return Failed(error,@"TUN engine stopped or sent an invalid lifecycle event; recovery required");
        _spawned = YES; // This is process startup, never a forwarding/readiness claim.
    }
}
- (BOOL)stop:(NSError **)error {
    _spawned = NO;
    if (_control >= 0) { close(_control); _control = -1; }
    // Give ordinary owner-loss shutdown a chance to reap before independent recovery.
    double deadline = Now()+2.5;
    while (_workerPID > 0 && Now() < deadline) {
        pid_t waited = waitpid(_workerPID,NULL,WNOHANG);
        if (waited == _workerPID || (waited < 0 && errno == ECHILD)) { _workerPID = 0; break; }
        if (waited < 0 && errno != EINTR) break;
        usleep(25000);
    }
    if (_workerPID > 0) {
        // A failed pre-activation path/record check may leave our worker suspended.
        // waitpid==0 proves it remains our unreaped child, without relying on a
        // path lookup or a PID that could already have been reused.
        pid_t waited = waitpid(_workerPID,NULL,WNOHANG);
        if (!waited) {
            if (kill(_workerPID,SIGKILL) && errno != ESRCH) return Failed(error,@"Cannot terminate owned TUN worker");
            for (int i = 0; i < 80; i++) {
                waited = waitpid(_workerPID,NULL,WNOHANG);
                if (waited != 0 && !(waited < 0 && errno == EINTR)) break;
                usleep(25000);
            }
        }
        if (waited == _workerPID || (waited < 0 && errno == ECHILD)) _workerPID = 0;
    }
    if (_identifier && ![_store cleanup:_identifier error:error]) return NO;
    if (_workerPID > 0) {
        pid_t waited = waitpid(_workerPID,NULL,WNOHANG);
        if (waited != _workerPID && !(waited < 0 && errno == ECHILD)) return Failed(error,@"TUN worker reaping remains pending");
        _workerPID = 0;
    }
    _stopped = YES; return YES;
}
@end

struct RecordContext { __unsafe_unretained PRTunStore *store; __unsafe_unretained NSString *session; };
static int RecordEngine(const struct PRWorkerIdentity *identity, void *context) {
    struct RecordContext *record = context;
    return [record->store record:identity session:record->session worker:NO error:NULL] ? 0 : -1;
}
int PRRunStagedTunWorker(PRTunStore *store, NSString *session, int control) {
    char executable[PROC_PIDPATHINFO_MAXSIZE];
    if (!store || ![store lease:session error:NULL] || proc_pidpath(getpid(),executable,sizeof(executable)) <= 0 ||
        ![[store path:session file:@"worker"] isEqual:[NSString stringWithUTF8String:executable]]) return 78;
    struct RecordContext context = {store,session};
    int result = PRRunEngineWorker([store path:session file:@"engine"].fileSystemRepresentation,
        [store path:session file:@"config.json"].fileSystemRepresentation,control,RecordEngine,&context);
    close(control);
    return [store cleanup:session error:NULL] ? result : 80;
}
