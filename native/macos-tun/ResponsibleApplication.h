#ifndef PERROUTE_RESPONSIBLE_APPLICATION_H
#define PERROUTE_RESPONSIBLE_APPLICATION_H

#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>

#define PR_PROCESS_PATH_SIZE 4096

typedef struct {
    pid_t pid;
    uid_t user;
    uid_t realUser;
    uint64_t startSeconds;
    uint64_t startMicroseconds;
    char executable[PR_PROCESS_PATH_SIZE];
} PRProcessIdentity;

typedef struct {
    PRProcessIdentity socketProcess;
    PRProcessIdentity application;
} PRResponsibleApplication;

/* Observation only. The caller must independently prove that socketPid owns the
 * flow. This does not authorize a route or replace executable/signature checks.
 * No identity is cached; a failed lookup clears the result and must not broaden
 * an exact-executable rule into a global shared-process rule. */
bool PRObserveResponsibleApplication(pid_t socketPid, uid_t user,
    const char *selectedExecutable, PRResponsibleApplication *result);

#endif
