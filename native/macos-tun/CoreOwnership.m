#import "CoreOwnership.h"
#import "TunStore.h"
#import <Security/Security.h>
#import <arpa/inet.h>
#import <libproc.h>
#import <sys/proc_info.h>

static id Reject(NSError **error) {
    if (error) *error = PRTunError(@"The expected signed routing process no longer owns every loopback endpoint");
    return nil;
}
static NSDictionary *Identity(pid_t pid, uid_t user, NSArray *paths) {
    struct proc_bsdinfo info = {0};
    char path[PROC_PIDPATHINFO_MAXSIZE];
    if (pid < 2 || !user || proc_pidinfo(pid,PROC_PIDTBSDINFO,0,&info,sizeof(info)) != sizeof(info) ||
        info.pbi_pid != (unsigned)pid || info.pbi_uid != user || !info.pbi_start_tvsec ||
        proc_pidpath(pid,path,sizeof(path)) <= 0) return nil;
    NSString *executable = [NSString stringWithUTF8String:path];
    if (![paths containsObject:executable]) return nil;
    return @{@"pid":@(pid),@"uid":@(user),@"seconds":@(info.pbi_start_tvsec),
        @"microseconds":@(info.pbi_start_tvusec),@"path":executable};
}
static BOOL Signed(pid_t pid, NSString *text) {
    if (!text.length) return NO;
    SecCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    NSDictionary *attributes = @{(__bridge NSString *)kSecGuestAttributePid:@(pid)};
    BOOL valid = SecCodeCopyGuestWithAttributes(NULL,(__bridge CFDictionaryRef)attributes,kSecCSDefaultFlags,&code) == errSecSuccess &&
        SecRequirementCreateWithString((__bridge CFStringRef)text,kSecCSDefaultFlags,&requirement) == errSecSuccess &&
        SecCodeCheckValidity(code,kSecCSStrictValidate,requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return valid;
}
NSDictionary *PRTunObserveCore(pid_t pid, uid_t user, NSArray<NSString *> *allowedPaths,
    NSString *requirement, NSSet<NSNumber *> *ports, NSError **error) {
    NSDictionary *before = Identity(pid,user,allowedPaths);
    if (!before || !ports.count || ports.count > 256 || !Signed(pid,requirement)) return Reject(error);
    for (id port in ports) if (![port isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)port) == CFBooleanGetTypeID() ||
        [port doubleValue] != [port integerValue] || [port integerValue] < 1 || [port integerValue] > 65535) return Reject(error);
    int size = proc_pidinfo(pid,PROC_PIDLISTFDS,0,NULL,0);
    if (size <= 0 || size > 1048576-4096) return Reject(error);
    size += 4096;
    struct proc_fdinfo *descriptors = malloc(size);
    if (!descriptors) return Reject(error);
    int length = proc_pidinfo(pid,PROC_PIDLISTFDS,0,descriptors,size);
    BOOL valid = length > 0 && length < size && !(length % sizeof(*descriptors));
    NSMutableSet *observed = [NSMutableSet set];
    for (NSUInteger i=0; valid && i<(NSUInteger)length/sizeof(*descriptors); i++) {
        if (descriptors[i].proc_fdtype != PROX_FDTYPE_SOCKET) continue;
        struct socket_fdinfo socket = {0};
        if (proc_pidfdinfo(pid,descriptors[i].proc_fd,PROC_PIDFDSOCKETINFO,&socket,sizeof(socket)) != sizeof(socket)) continue;
        if (socket.psi.soi_kind != SOCKINFO_TCP || socket.psi.soi_proto.pri_tcp.tcpsi_state != TSI_S_LISTEN) continue;
        struct in_sockinfo *listener = &socket.psi.soi_proto.pri_tcp.tcpsi_ini;
        // Darwin may report an AF_INET6 socket with INI_IPV4 and the IPv4 union
        // address for a mapped bind. The address flags select the union member.
        // Accept only 127.0.0.1, never ::1, wildcard or other loopback addresses.
        const struct in6_addr *v6 = &listener->insi_laddr.ina_6;
        BOOL v4 = (socket.psi.soi_family == AF_INET || socket.psi.soi_family == AF_INET6) &&
            (listener->insi_vflag & INI_IPV4) &&
            listener->insi_laddr.ina_46.i46a_addr4.s_addr == htonl(INADDR_LOOPBACK);
        BOOL mapped = socket.psi.soi_family == AF_INET6 && (listener->insi_vflag & INI_IPV6) && IN6_IS_ADDR_V4MAPPED(v6) &&
            v6->s6_addr[12] == 127 && v6->s6_addr[13] == 0 && v6->s6_addr[14] == 0 && v6->s6_addr[15] == 1;
        if (!v4 && !mapped) continue;
        [observed addObject:@(ntohs((uint16_t)listener->insi_lport))];
    }
    free(descriptors);
    if (!valid || ![ports isSubsetOfSet:observed] || (![before isEqual:Identity(pid,user,allowedPaths)] || !Signed(pid,requirement))) return Reject(error);
    NSMutableDictionary *result = [before mutableCopy];
    result[@"ports"] = [[ports allObjects] sortedArrayUsingSelector:@selector(compare:)];
    return [result copy];
}
BOOL PRTunCoreStillOwned(NSDictionary *identity, NSString *requirement, NSError **error) {
    if (![identity isKindOfClass:NSDictionary.class] || ![identity[@"path"] isKindOfClass:NSString.class] ||
        ![identity[@"ports"] isKindOfClass:NSArray.class]) return Reject(error) != nil;
    NSDictionary *observed = PRTunObserveCore([identity[@"pid"] intValue],[identity[@"uid"] unsignedIntValue],
        @[identity[@"path"]],[requirement copy],[NSSet setWithArray:identity[@"ports"]],error);
    if (!observed) return NO;
    if (![observed isEqual:identity]) { Reject(error); return NO; }
    return YES;
}

NSDictionary *PRTunObserveForwardingChain(pid_t gatewayPid, pid_t corePid, uid_t user,
    NSArray<NSString *> *gatewayPaths, NSArray<NSString *> *corePaths,
    NSString *gatewayRequirement, NSString *coreRequirement,
    NSSet<NSNumber *> *gatewayPorts, NSSet<NSNumber *> *upstreamPorts, NSError **error) {
    if (gatewayPid == corePid || gatewayPorts.count != upstreamPorts.count ||
        [gatewayPorts intersectsSet:upstreamPorts]) return Reject(error);
    NSDictionary *gateway = PRTunObserveCore(gatewayPid,user,gatewayPaths,gatewayRequirement,gatewayPorts,error);
    if (!gateway) return nil;
    NSDictionary *core = PRTunObserveCore(corePid,user,corePaths,coreRequirement,upstreamPorts,error);
    if (!core) return nil;
    NSDictionary *chain = @{@"gateway":gateway,@"core":core};
    return PRTunForwardingStillOwned(chain,gatewayRequirement,coreRequirement,error) ? chain : nil;
}
BOOL PRTunForwardingStillOwned(NSDictionary *chain, NSString *gatewayRequirement,
    NSString *coreRequirement, NSError **error) {
    if (![chain isKindOfClass:NSDictionary.class]) return Reject(error) != nil;
    return PRTunCoreStillOwned(chain[@"gateway"],gatewayRequirement,error) &&
        PRTunCoreStillOwned(chain[@"core"],coreRequirement,error) &&
        PRTunCoreStillOwned(chain[@"gateway"],gatewayRequirement,error);
}
