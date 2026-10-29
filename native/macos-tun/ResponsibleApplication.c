#include "ResponsibleApplicationInternal.h"
#include <string.h>

static bool ValidIdentity(const PRProcessIdentity *identity, pid_t pid, uid_t user) {
    return identity->pid == pid && identity->user == user && identity->realUser == user &&
        identity->startSeconds > 0 && identity->startMicroseconds < 1000000 &&
        identity->executable[0] == '/' &&
        memchr(identity->executable, '\0', sizeof(identity->executable)) != NULL;
}

static bool SameIdentity(const PRProcessIdentity *left, const PRProcessIdentity *right) {
    return left->pid == right->pid && left->user == right->user && left->realUser == right->realUser &&
        left->startSeconds == right->startSeconds && left->startMicroseconds == right->startMicroseconds &&
        strcmp(left->executable, right->executable) == 0;
}

bool PRObserveResponsibleApplicationWithReaders(pid_t socketPid, uid_t user,
    const char *selectedExecutable, PRResponsibleApplication *result,
    void *context, PRReadProcessIdentity readIdentity, PRReadResponsiblePid readResponsible) {
    if (!result) return false;
    memset(result, 0, sizeof(*result));
    if (socketPid < 2 || !user || !selectedExecutable || selectedExecutable[0] != '/' ||
        strnlen(selectedExecutable, PR_PROCESS_PATH_SIZE) >= PR_PROCESS_PATH_SIZE ||
        !readIdentity || !readResponsible) return false;

    PRProcessIdentity socketBefore = {0}, appBefore = {0}, socketAfter = {0}, appAfter = {0};
    if (!readIdentity(context, socketPid, &socketBefore) || !ValidIdentity(&socketBefore, socketPid, user)) return false;
    pid_t appPid = readResponsible(context, socketPid);
    // Ordinary exact-process routing remains independent of this explicit
    // responsible-application scope; no parent-chain or name fallback is used.
    if (appPid < 2 || appPid == socketPid ||
        !readIdentity(context, appPid, &appBefore) || !ValidIdentity(&appBefore, appPid, user) ||
        strcmp(appBefore.executable, selectedExecutable) != 0) return false;

    if (!readIdentity(context, socketPid, &socketAfter) || !ValidIdentity(&socketAfter, socketPid, user) ||
        !SameIdentity(&socketBefore, &socketAfter) || readResponsible(context, socketPid) != appPid ||
        !readIdentity(context, appPid, &appAfter) || !ValidIdentity(&appAfter, appPid, user) ||
        !SameIdentity(&appBefore, &appAfter)) return false;

    result->socketProcess = socketAfter;
    result->application = appAfter;
    return true;
}

bool PRObserveResponsibleConnectionWithReaders(pid_t socketPid, uid_t user,
    const char *selectedExecutable, const PRIPv4TCPConnection *connection,
    PRResponsibleApplication *result, void *context,
    PRReadProcessIdentity readIdentity, PRReadResponsiblePid readResponsible,
    PRReadTCPConnection readConnection, PRVerifyApplication verifyApplication) {
    if (!result) return false;
    memset(result, 0, sizeof(*result));
    if (!connection || !connection->sourceAddress || !connection->destinationAddress ||
        !connection->sourcePort || !connection->destinationPort || !readConnection || !verifyApplication) return false;
    PRResponsibleApplication before = {0}, after = {0};
    uint64_t firstGeneration = 0, lastGeneration = 0;
    if (!PRObserveResponsibleApplicationWithReaders(socketPid, user, selectedExecutable, &before,
            context, readIdentity, readResponsible) ||
        !readConnection(context, socketPid, connection, &firstGeneration) || !firstGeneration ||
        !verifyApplication(context, before.application.pid) ||
        !PRObserveResponsibleApplicationWithReaders(socketPid, user, selectedExecutable, &after,
            context, readIdentity, readResponsible) ||
        !SameIdentity(&before.socketProcess, &after.socketProcess) ||
        !SameIdentity(&before.application, &after.application) ||
        !readConnection(context, socketPid, connection, &lastGeneration) ||
        firstGeneration != lastGeneration || !verifyApplication(context, after.application.pid)) return false;
    *result = after;
    return true;
}
