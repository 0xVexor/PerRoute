#include "ResponsibleApplication.h"
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>

static bool PositiveNumber(const char *text, unsigned long *result) {
    if (!text || text[0] < '1' || text[0] > '9') return false;
    char *end = NULL;
    errno = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (errno || !end || *end || !value || value > INT_MAX) return false;
    *result = value;
    return true;
}

int main(int argc, const char **argv) {
    unsigned long pid, user;
    if (argc != 4 || !PositiveNumber(argv[1], &pid) || !PositiveNumber(argv[2], &user)) {
        fprintf(stderr, "Usage: responsible-application-probe SOCKET_PID UID SELECTED_EXECUTABLE\n");
        return 2;
    }
    PRResponsibleApplication observation;
    if (!PRObserveResponsibleApplication((pid_t)pid, (uid_t)user, argv[3], &observation)) {
        fprintf(stderr, "No stable same-user responsible application matched; no routing authority granted\n");
        return 1;
    }
    // Paths are not printed, keeping this diagnostic to process identity only.
    printf("socket_pid=%d application_pid=%d uid=%u socket_start=%llu.%06llu application_start=%llu.%06llu\n",
        observation.socketProcess.pid, observation.application.pid, observation.application.user,
        (unsigned long long)observation.socketProcess.startSeconds,
        (unsigned long long)observation.socketProcess.startMicroseconds,
        (unsigned long long)observation.application.startSeconds,
        (unsigned long long)observation.application.startMicroseconds);
    return 0;
}
