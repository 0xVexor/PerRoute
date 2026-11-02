#include "ResponsibleApplicationInternal.h"
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

static unsigned Checks;
#define CHECK(value) do { Checks++; if (!(value)) { \
    fprintf(stderr, "Darwin TCP ownership line %d: %s\n", __LINE__, #value); exit(1); \
} } while (0)

int main(void) {
    // Disposable sockets owned by this fixture only. No TUN, privileged helper,
    // browser process or external server participates in these checks.
    int listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    CHECK(listener >= 0);
    struct sockaddr_in address = {.sin_len = sizeof(address), .sin_family = AF_INET,
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    CHECK(bind(listener, (struct sockaddr *)&address, sizeof(address)) == 0);
    socklen_t length = sizeof(address);
    CHECK(getsockname(listener, (struct sockaddr *)&address, &length) == 0);
    CHECK(listen(listener, 1) == 0);
    int client = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    CHECK(client >= 0);
    CHECK(connect(client, (struct sockaddr *)&address, sizeof(address)) == 0);
    int accepted = accept(listener, NULL, NULL);
    CHECK(accepted >= 0);
    struct sockaddr_in source = {0};
    length = sizeof(source);
    CHECK(getsockname(client, (struct sockaddr *)&source, &length) == 0);
    PRIPv4TCPConnection tuple = {.sourceAddress = source.sin_addr.s_addr,
        .destinationAddress = address.sin_addr.s_addr,
        .sourcePort = ntohs(source.sin_port), .destinationPort = ntohs(address.sin_port)};
    uint64_t generation = 0, again = 0;
    CHECK(PRReadDarwinTCPConnection(NULL, getpid(), &tuple, &generation));
    CHECK(generation > 0);
    CHECK(PRReadDarwinTCPConnection(NULL, getpid(), &tuple, &again));
    CHECK(generation == again);
    for (unsigned changed = 0; changed < 4; changed++) {
        PRIPv4TCPConnection wrong = tuple;
        switch (changed) {
            case 0: wrong.sourceAddress = htonl(0x7f000002); break;
            case 1: wrong.destinationAddress = htonl(0x7f000002); break;
            case 2: wrong.sourcePort = 0; break;
            case 3: wrong.destinationPort = 0; break;
        }
        CHECK(!PRReadDarwinTCPConnection(NULL, getpid(), &wrong, &again));
    }
    CHECK(!PRReadDarwinTCPConnection(NULL, 1, &tuple, &again));
    CHECK(!PRReadDarwinTCPConnection(NULL, getpid(), NULL, &again));
    CHECK(!PRReadDarwinTCPConnection(NULL, getpid(), &tuple, NULL));
    int duplicate = dup(client);
    CHECK(duplicate >= 0);
    CHECK(!PRReadDarwinTCPConnection(NULL, getpid(), &tuple, &again));
    CHECK(close(duplicate) == 0);
    CHECK(PRReadDarwinTCPConnection(NULL, getpid(), &tuple, &again));
    CHECK(close(client) == 0);
    CHECK(!PRReadDarwinTCPConnection(NULL, getpid(), &tuple, &again));
    CHECK(close(accepted) == 0);
    CHECK(close(listener) == 0);
    printf("Darwin TCP ownership: %u loopback checks passed; no host routing changed\n", Checks);
    return 0;
}
