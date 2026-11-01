#import "Identity.h"
#import "SigningPin.h"
#import <unistd.h>

NSString *PRProbeRequirement(NSString *identifier, NSString *fingerprint) {
    // Pin a certificate, not a self-asserted Team ID or a caller-provided requirement.
    NSCharacterSet *hex = [NSCharacterSet characterSetWithCharactersInString:@"0123456789abcdefABCDEF"];
    if (![fingerprint isKindOfClass:NSString.class] || fingerprint.length != 40 ||
        [fingerprint rangeOfCharacterFromSet:hex.invertedSet].location != NSNotFound ||
        (![identifier isEqual:PRProbeApp] && ![identifier isEqual:PRProbeService])) return nil;
    // Apple's certificate requirement grammar uses the certificate's SHA-1 fingerprint.
    return [NSString stringWithFormat:@"identifier \"%@\" and certificate leaf = H\"%@\"", identifier, fingerprint];
}

static SecRequirementRef Requirement(NSString *identifier) {
    NSString *text = PRProbeRequirement(identifier, @PR_PROBE_CERT_SHA1);
    SecRequirementRef requirement = NULL;
    if (text) SecRequirementCreateWithString((__bridge CFStringRef)text, kSecCSDefaultFlags, &requirement);
    return requirement;
}

BOOL PRProbeCheckSelf(NSString *identifier) {
    SecCodeRef code = NULL;
    SecRequirementRef requirement = Requirement(identifier);
    BOOL ok = requirement && SecCodeCopySelf(kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecCodeCheckValidity(code, kSecCSStrictValidate, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return ok;
}

BOOL PRProbeCheckFile(NSURL *url, NSString *identifier) {
    SecStaticCodeRef code = NULL;
    SecRequirementRef requirement = Requirement(identifier);
    BOOL ok = requirement && SecStaticCodeCreateWithPath((__bridge CFURLRef)url, kSecCSDefaultFlags, &code) == errSecSuccess &&
        SecStaticCodeCheckValidity(code, kSecCSStrictValidate, requirement) == errSecSuccess;
    if (code) CFRelease(code);
    if (requirement) CFRelease(requirement);
    return ok;
}

NSDictionary *PRProbeReply(NSDictionary *request, uid_t caller, uid_t console) {
    if (console == 0 || caller != console) return @{@"ok": @NO, @"error": @"Inactive console user"};
    if (![request isKindOfClass:NSDictionary.class] || request.count != 2 ||
        ![request[@"version"] isEqual:@1] || ![request[@"operation"] isEqual:@"status"])
        return @{@"ok": @NO, @"error": @"This probe supports only status; proxy mutation is unavailable"};
    return @{@"ok": @YES, @"version": @1, @"readOnly": @YES, @"euid": @(geteuid()), @"pid": @(getpid())};
}

void PRProbePrint(NSDictionary *value) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:NSJSONWritingSortedKeys error:NULL];
    if (data) printf("%.*s\n", (int)data.length, (const char *)data.bytes);
}
