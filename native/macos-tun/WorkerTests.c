#include "EngineWorker.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int checks;
static void Check(int passed, const char *message) {
    checks++;
    if (!passed) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}
static struct PRWorkerEvent Receive(int fd) {
    struct PRWorkerEvent event = {0};
    size_t received = 0;
    struct timespec start, now;
    Check(!clock_gettime(CLOCK_MONOTONIC, &start), "event deadline clock");
    while (received < sizeof(event)) {
        Check(!clock_gettime(CLOCK_MONOTONIC, &now) && now.tv_sec - start.tv_sec < 4, "bounded event framing");
        struct pollfd observed = {.fd = fd, .events = POLLIN};
        int ready = poll(&observed, 1, 100);
        Check(ready >= 0, "worker event poll");
        if (!ready) continue;
        // Intentionally use partial reads to verify the stream-event contract.
        size_t remaining = sizeof(event) - received;
        ssize_t count = recv(fd, (char *)&event + received, remaining > 3 ? 3 : remaining, 0);
        Check(count > 0, "partial worker event bytes");
        received += (size_t)count;
    }
    Check(event.version == 1, "typed worker event");
    return event;
}
static void Wait(pid_t worker, int expected) {
    int status;
    struct timespec pause = {.tv_sec = 0, .tv_nsec = 25000000};
    for (int attempt = 0; attempt < 160; attempt++) {
        if (waitpid(worker, &status, WNOHANG) == worker) {
            Check(WIFEXITED(status) && WEXITSTATUS(status) == expected, "worker exited with expected outcome"); return;
        }
        nanosleep(&pause, NULL);
    }
    kill(worker, SIGKILL);
    waitpid(worker, &status, 0);
    Check(0, "worker deadline exceeded");
}
struct RecordContext { const char *configuration; int refuse; int delay; };
static int Record(const struct PRWorkerIdentity *identity, void *opaque) {
    struct RecordContext *context = opaque;
    int config = open(context->configuration, O_RDONLY);
    char bytes[2];
    ssize_t count = config >= 0 ? read(config, bytes, sizeof(bytes)) : -1;
    if (config >= 0) close(config);
    if (count != 1 || !identity->start_seconds || identity->pid <= 0) return 1;
    if (context->delay) usleep(100000);
    if (context->refuse) return 1;
    char path[300];
    snprintf(path, sizeof(path), "%s.owner", context->configuration);
    int file = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (file < 0) return 1;
    int failed = write(file, identity, sizeof(*identity)) != sizeof(*identity) || fsync(file);
    if (close(file)) failed = 1;
    return failed;
}
static void Lifecycle(const char *engine, const char *config, char mode, int action) {
    int file = open(config, O_WRONLY | O_TRUNC);
    Check(file >= 0 && write(file, &mode, 1) == 1 && !close(file), "owned configuration");
    int pair[2];
    Check(!socketpair(AF_UNIX, SOCK_STREAM, 0, pair), "private control pair");
    pid_t worker = fork();
    Check(worker >= 0, "worker fork");
    if (!worker) { close(pair[0]); struct RecordContext context = {.configuration = config}; int result = PRRunEngineWorker(engine, config, pair[1], Record, &context); close(pair[1]); _exit(result); }
    close(pair[1]);
    struct PRWorkerEvent event = Receive(pair[0]);
    Check(event.state == PR_WORKER_RUNNING && event.pid > 0, "spawn event, not readiness");
    pid_t engine_pid = event.pid;
    char record_path[300];
    snprintf(record_path, sizeof(record_path), "%s.owner", config);
    int record = open(record_path, O_RDONLY);
    struct PRWorkerIdentity identity = {0};
    Check(record >= 0 && read(record, &identity, sizeof(identity)) == sizeof(identity) && !close(record) &&
        identity.pid == engine_pid && identity.start_seconds > 0, "kernel child identity recorded before activation");
    if (mode != 'E') {
        int ready = 0;
        struct timespec pause = {.tv_sec = 0, .tv_nsec = 25000000};
        for (int attempt = 0; attempt < 80 && !ready; attempt++) {
            int fd = open(config, O_RDONLY);
            char bytes[2] = {0};
            if (fd >= 0) { ready = read(fd, bytes, sizeof(bytes)) == 2 && bytes[1] == 'R'; close(fd); }
            if (!ready) nanosleep(&pause, NULL);
        }
        Check(ready, "engine initialized signal policy, isolated FDs and environment");
    }
    if (action == 0) { close(pair[0]); Wait(worker, 0); }
    else {
        if (action == 1 || action == 2) {
            const char *command = action == 1 ? "S" : "X";
            Check(send(pair[0], command, 1, 0) == 1, "control command sent");
        }
        if (action == 4) Check(!kill(engine_pid, SIGKILL), "engine crash induced");
        event = Receive(pair[0]);
        int expected_reason = action == 1 ? PR_WORKER_STOP : action == 2 ? PR_WORKER_BAD_CONTROL : PR_WORKER_ENGINE_EXIT;
        Check(event.state == PR_WORKER_STOPPED && event.reason == expected_reason, "precise stop reason");
        if (mode == 'I') Check(WIFSIGNALED(event.wait_status) && WTERMSIG(event.wait_status) == SIGKILL, "TERM-ignoring child required KILL");
        if (mode == 'E') Check(WIFEXITED(event.wait_status) && WEXITSTATUS(event.wait_status) == 17, "early exit status retained");
        close(pair[0]); Wait(worker, action == 1 ? 0 : 79);
    }
    errno = 0;
    Check(kill(engine_pid, 0) < 0 && errno == ESRCH, "owned engine no longer exists");
}
static void StartupGate(const char *engine, const char *config, int refuse) {
    int file = open(config, O_WRONLY | O_TRUNC);
    Check(file >= 0 && write(file, "N", 1) == 1 && !close(file), "startup gate config");
    int pair[2];
    Check(!socketpair(AF_UNIX, SOCK_STREAM, 0, pair), "startup owner socket");
    pid_t worker = fork();
    Check(worker >= 0, "startup worker fork");
    if (!worker) {
        close(pair[0]);
        struct RecordContext context = {.configuration = config, .refuse = refuse, .delay = !refuse};
        int result = PRRunEngineWorker(engine, config, pair[1], Record, &context);
        close(pair[1]); _exit(result);
    }
    close(pair[1]);
    if (refuse) {
        struct PRWorkerEvent event = Receive(pair[0]);
        Check(event.state == PR_WORKER_STOPPED && event.reason == PR_WORKER_FAILURE, "record failure prevents RUNNING");
        close(pair[0]); Wait(worker, 79);
        errno = 0;
        Check(kill(event.pid, 0) < 0 && errno == ESRCH, "unrecorded suspended engine reaped");
    } else { close(pair[0]); Wait(worker, 0); }
    file = open(config, O_RDONLY);
    char bytes[2];
    Check(file >= 0 && read(file, bytes, sizeof(bytes)) == 1 && !close(file), "no engine activation before recovery/owner gate");
}
int main(int argc, char **argv) {
    Check(argc == 2 && argv[1][0] == '/', "fixed fixture executable argument");
    Check(!setenv("PERROUTE_WORKER_SECRET", "must-not-reach-child", 1), "parent environment sentinel");
    char directory[] = "/private/tmp/perroute-worker-tests.XXXXXX";
    Check(mkdtemp(directory) != NULL, "owned staging directory");
    char config[256];
    snprintf(config, sizeof(config), "%s/config", directory);
    int file = open(config, O_WRONLY | O_CREAT | O_EXCL, 0600);
    Check(file >= 0 && write(file, "N", 1) == 1 && !close(file), "bounded regular config fixture");
    Lifecycle(argv[1], config, 'N', 1);
    Lifecycle(argv[1], config, 'I', 1);
    Lifecycle(argv[1], config, 'N', 0);
    Lifecycle(argv[1], config, 'I', 0);
    Lifecycle(argv[1], config, 'N', 2);
    Lifecycle(argv[1], config, 'E', 3);
    Lifecycle(argv[1], config, 'N', 4);
    StartupGate(argv[1], config, 1);
    StartupGate(argv[1], config, 0);
    int pair[2];
    Check(!socketpair(AF_UNIX, SOCK_STREAM, 0, pair), "refusal fixture socket");
    Check(PRRunEngineWorker(argv[1], config, pair[1], NULL, NULL) == 78, "missing recovery recorder refused");
    Check(PRRunEngineWorker("relative", config, pair[1], Record, NULL) == 78, "relative engine refused");
    Check(PRRunEngineWorker(argv[1], "relative", pair[1], Record, NULL) == 78, "relative config refused");
    Check(PRRunEngineWorker(argv[1], config, 0, Record, NULL) == 78, "standard input not an owner control socket");
    Check(!chmod(config, 0666), "unsafe config fixture permissions");
    Check(PRRunEngineWorker(argv[1], config, pair[1], Record, NULL) == 78, "writable config refused");
    Check(!chmod(config, 0600), "safe config permissions restored");
    char link[256];
    snprintf(link, sizeof(link), "%s/link", directory);
    Check(!symlink(config, link), "symlink config fixture");
    Check(PRRunEngineWorker(argv[1], link, pair[1], Record, NULL) == 78, "symlink config refused");
    Check(!unlink(link), "symlink removed");
    file = open(config, O_WRONLY | O_TRUNC);
    Check(file >= 0 && !close(file), "empty configuration fixture");
    Check(PRRunEngineWorker(argv[1], config, pair[1], Record, NULL) == 78, "empty config refused");
    close(pair[0]); close(pair[1]);
    char owner_path[300];
    snprintf(owner_path, sizeof(owner_path), "%s.owner", config);
    Check(!unlink(owner_path), "recorded child identity removed");
    Check(!unlink(config) && !rmdir(directory), "owned test staging removed");
    printf("macOS engine worker: %d assertions passed; no TUN or host network changes\n", checks);
    return 0;
}
