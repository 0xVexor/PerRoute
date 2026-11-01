#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
int main(int argc, char **argv) {
    alarm(10); // Bound disposable fixture lifetime even if recovery regresses.
    signal(SIGTERM, SIG_IGN);
    if (argc != 2) return 1;
    int ready = open(argv[1], O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (ready < 0 || write(ready, "R", 1) != 1 || close(ready)) return 2;
    for (;;) pause();
}
