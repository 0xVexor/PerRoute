#include "ResponsibleApplicationInternal.h"
#include <dlfcn.h>
#include <libproc.h>
#include <string.h>
#include <sys/proc.h>
#include <sys/proc_info.h>

typedef pid_t (*ResponsibilityPid)(pid_t);

static bool SameProcess(const struct proc_bsdinfo *left, const struct proc_bsdinfo *right) {
    return left->pbi_pid == right->pbi_pid && left->pbi_uid == right->pbi_uid &&
        left->pbi_ruid == right->pbi_ruid && left->pbi_start_tvsec == right->pbi_start_tvsec &&
        left->pbi_start_tvusec == right->pbi_start_tvusec &&
        left->pbi_status != SZOMB && right->pbi_status != SZOMB;
}

static bool ReadIdentity(void *context, pid_t pid, PRProcessIdentity *result) {
    (void)context;
    struct proc_bsdinfo before = {0}, after = {0};
    _Static_assert(PR_PROCESS_PATH_SIZE >= PROC_PIDPATHINFO_MAXSIZE, "Process path buffer is too small");
    memset(result, 0, sizeof(*result));
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &before, sizeof(before)) != sizeof(before) ||
        before.pbi_pid != (uint32_t)pid ||
        proc_pidpath(pid, result->executable, sizeof(result->executable)) <= 0 ||
        proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &after, sizeof(after)) != sizeof(after) ||
        !SameProcess(&before, &after)) return false;
    result->pid = pid;
    result->user = after.pbi_uid;
    result->realUser = after.pbi_ruid;
    result->startSeconds = after.pbi_start_tvsec;
    result->startMicroseconds = after.pbi_start_tvusec;
    return true;
}

static pid_t ReadResponsible(void *context, pid_t pid) {
    ResponsibilityPid *lookup = context;
    return (*lookup)(pid);
}

bool PRObserveResponsibleApplication(pid_t socketPid, uid_t user,
    const char *selectedExecutable, PRResponsibleApplication *result) {
    // This SPI has no public availability guarantee. Resolve it at runtime and
    // return unavailable rather than making it a loader dependency or guessing.
    ResponsibilityPid lookup = (ResponsibilityPid)dlsym(RTLD_DEFAULT,
        "responsibility_get_pid_responsible_for_pid");
    return PRObserveResponsibleApplicationWithReaders(socketPid, user, selectedExecutable,
        result, &lookup, ReadIdentity, lookup ? ReadResponsible : NULL);
}
