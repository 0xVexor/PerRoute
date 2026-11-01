#include <arpa/inet.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
int main(int argc, char **argv) {
    alarm(20);
    if (argc != 3) return 1;
    int ports[2];
    for (int i=0;i<2;i++) {
        int mapped = !strcmp(argv[2],"mapped"), v6 = mapped || !strcmp(argv[2],"v6-loopback") || !strcmp(argv[2],"v6-wildcard");
        int fd = socket(v6 ? AF_INET6 : AF_INET,SOCK_STREAM,0);
        struct sockaddr_in address = {.sin_len=sizeof(address),.sin_family=AF_INET,
            .sin_addr.s_addr=htonl(strcmp(argv[2],"wildcard") ? INADDR_LOOPBACK : INADDR_ANY)};
        struct sockaddr_in6 address6 = {.sin6_len=sizeof(address6),.sin6_family=AF_INET6};
        if (v6 && inet_pton(AF_INET6,mapped ? "::ffff:127.0.0.1" : !strcmp(argv[2],"v6-wildcard") ? "::" : "::1",&address6.sin6_addr) != 1) return 2;
        socklen_t size = v6 ? sizeof(address6) : sizeof(address);
        struct sockaddr *bindAddress = v6 ? (struct sockaddr *)&address6 : (struct sockaddr *)&address;
        if (fd < 0 || bind(fd,bindAddress,size) || listen(fd,4) || getsockname(fd,bindAddress,&size)) return 2;
        ports[i] = ntohs(v6 ? address6.sin6_port : address.sin_port);
    }
    int ready = open(argv[1],O_WRONLY|O_CREAT|O_EXCL,0600);
    char text[64]; int length = snprintf(text,sizeof(text),"%d %d",ports[0],ports[1]);
    if (ready < 0 || write(ready,text,length) != length || close(ready)) return 3;
    for (;;) pause();
}
