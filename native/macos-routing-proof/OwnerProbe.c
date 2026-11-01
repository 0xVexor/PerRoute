#include <arpa/inet.h>
#include <libproc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/proc_info.h>
#include <unistd.h>

static long positive_number(const char *text, long maximum) {
    char *end = NULL;
    long value = strtol(text, &end, 10);
    return *text && !*end && value > 0 && value <= maximum ? value : -1;
}

// Inspect only the child PID and exact loopback tuple supplied by the owned fixture. This is
// feasibility evidence, not an authority check for arbitrary apps or a production process cache.
int main(int argc, char **argv) {
    if (argc != 5) return 2;
    long pid = positive_number(argv[1], 2147483647);
    long source = positive_number(argv[2], 65535);
    long destination = positive_number(argv[3], 65535);
    if (pid < 0 || source < 0 || destination < 0) return 2;
    struct proc_bsdinfo before = {0}, after = {0};
    if (proc_pidinfo((int)pid, PROC_PIDTBSDINFO, 0, &before, sizeof(before)) != sizeof(before) ||
        before.pbi_uid != getuid()) return 3;
    int size = proc_pidinfo((int)pid, PROC_PIDLISTFDS, 0, NULL, 0);
    if (size <= 0 || size > 1024 * 1024 - 4096) return 3;
    size += 4096;
    struct proc_fdinfo *descriptors = malloc((size_t)size);
    if (!descriptors) return 3;
    int read_size = proc_pidinfo((int)pid, PROC_PIDLISTFDS, 0, descriptors, size);
    if (read_size <= 0 || read_size >= size || read_size % sizeof(*descriptors)) { free(descriptors); return 3; }
    int matches = 0;
    for (size_t i = 0; i < (size_t)read_size / sizeof(*descriptors); i++) {
        if (descriptors[i].proc_fdtype != PROX_FDTYPE_SOCKET) continue;
        struct socket_fdinfo socket_info = {0};
        if (proc_pidfdinfo((int)pid, descriptors[i].proc_fd, PROC_PIDFDSOCKETINFO,
                           &socket_info, sizeof(socket_info)) != sizeof(socket_info)) continue;
        if (socket_info.psi.soi_kind != SOCKINFO_TCP || socket_info.psi.soi_family != AF_INET) continue;
        struct in_sockinfo *connection = &socket_info.psi.soi_proto.pri_tcp.tcpsi_ini;
        if (ntohs((uint16_t)connection->insi_lport) == source &&
            ntohs((uint16_t)connection->insi_fport) == destination &&
            connection->insi_laddr.ina_46.i46a_addr4.s_addr == htonl(INADDR_LOOPBACK) &&
            connection->insi_faddr.ina_46.i46a_addr4.s_addr == htonl(INADDR_LOOPBACK)) matches++;
    }
    free(descriptors);
    char executable[PROC_PIDPATHINFO_MAXSIZE] = {0};
    if (proc_pidpath((int)pid, executable, sizeof(executable)) <= 0 || strcmp(executable, argv[4]) || matches != 1) return 4;
    if (proc_pidinfo((int)pid, PROC_PIDTBSDINFO, 0, &after, sizeof(after)) != sizeof(after) ||
        before.pbi_start_tvsec != after.pbi_start_tvsec || before.pbi_start_tvusec != after.pbi_start_tvusec) return 4;
    puts("Owned child TCP tuple, UID, process start time and exact executable path verified");
    return 0;
}
