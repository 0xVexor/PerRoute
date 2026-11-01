#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1],"exit")) { puts("fixture output"); return 42; }
    if (argc == 2 && !strcmp(argv[1],"ignore-term")) signal(SIGTERM,SIG_IGN);
    puts("fixture ready"); fflush(stdout);
    for (;;) pause();
}
