#import "TunPolicy.h"
#import "TunCapture.h"
#import <CoreFoundation/CoreFoundation.h>

static id Reject(NSError **error) {
    if (error) *error = [NSError errorWithDomain:@"PerRouteTunPolicy" code:1
        userInfo:@{NSLocalizedDescriptionKey: @"Unsupported or invalid TUN preparation request"}];
    return nil;
}

static BOOL Keys(NSDictionary *dictionary, NSArray *keys) {
    return [dictionary isKindOfClass:NSDictionary.class] &&
        [[NSSet setWithArray:dictionary.allKeys] isEqualToSet:[NSSet setWithArray:keys]];
}

static BOOL Integer(id value, NSInteger minimum, NSInteger maximum) {
    return [value isKindOfClass:NSNumber.class] &&
        CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID() &&
        [value doubleValue] == [value integerValue] &&
        [value integerValue] >= minimum && [value integerValue] <= maximum;
}

static NSArray *SortedPaths(NSArray *paths) {
    return [paths sortedArrayUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        return [left compare:right options:NSLiteralSearch];
    }];
}

static NSString *Executable(id value) {
    if (![value isKindOfClass:NSString.class]) return nil;
    NSString *path = [value copy];
    if (path.length > 8192 || ![path hasPrefix:@"/"] || [path hasSuffix:@"/"] ||
        [path containsString:@"\\"]) return nil;
    for (NSUInteger i = 0; i < path.length; i++) {
        unichar c = [path characterAtIndex:i];
        if (c <= 31 || (c >= 127 && c <= 159)) return nil;
    }
    NSArray *parts = [[path substringFromIndex:1] componentsSeparatedByString:@"/"];
    for (NSString *part in parts)
        if (!part.length || [part isEqual:@"."] || [part isEqual:@".."]) return nil;
    return path;
}

NSData *PRTunPrepareConfiguration(NSData *request, NSArray<NSString *> *trustedEngineExecutables, NSError **error) {
    if (error) *error = nil;
    if (![request isKindOfClass:NSData.class] || !request.length || request.length > 65536 ||
        ![trustedEngineExecutables isKindOfClass:NSArray.class] ||
        !trustedEngineExecutables.count || trustedEngineExecutables.count > 32) return Reject(error);
    id decoded = [NSJSONSerialization JSONObjectWithData:request options:0 error:NULL];
    if (!Keys(decoded, @[@"version", @"operation", @"bindings"])) return Reject(error);
    NSDictionary *wire = decoded;
    if (!Integer(wire[@"version"], 1, 1) || ![wire[@"operation"] isEqual:@"prepare-tun"] ||
        ![wire[@"bindings"] isKindOfClass:NSArray.class]) return Reject(error);
    NSArray *bindings = wire[@"bindings"];
    if (!bindings.count || bindings.count > 256) return Reject(error);
    NSMutableSet *excluded = [NSMutableSet set];
    for (id candidate in trustedEngineExecutables) {
        NSString *path = Executable(candidate);
        if (!path || [excluded containsObject:path]) return Reject(error);
        [excluded addObject:path];
    }
    NSMutableSet *selected = [NSMutableSet set];
    NSMutableDictionary<NSNumber *, NSMutableArray *> *groups = [NSMutableDictionary dictionary];
    for (id binding in bindings) {
        if (!Keys(binding, @[@"executable", @"socksPort"])) return Reject(error);
        NSString *path = Executable(binding[@"executable"]);
        id port = binding[@"socksPort"];
        if (!path || !Integer(port, 1, 65535) || [excluded containsObject:path] ||
            [selected containsObject:path]) return Reject(error);
        [selected addObject:path];
        NSNumber *key = @([port integerValue]);
        if (!groups[key]) groups[key] = [NSMutableArray array];
        [groups[key] addObject:path];
    }
    NSArray *exclusions = SortedPaths([excluded allObjects]);
    NSMutableArray *rules = [NSMutableArray arrayWithObject:
        @{@"process_path": exclusions, @"action": @"route", @"outbound": @"unmatched-direct"}];
    NSMutableArray *outbounds = [NSMutableArray arrayWithObject:@{@"type": @"direct", @"tag": @"unmatched-direct"}];
    for (NSNumber *port in [[groups allKeys] sortedArrayUsingSelector:@selector(compare:)]) {
        NSString *tag = [NSString stringWithFormat:@"xray-loopback-%ld", (long)port.integerValue];
        [outbounds addObject:@{@"type": @"socks", @"tag": tag, @"server": @"127.0.0.1",
            @"server_port": port, @"version": @"5"}];
        [rules addObject:@{@"inbound": @[@"perroute-tun-spike"], @"network": @"tcp",
            @"process_path": SortedPaths(groups[port]),
            @"action": @"route", @"outbound": tag}];
    }
    // No route/DNS/capture switch is caller-controlled. Actual activation, payload trust,
    // peer authentication and recovery must be implemented before exposing this through XPC.
    NSDictionary *configuration = @{@"log": @{@"disabled": @YES},
        @"inbounds": @[@{@"type": @"tun", @"tag": @"perroute-tun-spike",
            @"address": @[@"172.31.255.1/30"], @"auto_route": @NO}],
        @"outbounds": outbounds,
        @"route": @{@"auto_detect_interface": @YES, @"rules": rules, @"final": @"unmatched-direct"}};
    NSData *result = [NSJSONSerialization dataWithJSONObject:configuration options:NSJSONWritingSortedKeys error:NULL];
    return result ? [result copy] : Reject(error);
}

NSData *PRTunActiveConfiguration(NSData *request, NSArray<NSString *> *trustedEngineExecutables, uid_t user, NSError **error) {
    if (!user || user > INT_MAX) return Reject(error);
    NSData *prepared = PRTunPrepareConfiguration(request,trustedEngineExecutables,error);
    if (!prepared) return nil;
    NSMutableDictionary *configuration = [NSJSONSerialization JSONObjectWithData:prepared options:NSJSONReadingMutableContainers error:error];
    NSMutableDictionary *inbound = configuration[@"inbounds"][0];
    inbound[@"auto_route"] = @YES;
    inbound[@"interface_name"] = @PR_TUN_INTERFACE;
    // Exact executable paths may be shared by processes of several logged-in users.
    // The pinned Darwin engine supplies process UIDs; unresolved ownership stays direct.
    NSArray *rules = configuration[@"route"][@"rules"];
    for (NSUInteger i=1;i<rules.count;i++) rules[i][@"user_id"] = @[@(user)];
    return [NSJSONSerialization dataWithJSONObject:configuration options:NSJSONWritingSortedKeys error:error];
}

NSSet<NSNumber *> *PRTunUpstreamPorts(id values, NSSet<NSNumber *> *gatewayPorts, NSError **error) {
    if (![values isKindOfClass:NSArray.class] || !gatewayPorts.count || gatewayPorts.count > 256 ||
        [values count] != gatewayPorts.count) return Reject(error);
    NSMutableSet *upstream = [NSMutableSet set];
    for (id port in values) {
        if (!Integer(port,1,65535) || [upstream containsObject:port] || [gatewayPorts containsObject:port]) return Reject(error);
        [upstream addObject:port];
    }
    return [upstream copy];
}
