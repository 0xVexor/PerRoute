#include <arpa/inet.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

// One bounded synthetic request to loopback only. Copies of this executable let the engine
// distinguish paths while keeping the process name and executable contents identical.
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    char *end = NULL;
    long port = strtol(argv[1], &end, 10);
    if (!*argv[1] || *end || port < 1 || port > 65535) return 2;
    signal(SIGPIPE, SIG_IGN);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return 3;
    struct timeval timeout = {.tv_sec = 5, .tv_usec = 0};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) { close(fd); return 3; }
    struct sockaddr_in address = {.sin_len = sizeof(address), .sin_family = AF_INET,
                                 .sin_port = htons((uint16_t)port)};
    inet_pton(AF_INET, "127.0.0.1", &address.sin_addr);
    if (connect(fd, (struct sockaddr *)&address, sizeof(address))) { close(fd); return 4; }
    const char request[] = "perroute-process-proof\n";
    size_t sent = 0;
    while (sent < sizeof(request) - 1) {
        ssize_t count = send(fd, request + sent, sizeof(request) - 1 - sent, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(fd); return 5; }
        sent += (size_t)count;
    }
    char response[64] = {0};
    size_t received = 0;
    while (received < sizeof(response) - 1 && !strchr(response, '\n')) {
        ssize_t count = recv(fd, response + received, sizeof(response) - 1 - received, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        received += (size_t)count;
    }
    close(fd);
    if (strcmp(response, "perroute-proof-ok\n")) return 6;
    puts("Controlled loopback request passed");
    return 0;
}
