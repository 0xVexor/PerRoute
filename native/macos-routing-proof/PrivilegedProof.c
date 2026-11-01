#include <CommonCrypto/CommonDigest.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "ProofPins.h"

static volatile sig_atomic_t stopping;
static void stop_requested(int signal_number) { (void)signal_number; stopping = 1; }

// Pins are generated for this single invocation, after the official archive and loopback
// configuration have been checked. Copy and hash the same bytes to avoid a check/use race.
static bool verified_copy(const char *source, const char *target, const char *expected, size_t limit, mode_t mode) {
    int input = open(source, O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
    if (input < 0) return false;
    struct stat info;
    if (fstat(input, &info) || !S_ISREG(info.st_mode) || info.st_uid != PROOF_UID ||
        info.st_size < 1 || (size_t)info.st_size > limit) { close(input); return false; }
    int output = target ? open(target, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, mode) : -1;
    if (target && output < 0) { close(input); return false; }
    CC_SHA256_CTX hash;
    CC_SHA256_Init(&hash);
    unsigned char bytes[65536], digest[CC_SHA256_DIGEST_LENGTH];
    size_t total = 0;
    bool valid = true;
    for (;;) {
        ssize_t count = read(input, bytes, sizeof(bytes));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { valid = false; break; }
        if (!count) break;
        total += (size_t)count;
        if (total > limit) { valid = false; break; }
        CC_SHA256_Update(&hash, bytes, (CC_LONG)count);
        for (ssize_t offset = 0; output >= 0 && offset < count;) {
            ssize_t written = write(output, bytes + offset, (size_t)(count - offset));
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) { valid = false; break; }
            offset += written;
        }
        if (!valid) break;
    }
    CC_SHA256_Final(digest, &hash);
    char hex[CC_SHA256_DIGEST_LENGTH * 2 + 1];
    for (size_t i = 0; i < sizeof(digest); i++) snprintf(hex + i * 2, 3, "%02x", digest[i]);
    close(input);
    if (output >= 0 && close(output)) valid = false;
    return valid && total == (size_t)info.st_size && !strcmp(hex, expected);
}

// Signal the known child directly as well as its group. Reaping must stay bounded
// even if a process-group operation fails or the engine refuses graceful shutdown.
static bool stop_child(pid_t child) {
    const int signals[] = {SIGTERM, SIGKILL};
    struct timespec pause = {.tv_sec = 0, .tv_nsec = 50000000};
    for (size_t stage = 0; stage < sizeof(signals) / sizeof(signals[0]); stage++) {
        kill(-child, signals[stage]);
        if (kill(child, signals[stage]) && errno != ESRCH) return false;
        for (int attempt = 0; attempt < 40; attempt++) {
            pid_t waited = waitpid(child, NULL, WNOHANG);
            if (waited == child || (waited < 0 && errno == ECHILD)) return true;
            if (waited < 0 && errno != EINTR) return false;
            nanosleep(&pause, NULL);
        }
    }
    return false;
}

int main(int argc, char **argv) {
    bool verify_only = argc == 2 && !strcmp(argv[1], "--verify-only");
    if (!verify_only && (argc != 1 || geteuid() != 0)) return 77;
    if (verify_only) {
        if (!verified_copy(PROOF_ENGINE, NULL, PROOF_ENGINE_HASH, 200 * 1024 * 1024, 0500) ||
            !verified_copy(PROOF_CONFIG, NULL, PROOF_CONFIG_HASH, 1024 * 1024, 0600)) return 78;
        puts("One-shot engine and loopback configuration pins verified");
        return 0;
    }
    umask(077);
    char directory[] = "/private/tmp/perroute-root-proof.XXXXXX";
    if (!mkdtemp(directory)) return 78;
    char engine[256], config[256];
    snprintf(engine, sizeof(engine), "%s/engine", directory);
    snprintf(config, sizeof(config), "%s/fixture.json", directory);
    int outcome = 78;
    if (!verified_copy(PROOF_ENGINE, engine, PROOF_ENGINE_HASH, 200 * 1024 * 1024, 0500) ||
        !verified_copy(PROOF_CONFIG, config, PROOF_CONFIG_HASH, 1024 * 1024, 0600)) goto cleanup;
    signal(SIGTERM, stop_requested);
    signal(SIGINT, stop_requested);
    pid_t child = fork();
    if (child < 0) goto cleanup;
    if (!child) {
        if (setpgid(0, 0) || chdir(directory)) _exit(79);
        int null_device = open("/dev/null", O_RDWR);
        if (null_device < 0) _exit(79);
        for (int fd = 0; fd < 3; fd++) if (dup2(null_device, fd) < 0) _exit(79);
        if (null_device > 2) close(null_device);
        char *arguments[] = {engine, "run", "-c", config, NULL};
        char *environment[] = {"PATH=/usr/bin:/bin", "HOME=/var/empty", "TMPDIR=/private/tmp", NULL};
        execve(engine, arguments, environment);
        _exit(79);
    }
    setpgid(child, child);
    // The live comparison outlived its signal-based deadline. Check elapsed time
    // directly so termination does not depend on receiving SIGALRM.
    struct timespec deadline;
    if (clock_gettime(CLOCK_MONOTONIC, &deadline)) {
        if (!stop_child(child)) return 81;
        goto cleanup;
    }
    deadline.tv_sec += 20;
    int status = 0;
    pid_t waited = 0;
    struct timespec pause = {.tv_sec = 0, .tv_nsec = 50000000};
    bool timer_failed = false;
    while (!stopping) {
        struct timespec now;
        if (clock_gettime(CLOCK_MONOTONIC, &now)) { timer_failed = true; break; }
        if (now.tv_sec > deadline.tv_sec ||
            (now.tv_sec == deadline.tv_sec && now.tv_nsec >= deadline.tv_nsec)) break;
        waited = waitpid(child, &status, WNOHANG);
        if (waited == child) break;
        if (waited < 0 && errno != EINTR) break;
        nanosleep(&pause, NULL);
    }
    if (waited != child) {
        if (!stop_child(child)) return 81;
        outcome = timer_failed ? 78 : 0;
    } else {
        // Early engine exit must fail the comparison rather than look like a completed test.
        outcome = 79;
    }
cleanup:
    if (unlink(engine) && errno != ENOENT) outcome = 80;
    if (unlink(config) && errno != ENOENT) outcome = 80;
    if (rmdir(directory)) outcome = 80;
    if (!outcome) puts("One-shot privileged engine stopped and root staging removed");
    return outcome;
}
