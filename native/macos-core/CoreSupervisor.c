#include <sys/event.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>

static int reap(pid_t child, int *status) {
    pid_t result;
    do { result = waitpid(child,status,WNOHANG); } while (result < 0 && errno == EINTR);
    return result == child || (result < 0 && errno == ECHILD);
}
static void stop(pid_t child) {
    int status = 0;
    if (reap(child,&status)) return;
    // The supervisor alone reaps its child. Its PID cannot be reused while held
    // here, even if it exits between the observation and the signal.
    kill(child,SIGTERM);
    for (unsigned i=0;i<100;i++) {
        if (reap(child,&status)) return;
        struct timespec delay = {0,20000000}; nanosleep(&delay,NULL);
    }
    kill(child,SIGKILL);
    while (waitpid(child,&status,0) < 0 && errno == EINTR) { }
}
static int observe(int queue, uintptr_t pid, int16_t filter, uint32_t flags) {
    struct kevent event;
    EV_SET(&event,pid,filter,EV_ADD|EV_ENABLE,flags,0,NULL);
    return kevent(queue,&event,1,NULL,0,NULL);
}
int main(int argc, char **argv) {
    if (argc < 2 || geteuid() == 0 || getuid() != geteuid()) return 78;
    pid_t parent = getppid();
    if (parent <= 1) return 78;
    int queue = kqueue(), gate[2];
    if (queue < 0) return 71;
    fcntl(queue,F_SETFD,FD_CLOEXEC);
    signal(SIGTERM,SIG_IGN); signal(SIGINT,SIG_IGN); signal(SIGPIPE,SIG_IGN);
    if (observe(queue,parent,EVFILT_PROC,NOTE_EXIT) ||
        observe(queue,SIGTERM,EVFILT_SIGNAL,0) || observe(queue,SIGINT,EVFILT_SIGNAL,0) ||
        getppid() != parent || pipe(gate)) { close(queue); return 71; }
    signal(SIGCHLD,SIG_DFL);
    pid_t child = fork();
    if (child < 0) { close(queue); close(gate[0]); close(gate[1]); return 71; }
    if (!child) {
        close(queue); close(gate[1]);
        char ready;
        ssize_t count;
        do { count = read(gate[0],&ready,1); } while (count < 0 && errno == EINTR);
        close(gate[0]);
        if (count != 1) _exit(71);
        signal(SIGTERM,SIG_DFL); signal(SIGINT,SIG_DFL); signal(SIGPIPE,SIG_DFL);
        execv(argv[1],argv+1);
        _exit(127);
    }
    close(gate[0]);
    if (observe(queue,child,EVFILT_PROC,NOTE_EXIT) || getppid() != parent) {
        close(gate[1]); stop(child); close(queue); return 71;
    }
    // Release execution only after the ownership handshake precedes all Xray output.
    if (printf("PRCORE1 %d\n",child) < 0 || fflush(stdout) || write(gate[1],"1",1) != 1) {
        close(gate[1]); stop(child); close(queue); return 71;
    }
    close(gate[1]);
    for (;;) {
        int status = 0;
        if (reap(child,&status)) { close(queue); return WIFEXITED(status) ? WEXITSTATUS(status) : 128+WTERMSIG(status); }
        struct kevent event;
        struct timespec timeout = {1,0};
        int count = kevent(queue,NULL,0,&event,1,&timeout);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 || getppid() != parent || (count && event.filter == EVFILT_SIGNAL) ||
            (count && event.filter == EVFILT_PROC && event.ident == (uintptr_t)parent)) {
            stop(child); close(queue); return 0;
        }
    }
}
