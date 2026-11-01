#import <Foundation/Foundation.h>
#import <sys/types.h>

// Native-derived paths and signing requirement only; never supplied by an IPC caller.
NSDictionary *PRTunObserveCore(pid_t pid, uid_t user, NSArray<NSString *> *allowedPaths,
    NSString *requirement, NSSet<NSNumber *> *ports, NSError **error);
BOOL PRTunCoreStillOwned(NSDictionary *identity, NSString *requirement, NSError **error);

// Native supplies the authenticated peer PID and fixed payload trust requirements.
// The client cannot nominate a different gateway process or executable.
NSDictionary *PRTunObserveForwardingChain(pid_t gatewayPid, pid_t corePid, uid_t user,
    NSArray<NSString *> *gatewayPaths, NSArray<NSString *> *corePaths,
    NSString *gatewayRequirement, NSString *coreRequirement,
    NSSet<NSNumber *> *gatewayPorts, NSSet<NSNumber *> *upstreamPorts, NSError **error);
BOOL PRTunForwardingStillOwned(NSDictionary *chain, NSString *gatewayRequirement,
    NSString *coreRequirement, NSError **error);
