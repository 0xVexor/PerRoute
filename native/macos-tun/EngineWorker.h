#ifndef PERROUTE_ENGINE_WORKER_H
#define PERROUTE_ENGINE_WORKER_H
#include <sys/types.h>
#include <stdint.h>

// Run only in an isolated worker process, never on the helper XPC queue.
// Internal native API, never an IPC request. The caller must authenticate the session
// and verify/stage the fixed engine and config before entering its isolated worker.
// The calling worker owns and closes control after this function returns. EOF, STOP
// or malformed input stops the child; the engine must never inherit this socket.
// A RUNNING event proves spawn only, not interface readiness or successful forwarding.
enum PRWorkerState { PR_WORKER_RUNNING = 1, PR_WORKER_STOPPED = 2 };
enum PRWorkerReason { PR_WORKER_STOP = 1, PR_WORKER_OWNER_LOST = 2,
    PR_WORKER_ENGINE_EXIT = 3, PR_WORKER_BAD_CONTROL = 4, PR_WORKER_FAILURE = 5 };
struct PRWorkerEvent { int version; int state; int reason; pid_t pid; int wait_status; };
struct PRWorkerIdentity { pid_t pid; uint64_t start_seconds; uint64_t start_microseconds; };
// The engine stays suspended until record returns zero. Production must durably persist
// this kernel-observed identity; callback failure must never permit traffic capture.
typedef int (*PRWorkerRecord)(const struct PRWorkerIdentity *identity, void *context);
int PRRunEngineWorker(const char *engine, const char *configuration, int control, PRWorkerRecord record, void *context);
#endif
