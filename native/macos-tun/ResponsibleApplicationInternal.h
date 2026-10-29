#ifndef PERROUTE_RESPONSIBLE_APPLICATION_INTERNAL_H
#define PERROUTE_RESPONSIBLE_APPLICATION_INTERNAL_H

#include "ResponsibleApplication.h"

typedef bool (*PRReadProcessIdentity)(void *context, pid_t pid, PRProcessIdentity *result);
typedef pid_t (*PRReadResponsiblePid)(void *context, pid_t pid);
typedef bool (*PRReadTCPConnection)(void *context, pid_t pid,
    const PRIPv4TCPConnection *connection, uint64_t *generation);
typedef bool (*PRVerifyApplication)(void *context, pid_t pid);

bool PRReadDarwinTCPConnection(void *context, pid_t pid,
    const PRIPv4TCPConnection *connection, uint64_t *generation);

/* Inject observations so exit, PID reuse and ownership changes can be checked
 * without starting browsers, touching the helper or changing host routing. */
bool PRObserveResponsibleApplicationWithReaders(pid_t socketPid, uid_t user,
    const char *selectedExecutable, PRResponsibleApplication *result,
    void *context, PRReadProcessIdentity readIdentity, PRReadResponsiblePid readResponsible);

bool PRObserveResponsibleConnectionWithReaders(pid_t socketPid, uid_t user,
    const char *selectedExecutable, const PRIPv4TCPConnection *connection,
    PRResponsibleApplication *result, void *context,
    PRReadProcessIdentity readIdentity, PRReadResponsiblePid readResponsible,
    PRReadTCPConnection readConnection, PRVerifyApplication verifyApplication);

#endif
