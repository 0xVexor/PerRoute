#include "EngineWorker.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <libproc.h>
#include <sys/proc_info.h>
#include <signal.h>
#include <spawn.h>
#include <stdbool.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static bool Regular(const char *path, off_t limit, bool executable) {
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat info;
    bool valid = !fstat(fd, &info) && S_ISREG(info.st_mode) && info.st_uid == geteuid() &&
        !(info.st_mode & 0022) && info.st_size > 0 && info.st_size <= limit &&
        (!executable || (info.st_mode & S_IXUSR));
    close(fd);
    return valid;
}

static bool Event(int control, int state, int reason, pid_t child, int status) {
    struct PRWorkerEvent event = {.version = 1, .state = state, .reason = reason, .pid = child, .wait_status = status};
    // Fixed-size native events never block indefinitely on a lost owner. The parent
    // must read a complete event before accepting it; stream reads may be partial.
    return send(control, &event, sizeof(event), MSG_DONTWAIT | MSG_NOSIGNAL) == sizeof(event);
}

static bool Reap(pid_t child, int *status) {
    struct timespec pause = {.tv_sec = 0, .tv_nsec = 25000000};
    const int signals[] = {SIGTERM, SIGKILL};
    for (unsigned stage = 0; stage < sizeof(signals) / sizeof(signals[0]); stage++) {
        // Check before signalling: once reaped, this PID must never be used as authority again.
        pid_t waited = waitpid(child, status, WNOHANG);
        if (waited == child) return true;
        if (waited < 0 && errno != EINTR) return false;
        kill(-child, signals[stage]);
        if (kill(child, signals[stage]) && errno != ESRCH) return false;
        for (int attempt = 0; attempt < 40; attempt++) {
            waited = waitpid(child, status, WNOHANG);
            if (waited == child) return true;
            if (waited < 0 && errno != EINTR) return false;
            nanosleep(&pause, NULL);
        }
    }
    return false;
}

int PRRunEngineWorker(const char *engine, const char *configuration, int control, PRWorkerRecord record, void *context) {
    int socket_type = 0;
    socklen_t type_length = sizeof(socket_type);
    if (!record || !engine || engine[0] != '/' || !configuration || configuration[0] != '/' || control < 3 ||
        getsockopt(control, SOL_SOCKET, SO_TYPE, &socket_type, &type_length) || socket_type != SOCK_STREAM ||
        !Regular(engine, 200 * 1024 * 1024, true) || !Regular(configuration, 1024 * 1024, false)) return 78;
    int null_device = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_device < 0) return 78;
    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attributes;
    int code = posix_spawn_file_actions_init(&actions);
    if (code) { close(null_device); return 78; }
    code = posix_spawnattr_init(&attributes);
    if (code) { posix_spawn_file_actions_destroy(&actions); close(null_device); return 78; }
    for (int fd = 0; fd < 3 && !code; fd++) code = posix_spawn_file_actions_adddup2(&actions, null_device, fd);
    sigset_t defaults, mask;
    sigemptyset(&mask);
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGTERM); sigaddset(&defaults, SIGINT); sigaddset(&defaults, SIGPIPE);
    if (!code) code = posix_spawnattr_setsigmask(&attributes, &mask);
    if (!code) code = posix_spawnattr_setsigdefault(&attributes, &defaults);
    if (!code) code = posix_spawnattr_setpgroup(&attributes, 0);
    if (!code) code = posix_spawnattr_setflags(&attributes,
        POSIX_SPAWN_START_SUSPENDED | POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_CLOEXEC_DEFAULT);
    char *arguments[] = {(char *)engine, "run", "-c", (char *)configuration, NULL};
    char *environment[] = {"PATH=/usr/bin:/bin", "HOME=/var/empty", "TMPDIR=/private/tmp", NULL};
    pid_t child = 0;
    if (!code) code = posix_spawn(&child, engine, &actions, &attributes, arguments, environment);
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attributes);
    close(null_device);
    if (code) { Event(control, PR_WORKER_STOPPED, PR_WORKER_FAILURE, 0, code); return 79; }
    int status = 0, reason = PR_WORKER_FAILURE;
    struct proc_bsdinfo info = {0};
    struct PRWorkerIdentity identity = {.pid = child};
    bool observed = proc_pidinfo(child, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) == sizeof(info) &&
        info.pbi_pid == (unsigned)child && info.pbi_uid == geteuid() && info.pbi_start_tvsec > 0;
    if (observed) { identity.start_seconds = info.pbi_start_tvsec; identity.start_microseconds = info.pbi_start_tvusec; }
    if (!observed || record(&identity, context)) {
        bool stopped = Reap(child, &status);
        Event(control, PR_WORKER_STOPPED, PR_WORKER_FAILURE, child, status);
        return stopped ? 79 : 80;
    }
    struct pollfd owner = {.fd = control, .events = POLLIN};
    int ready = poll(&owner, 1, 0);
    if (ready != 0) {
        reason = ready < 0 ? PR_WORKER_FAILURE : PR_WORKER_OWNER_LOST;
        if (ready > 0 && (owner.revents & POLLIN) && !(owner.revents & POLLHUP)) {
            char command[2];
            ssize_t count = recv(control, command, sizeof(command), MSG_DONTWAIT);
            reason = count == 1 && command[0] == 'S' ? PR_WORKER_STOP : count == 0 ? PR_WORKER_OWNER_LOST : PR_WORKER_BAD_CONTROL;
        }
        bool stopped = Reap(child, &status);
        Event(control, PR_WORKER_STOPPED, reason, child, status);
        return !stopped ? 80 : reason == PR_WORKER_STOP || reason == PR_WORKER_OWNER_LOST ? 0 : 79;
    }
    if (kill(child, SIGCONT)) {
        bool stopped = Reap(child, &status);
        Event(control, PR_WORKER_STOPPED, PR_WORKER_FAILURE, child, status);
        return stopped ? 79 : 80;
    }
    bool reaped = false;
    if (Event(control, PR_WORKER_RUNNING, 0, child, 0)) {
        for (;;) {
            pid_t waited = waitpid(child, &status, WNOHANG);
            if (waited == child) { reaped = true; reason = PR_WORKER_ENGINE_EXIT; break; }
            if (waited < 0 && errno != EINTR) break;
            struct pollfd observed = {.fd = control, .events = POLLIN};
            int ready = poll(&observed, 1, 100);
            if (ready < 0) { if (errno == EINTR) continue; break; }
            if (!ready) continue;
            if (observed.revents & (POLLHUP | POLLERR | POLLNVAL)) { reason = PR_WORKER_OWNER_LOST; break; }
            if (observed.revents & POLLIN) {
                char command[2];
                ssize_t count = recv(control, command, sizeof(command), MSG_DONTWAIT);
                if (count == 1 && command[0] == 'S') reason = PR_WORKER_STOP;
                else if (count == 0) reason = PR_WORKER_OWNER_LOST;
                else reason = PR_WORKER_BAD_CONTROL;
                break;
            }
        }
    } else reason = PR_WORKER_OWNER_LOST;
    if (!reaped && !Reap(child, &status)) {
        Event(control, PR_WORKER_STOPPED, PR_WORKER_FAILURE, child, status);
        return 80; // Keep recovery ownership outside this worker when termination is unproved.
    }
    Event(control, PR_WORKER_STOPPED, reason, child, status);
    return reason == PR_WORKER_STOP || reason == PR_WORKER_OWNER_LOST ? 0 : 79;
}
