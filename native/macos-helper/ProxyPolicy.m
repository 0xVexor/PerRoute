#import "ProxyHelper.h"
#import <Security/Security.h>
#import <unistd.h>

NSError *PRError(NSString *message) {
    return [NSError errorWithDomain:@"app.perroute.proxy-helper" code:1 userInfo:@{NSLocalizedDescriptionKey: message}];
}

NSXPCInterface *PRHelperInterface(void) {
    NSXPCInterface *interface = [NSXPCInterface interfaceWithProtocol:@protocol(PRProxyHelper)];
    NSSet *classes = [NSSet setWithObjects:NSDictionary.class, NSArray.class, NSString.class, NSNumber.class, nil];
    [interface setClasses:classes forSelector:@selector(request:reply:) argumentIndex:0 ofReply:NO];
    [interface setClasses:classes forSelector:@selector(request:reply:) argumentIndex:0 ofReply:YES];
    return interface;
}

BOOL PRSignedSelf(NSString *identifier) {
    SecCodeRef code = NULL;
    SecRequirementRef requirement = NULL;
    NSString *identity = PRRequirement(identifier, @"local");
    BOOL ok = identity && SecRequirementCreateWithString((__bridge CFStringRef)identity, kSecCSDefaultFlags, &requirement) == errSecSuccess &&
        SecCodeCopySelf(kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecCodeCheckValidity(code, kSecCSStrictValidate, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return ok;
}

NSString *PRSigningTeam(void) {
#ifdef PR_LOCAL_CERT_SHA1
    return PRSignedSelf(PRAppIdentifier) || PRSignedSelf(PRHelperLabel) ? @"local" : nil;
#else
    SecCodeRef code = NULL;
    CFDictionaryRef info = NULL;
    NSString *team = nil;
    if (SecCodeCopySelf(kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecCodeCopySigningInformation(code, kSecCSSigningInformation, &info) == errSecSuccess) {
        team = [(__bridge NSDictionary *)info objectForKey:(__bridge NSString *)kSecCodeInfoTeamIdentifier];
        if (![team isKindOfClass:NSString.class] || team.length != 10 ||
            [team rangeOfCharacterFromSet:[[NSCharacterSet characterSetWithCharactersInString:@"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"] invertedSet]].location != NSNotFound) team = nil;
    }
    if (info) CFRelease(info);
    if (code) CFRelease(code);
    return team;
#endif
}

NSString *PRRequirement(NSString *identifier, NSString *team) {
#ifdef PR_LOCAL_CERT_SHA1
    // Local builds pin the persistent signing certificate at compile time. No request may choose it.
    NSString *pin = @PR_LOCAL_CERT_SHA1;
    if (pin.length != 40 || [pin rangeOfCharacterFromSet:[[NSCharacterSet characterSetWithCharactersInString:@"0123456789abcdef"] invertedSet]].location != NSNotFound ||
        ![team isEqual:@"local"] || (![identifier isEqual:PRAppIdentifier] && ![identifier isEqual:PRHelperLabel] && ![identifier isEqual:@"app.perroute.proxy-installer"] && ![identifier isEqual:@"app.perroute.tun-engine.safari-v1"] && ![identifier isEqual:PRTunEngineIdentifier] && ![identifier isEqual:PRXrayIdentifier])) return nil;
    return [NSString stringWithFormat:@"identifier \"%@\" and certificate leaf = H\"%@\"", identifier, pin];
#else
    // Never substitute ad-hoc identities or a caller-supplied requirement for this trust boundary.
    return [NSString stringWithFormat:@"anchor apple generic and identifier \"%@\" and certificate leaf[subject.OU] = \"%@\"", identifier, team];
#endif
}

NSDictionary *PRSessionStatus(BOOL pending, BOOL owned) {
    // Java deliberately requires JSON booleans, not numeric 0/1 boxed from a C comparison.
    return @{@"pending": pending ? @YES : @NO, @"owned": owned ? @YES : @NO,
             @"euid": @(geteuid()), @"pid": @(getpid())};
}

static BOOL Flag(NSDictionary *d, NSString *key) {
    id value = d[key];
    return !value || ([value isKindOfClass:NSNumber.class] && ([value isEqual:@0] || [value isEqual:@1]));
}

static NSDictionary *Group(NSDictionary *d, NSString *prefix) {
    NSMutableDictionary *group = [NSMutableDictionary dictionary];
    for (NSString *suffix in @[@"Enable", @"Proxy", @"Port"]) {
        NSString *key = [prefix stringByAppendingString:suffix];
        if (d[key]) group[key] = d[key];
    }
    return group;
}

static NSDictionary *SemanticGroup(NSDictionary *d, NSString *prefix) {
    NSString *enable = [prefix stringByAppendingString:@"Enable"];
    if (!Flag(d, enable)) return nil;
    if (![d[enable] boolValue]) return @{enable: @0};
    return Group(d, prefix);
}

NSDictionary *PRApplySettings(NSDictionary *original, NSInteger http, NSInteger socks, NSError **error) {
    if (![original isKindOfClass:NSDictionary.class] || http < 1024 || http > 65535 || socks < 1024 || socks > 65535 || http == socks) {
        if (error) *error = PRError(@"Invalid loopback proxy ports or settings");
        return nil;
    }
    NSMutableSet *allowed = [NSMutableSet setWithArray:@[@"ExceptionsList", @"ExcludeSimpleHostnames", @"FTPPassive", @"ProxyAutoConfigEnable", @"ProxyAutoDiscoveryEnable"]];
    for (NSString *prefix in @[@"HTTP", @"HTTPS", @"SOCKS"])
        for (NSString *suffix in @[@"Enable", @"Proxy", @"Port"]) [allowed addObject:[prefix stringByAppendingString:suffix]];
    for (id key in original) {
        if (![key isKindOfClass:NSString.class] || ![allowed containsObject:key]) goto unsupported;
    }
    for (NSString *flag in @[@"HTTPEnable", @"HTTPSEnable", @"SOCKSEnable", @"ExcludeSimpleHostnames", @"FTPPassive", @"ProxyAutoConfigEnable", @"ProxyAutoDiscoveryEnable"])
        if (!Flag(original, flag)) goto unsupported;
    if ([original[@"ProxyAutoConfigEnable"] boolValue] || [original[@"ProxyAutoDiscoveryEnable"] boolValue]) goto unsupported;
    if (original[@"ExceptionsList"]) {
        if (![original[@"ExceptionsList"] isKindOfClass:NSArray.class]) goto unsupported;
        for (id entry in original[@"ExceptionsList"])
            if (![entry isKindOfClass:NSString.class] || ![entry length]) goto unsupported;
    }
    for (NSString *prefix in @[@"HTTP", @"HTTPS", @"SOCKS"]) {
        id host = original[[prefix stringByAppendingString:@"Proxy"]];
        id port = original[[prefix stringByAppendingString:@"Port"]];
        if (host && (![host isKindOfClass:NSString.class] || ![host length])) goto unsupported;
        if (port && (![port isKindOfClass:NSNumber.class] || [port doubleValue] != [port integerValue] || [port integerValue] < 1 || [port integerValue] > 65535)) goto unsupported;
        if ([original[[prefix stringByAppendingString:@"Enable"]] boolValue] && (!host || !port)) goto unsupported;
    }
    {
        NSMutableDictionary *result = [original mutableCopy];
        for (NSString *prefix in @[@"HTTP", @"HTTPS", @"SOCKS"]) {
            result[[prefix stringByAppendingString:@"Enable"]] = @1;
            result[[prefix stringByAppendingString:@"Proxy"]] = @"127.0.0.1";
            result[[prefix stringByAppendingString:@"Port"]] = @([prefix isEqual:@"SOCKS"] ? socks : http);
        }
        return result;
    }
unsupported:
    if (error) *error = PRError(@"Unsupported proxy settings; use Local proxy or resolve the network configuration");
    return nil;
}

NSDictionary *PRRestoreSettings(NSDictionary *current, NSDictionary *original, NSDictionary *applied, BOOL *conflict) {
    NSMutableDictionary *result = [current mutableCopy];
    *conflict = NO;
    for (NSString *prefix in @[@"HTTP", @"HTTPS", @"SOCKS"]) {
        NSDictionary *before = SemanticGroup(original, prefix), *owned = SemanticGroup(applied, prefix), *now = SemanticGroup(current, prefix);
        if ([before isEqual:owned]) continue; // An already matching endpoint was never ours.
        if (!now || (![now isEqual:before] && ![now isEqual:owned])) { *conflict = YES; continue; }
        for (NSString *suffix in @[@"Enable", @"Proxy", @"Port"]) [result removeObjectForKey:[prefix stringByAppendingString:suffix]];
        [result addEntriesFromDictionary:Group(original, prefix)];
    }
    return result;
}

BOOL PRSettingsEquivalent(NSDictionary *a, NSDictionary *b) {
    NSMutableDictionary *left = [a mutableCopy], *right = [b mutableCopy];
    for (NSString *prefix in @[@"HTTP", @"HTTPS", @"SOCKS"]) {
        NSDictionary *l = SemanticGroup(a, prefix), *r = SemanticGroup(b, prefix);
        if (!l || !r || ![l isEqual:r]) return NO;
        for (NSString *suffix in @[@"Enable", @"Proxy", @"Port"]) {
            [left removeObjectForKey:[prefix stringByAppendingString:suffix]];
            [right removeObjectForKey:[prefix stringByAppendingString:suffix]];
        }
    }
    return [left isEqual:right];
}

BOOL PREffectiveEquivalent(NSDictionary *desired, NSDictionary *observed) {
    if (![observed isKindOfClass:NSDictionary.class]) return NO;
    for (NSString *prefix in @[@"HTTP", @"HTTPS", @"SOCKS"]) {
        NSDictionary *left = SemanticGroup(desired, prefix), *right = SemanticGroup(observed, prefix);
        if (!left || !right || ![left isEqual:right]) return NO;
    }
    for (NSString *key in @[@"ExcludeSimpleHostnames", @"ProxyAutoConfigEnable", @"ProxyAutoDiscoveryEnable"]) {
        if (!Flag(desired, key) || !Flag(observed, key) || [desired[key] boolValue] != [observed[key] boolValue]) return NO;
    }
    return [(desired[@"ExceptionsList"] ?: @[]) isEqual:(observed[@"ExceptionsList"] ?: @[])];
}
