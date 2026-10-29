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

/* IPv4 addresses are in network byte order; ports are in host byte order. */
typedef struct {
    uint32_t sourceAddress;
    uint32_t destinationAddress;
    uint16_t sourcePort;
    uint16_t destinationPort;
} PRIPv4TCPConnection;

/* Observation only. The caller must independently prove that socketPid owns the
 * flow. This does not authorize a route or replace executable/signature checks.
 * No identity is cached; a failed lookup clears the result and must not broaden
 * an exact-executable rule into a global shared-process rule. */
bool PRObserveResponsibleApplication(pid_t socketPid, uid_t user,
    const char *selectedExecutable, PRResponsibleApplication *result);

/* Verifies the exact TCP tuple and Apple-signed Safari responsibility with
 * repeated uncached observations. This is a point-in-time observation, not a
 * durable lease. Engine exclusions and explicit routing scope remain required. */
bool PRObserveSafariTCPConnection(pid_t socketPid, uid_t user,
    const char *selectedExecutable, const PRIPv4TCPConnection *connection,
    PRResponsibleApplication *result);

void PRSafariDiagnostic(const char *stage, bool matched, pid_t socketPid, uid_t user);

#endif
