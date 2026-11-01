#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc != 4 || strcmp(argv[1], "run") || strcmp(argv[2], "-c")) return 90;
    int config = open(argv[3], O_RDONLY);
    if (config < 0) return 91;
    char mode = 0;
    if (read(config, &mode, 1) != 1) return 92;
    close(config);
    if (getenv("PERROUTE_WORKER_SECRET")) return 94;
    for (int fd = 3; fd < 128; fd++) if (fcntl(fd, F_GETFD) >= 0) return 95;
    if (mode == 'E') return 17;
    signal(SIGALRM, SIG_DFL); alarm(8); // Bound this synthetic process even if a regression breaks its worker.
    if (mode == 'I') signal(SIGTERM, SIG_IGN);
    config = open(argv[3], O_WRONLY | O_APPEND);
    if (config < 0 || write(config, "R", 1) != 1 || close(config)) return 93;
    for (;;) pause();
}
