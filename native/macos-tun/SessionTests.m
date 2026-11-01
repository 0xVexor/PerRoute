#import "TunSession.h"
#import <fcntl.h>
#import <sys/stat.h>
#import <sys/wait.h>
#import <unistd.h>
static unsigned Checks;
static PRTunStore *EmergencyStore;
static void CleanupFixtures(void) { @autoreleasepool {
    for (NSString *session in [EmergencyStore sessions:NULL]) [EmergencyStore cleanup:session error:NULL];
    while (waitpid(-1,NULL,WNOHANG) > 0) {}
} }
#define CHECK(value) do { Checks++; if (!(value)) { fprintf(stderr,"Session check failed at line %d: %s\n",__LINE__,#value); exit(1); } } while(0)
@interface RefusingWorkerStore : PRTunStore
@end
@implementation RefusingWorkerStore
- (BOOL)record:(const struct PRWorkerIdentity *)identity session:(NSString *)session worker:(BOOL)worker error:(NSError **)error {
    if (worker) { if (error) *error = PRTunError(@"Injected durable worker-record refusal"); return NO; }
    return [super record:identity session:session worker:worker error:error];
}
@end
static PRTunSession *Start(PRTunStore *store, NSString *engine, NSString *worker, char mode) {
    NSError *error = nil;
    PRTunSession *session = [[PRTunSession alloc] initWithStore:store user:geteuid() engine:engine worker:worker
        configuration:[NSData dataWithBytes:&mode length:1] engineRequirement:@"identifier \"app.perroute.session-engine\""
        workerRequirement:@"identifier \"app.perroute.session-worker\"" error:&error];
    if (!session) fprintf(stderr,"start error: %s\n",error.description.UTF8String);
    CHECK(session);
    CHECK(session.spawned);
    CHECK(session.workerPID > 0);
    CHECK([store lease:session.identifier error:NULL]);
    for (NSString *name in @[@"worker.json",@"engine.json"]) {
        id identity = [NSJSONSerialization JSONObjectWithData:[NSData dataWithContentsOfFile:[store path:session.identifier file:name]] options:0 error:NULL];
        CHECK([identity[@"pid"] intValue] > 0 && [identity[@"seconds"] unsignedLongLongValue] > 0);
    }
    NSString *path = [store path:session.identifier file:@"config.json"];
    for (int i=0;i<160 && [NSData dataWithContentsOfFile:path].length != 2;i++) usleep(25000);
    CHECK([NSData dataWithContentsOfFile:path].length == 2);
    return session;
}
int main(int argc, const char **argv) { @autoreleasepool {
    CHECK(argc == 3 && geteuid() != 0);
    NSString *engine = [NSString stringWithUTF8String:argv[1]], *worker = [NSString stringWithUTF8String:argv[2]];
    char root[] = "/private/tmp/perroute-session-XXXXXX";
    CHECK(mkdtemp(root));
    int directory = open(root,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    PRTunStore *store = [[PRTunStore alloc] initWithDirectoryDescriptor:directory error:NULL];
    CHECK(store);
    EmergencyStore = store; atexit(CleanupFixtures);
    PRTunStore *refusing = [[RefusingWorkerStore alloc] initWithDirectoryDescriptor:directory error:NULL];
    close(directory); CHECK(refusing);
    NSError *refused = nil;
    PRTunSession *refusedSession = [[PRTunSession alloc] initWithStore:refusing user:geteuid() engine:engine worker:worker
        configuration:[@"N" dataUsingEncoding:NSUTF8StringEncoding] engineRequirement:@"identifier \"app.perroute.session-engine\""
        workerRequirement:@"identifier \"app.perroute.session-worker\"" error:&refused];
    CHECK(!refusedSession && refused);
    CHECK([store sessions:NULL].count == 0);
    CHECK(waitpid(-1,NULL,WNOHANG) == -1 && errno == ECHILD);
    PRTunSession *session = Start(store,engine,worker,'N');
    CHECK([session poll:NULL]);
    CHECK([session stop:NULL]);
    CHECK(!session.spawned && session.workerPID == 0);
    CHECK([store sessions:NULL].count == 0);
    CHECK([session stop:NULL]);
    CHECK(![session poll:NULL]);
    session = Start(store,engine,worker,'I');
    CHECK([session stop:NULL]);
    CHECK([store sessions:NULL].count == 0);
    session = Start(store,engine,worker,'N');
    CHECK(!kill(session.workerPID,SIGKILL));
    usleep(100000);
    CHECK(![session poll:NULL]);
    CHECK([session stop:NULL]);
    CHECK([store sessions:NULL].count == 0);
    session = Start(store,engine,worker,'I');
    NSString *identifier = session.identifier;
    session = nil; // Owner EOF must independently stop/reap the engine and remove staging.
    for (int i=0;i<160 && [store sessions:NULL].count;i++) usleep(25000);
    CHECK([store sessions:NULL].count == 0);
    CHECK([store cleanup:identifier error:NULL]);
    NSError *error = nil;
    session = [[PRTunSession alloc] initWithStore:store user:geteuid() engine:engine worker:worker
        configuration:[@"N" dataUsingEncoding:NSUTF8StringEncoding] engineRequirement:@"identifier \"wrong\""
        workerRequirement:@"identifier \"app.perroute.session-worker\"" error:&error];
    CHECK(!session && error);
    CHECK([store sessions:NULL].count == 0);
    CHECK(!rmdir(root));
    printf("TUN protected sessions: %u checks passed; disposable same-user processes, no capture\n",Checks);
    return 0;
} }
