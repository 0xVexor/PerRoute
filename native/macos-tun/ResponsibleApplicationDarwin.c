#include "ResponsibleApplicationInternal.h"
#include <Security/Security.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <libproc.h>
#include <stdlib.h>
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

static bool SocketMatches(const struct socket_info *socket, const PRIPv4TCPConnection *expected) {
    if (socket->soi_kind != SOCKINFO_TCP || socket->soi_type != SOCK_STREAM ||
        socket->soi_protocol != IPPROTO_TCP || (socket->soi_family != AF_INET && socket->soi_family != AF_INET6) ||
        socket->soi_proto.pri_tcp.tcpsi_state < TSI_S_SYN_SENT ||
        socket->soi_proto.pri_tcp.tcpsi_state > TSI_S_TIME_WAIT) return false;
    const struct in_sockinfo *connection = &socket->soi_proto.pri_tcp.tcpsi_ini;
    uint32_t source, destination;
    if (connection->insi_vflag & INI_IPV4) {
        source = connection->insi_laddr.ina_46.i46a_addr4.s_addr;
        destination = connection->insi_faddr.ina_46.i46a_addr4.s_addr;
    } else if ((connection->insi_vflag & INI_IPV6) &&
        IN6_IS_ADDR_V4MAPPED(&connection->insi_laddr.ina_6) &&
        IN6_IS_ADDR_V4MAPPED(&connection->insi_faddr.ina_6)) {
        memcpy(&source, &connection->insi_laddr.ina_6.s6_addr[12], sizeof(source));
        memcpy(&destination, &connection->insi_faddr.ina_6.s6_addr[12], sizeof(destination));
    } else return false;
    return source == expected->sourceAddress && destination == expected->destinationAddress &&
        ntohs((uint16_t)connection->insi_lport) == expected->sourcePort &&
        ntohs((uint16_t)connection->insi_fport) == expected->destinationPort;
}

bool PRReadDarwinTCPConnection(void *context, pid_t pid, const PRIPv4TCPConnection *connection, uint64_t *generation) {
    (void)context;
    if (!generation) return false;
    *generation = 0;
    if (!connection || pid < 2) return false;
    int size = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, NULL, 0);
    if (size <= 0 || size > 1048576 - 4096) return false;
    size += 4096;
    struct proc_fdinfo *descriptors = malloc((size_t)size);
    if (!descriptors) return false;
    int length = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, descriptors, size);
    bool valid = length > 0 && length < size && length % sizeof(*descriptors) == 0;
    unsigned matches = 0;
    for (size_t i = 0; valid && i < (size_t)length / sizeof(*descriptors); i++) {
        if (descriptors[i].proc_fdtype != PROX_FDTYPE_SOCKET) continue;
        struct socket_fdinfo socket = {0};
        if (proc_pidfdinfo(pid, descriptors[i].proc_fd, PROC_PIDFDSOCKETINFO, &socket, sizeof(socket)) != sizeof(socket)) continue;
        if (!SocketMatches(&socket.psi, connection)) continue;
        matches++;
        *generation = socket.psi.soi_proto.pri_tcp.tcpsi_ini.insi_gencnt;
    }
    free(descriptors);
    // Refuse ambiguous descriptor observations, including duplicate socket FDs.
    // No port-only, wildcard-address or cached-PID fallback grants attribution.
    return valid && matches == 1 && *generation > 0;
}

static bool VerifySafari(void *context, pid_t pid) {
    (void)context;
    SecCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    CFNumberRef number = CFNumberCreate(NULL, kCFNumberIntType, &pid);
    if (!number) return false;
    const void *keys[] = {kSecGuestAttributePid};
    const void *values[] = {number};
    CFDictionaryRef attributes = CFDictionaryCreate(NULL, keys, values, 1,
        &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    bool valid = attributes &&
        SecCodeCopyGuestWithAttributes(NULL, attributes, kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecRequirementCreateWithString(CFSTR("anchor apple and identifier \"com.apple.Safari\""),
            kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecCodeCheckValidity(code, kSecCSStrictValidate, requirement) == errSecSuccess;
    if (requirement) CFRelease(requirement);
    if (code) CFRelease(code);
    if (attributes) CFRelease(attributes);
    CFRelease(number);
    return valid;
}

bool PRObserveSafariTCPConnection(pid_t socketPid, uid_t user,
    const char *selectedExecutable, const PRIPv4TCPConnection *connection,
    PRResponsibleApplication *result) {
    ResponsibilityPid lookup = (ResponsibilityPid)dlsym(RTLD_DEFAULT,
        "responsibility_get_pid_responsible_for_pid");
    return PRObserveResponsibleConnectionWithReaders(socketPid, user, selectedExecutable, connection,
        result, &lookup, ReadIdentity, lookup ? ReadResponsible : NULL, PRReadDarwinTCPConnection, VerifySafari);
}
