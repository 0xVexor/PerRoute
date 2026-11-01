#import <Foundation/Foundation.h>

// Preparation only. The native supervisor supplies its own verified engine
// paths; they are never accepted from the wire. This API performs no file/process/network I/O.
NSData *PRTunPrepareConfiguration(NSData *request, NSArray<NSString *> *trustedEngineExecutables, NSError **error);

// Native authenticated caller only, after payload, endpoint and conflict validation.
// This compiler still performs no I/O; only its caller can stage and activate it.
NSData *PRTunActiveConfiguration(NSData *request, NSArray<NSString *> *trustedEngineExecutables, uid_t user, NSError **error);

// Bounded distinct Xray listeners, separate from every authenticated JVM gateway port.
NSSet<NSNumber *> *PRTunUpstreamPorts(id values, NSSet<NSNumber *> *gatewayPorts, NSError **error);
