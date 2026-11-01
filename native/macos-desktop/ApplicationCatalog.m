#import <AppKit/AppKit.h>
#import <Security/Security.h>
#include <stdlib.h>

static NSURL *CanonicalURL(NSURL *url) {
    if (!url) return nil;
    char *resolved = realpath(url.fileSystemRepresentation, NULL);
    if (!resolved) return nil;
    NSString *path = [NSFileManager.defaultManager stringWithFileSystemRepresentation:resolved length:strlen(resolved)];
    free(resolved);
    return [NSURL fileURLWithPath:path];
}

static NSDictionary *Application(NSURL *url) {
    url = CanonicalURL(url);
    if (!url) return nil;
    NSBundle *bundle = [NSBundle bundleWithURL:url];
    NSURL *executable = CanonicalURL(bundle.executableURL);
    NSString *identifier = bundle.bundleIdentifier;
    if (!bundle || !executable || !identifier.length || ![NSFileManager.defaultManager isExecutableFileAtPath:executable.path]) return nil;
    // Use filesystem canonical paths on both sides: Cryptex aliases (Safari) can otherwise
    // resolve differently through Foundation and NSBundle. Nonconventional wrappers
    // such as iOS-on-Mac bundles are outside this initial catalog; do not invent executable identities.
    if (![executable.path hasPrefix:[url.path stringByAppendingString:@"/Contents/MacOS/"]]) return nil;
    NSString *name = [bundle objectForInfoDictionaryKey:@"CFBundleDisplayName"] ?: [bundle objectForInfoDictionaryKey:@"CFBundleName"];
    if (![name isKindOfClass:NSString.class] || !name.length) name = url.lastPathComponent.stringByDeletingPathExtension;
    SecStaticCodeRef code = NULL;
    CFDictionaryRef info = NULL;
    BOOL valid = NO;
    NSString *team = @"", *signingIdentifier = @"";
    if (SecStaticCodeCreateWithPath((__bridge CFURLRef)url, kSecCSDefaultFlags, &code) == errSecSuccess) {
        valid = SecStaticCodeCheckValidity(code, kSecCSBasicValidateOnly, NULL) == errSecSuccess;
        if (valid && SecCodeCopySigningInformation(code, kSecCSSigningInformation, &info) == errSecSuccess) {
            NSDictionary *metadata = (__bridge NSDictionary *)info;
            team = metadata[(__bridge NSString *)kSecCodeInfoTeamIdentifier] ?: @"";
            signingIdentifier = metadata[(__bridge NSString *)kSecCodeInfoIdentifier] ?: @"";
        }
    }
    if (info) CFRelease(info);
    if (code) CFRelease(code);
    // Paths identify catalog entries only. Signing metadata is observational, not routing authority.
    return @{@"name": name, @"bundlePath": url.path,
        @"executablePath": executable.path, @"bundleIdentifier": identifier,
        @"teamIdentifier": team, @"signingIdentifier": signingIdentifier, @"signatureValid": @(valid)};
}

// A browser's network owner may be a nested helper rather than its main executable.
// Discover candidates only; each returned exact path still requires explicit selection.
static void AddApplicationFamilyLimited(NSURL *url, NSMutableDictionary *entries, NSMutableArray *warnings, NSUInteger limit) {
    NSDictionary *main = Application(url);
    if (!main) return;
    entries[main[@"bundlePath"]] = main;
    NSURL *root = [NSURL fileURLWithPath:main[@"bundlePath"]];
    NSString *prefix = [root.path stringByAppendingString:@"/"];
    NSURL *contents = [root URLByAppendingPathComponent:@"Contents"];
    NSSet *helperDirectories = [NSSet setWithArray:@[@"Frameworks", @"Library", @"Helpers", @"PlugIns", @"XPCServices", @"Applications", @"MacOS"]];
    NSDirectoryEnumerator *enumerator = [NSFileManager.defaultManager enumeratorAtURL:contents
        includingPropertiesForKeys:@[NSURLIsSymbolicLinkKey, NSURLIsDirectoryKey] options:NSDirectoryEnumerationSkipsHiddenFiles
        errorHandler:^BOOL(NSURL *ignored, NSError *error) {
            (void)ignored; (void)error;
            if (!warnings.count) [warnings addObject:@"Some application helpers could not be read"];
            return YES;
        }];
    NSUInteger visited = 0;
    for (NSURL *candidate in enumerator) {
        if (++visited > limit || entries.count > 4096) {
            if (!warnings.count) [warnings addObject:@"Application helper inventory exceeds its size limit"];
            break;
        }
        NSNumber *symlink = nil;
        if (![candidate getResourceValue:&symlink forKey:NSURLIsSymbolicLinkKey error:NULL]) {
            if (!warnings.count) [warnings addObject:@"Some application helpers could not be read"];
            break;
        }
        // URL enumeration does not follow symbolic-link directories. Calling skipDescendants
        // on a link/file can skip the containing directory's remaining siblings.
        if (symlink.boolValue) continue;
        // Limit discovery to conventional executable locations rather than walking developer
        // toolchains, bundled source trees and other application data.
        BOOL unsupportedRoot = [candidate.URLByDeletingLastPathComponent.path isEqual:contents.path] &&
            ![helperDirectories containsObject:candidate.lastPathComponent];
        if (unsupportedRoot || [candidate.lastPathComponent isEqual:@"Resources"] || [candidate.pathExtension isEqual:@"lproj"]) {
            NSNumber *directory = nil;
            if ([candidate getResourceValue:&directory forKey:NSURLIsDirectoryKey error:NULL] && directory.boolValue)
                [enumerator skipDescendants];
            continue;
        }
        if (![candidate.pathExtension isEqual:@"app"]) continue;
        NSDictionary *helper = Application(candidate);
        if (!helper || ![helper[@"bundlePath"] hasPrefix:prefix] || entries[helper[@"bundlePath"]]) continue;
        NSMutableDictionary *labelled = [helper mutableCopy];
        labelled[@"name"] = [NSString stringWithFormat:@"%@ — %@", main[@"name"], helper[@"name"]];
        entries[helper[@"bundlePath"]] = labelled;
    }
}

static void AddApplicationFamily(NSURL *url, NSMutableDictionary *entries, NSMutableArray *warnings) {
    AddApplicationFamilyLimited(url, entries, warnings, 16384);
}

static void AddRootApplicationAliases(NSURL *root, NSMutableDictionary *entries, NSMutableArray *warnings) {
    // macOS marks Safari's top-level Cryptex alias hidden. Include app aliases even when
    // their filesystem hidden flag would omit them from the ordinary recursive inventory.
    NSArray *children = [NSFileManager.defaultManager contentsOfDirectoryAtURL:root
        includingPropertiesForKeys:@[NSURLIsSymbolicLinkKey] options:0 error:NULL];
    for (NSURL *url in children) {
        if (![url.pathExtension isEqual:@"app"]) continue;
        NSNumber *symlink = nil;
        if ([url getResourceValue:&symlink forKey:NSURLIsSymbolicLinkKey error:NULL] && symlink.boolValue)
            AddApplicationFamily(url, entries, warnings);
        if (entries.count > 4096) break;
    }
}

__attribute__((visibility("default"))) char *perroute_catalog_request(const char *operation) {
    @autoreleasepool {
        NSMutableDictionary *entries = [NSMutableDictionary dictionary];
        NSMutableArray *warnings = [NSMutableArray array];
        NSString *op = operation ? [NSString stringWithUTF8String:operation] : nil;
        if ([op isEqual:@"installed"]) {
            NSArray *roots = @[@"/Applications", @"/System/Applications", [NSHomeDirectory() stringByAppendingPathComponent:@"Applications"]];
            for (NSString *root in roots) {
                if (![NSFileManager.defaultManager fileExistsAtPath:root]) continue;
                AddRootApplicationAliases([NSURL fileURLWithPath:root], entries, warnings);
                if (entries.count > 4096) break;
                NSDirectoryEnumerator *enumerator = [NSFileManager.defaultManager enumeratorAtURL:[NSURL fileURLWithPath:root]
                    includingPropertiesForKeys:nil options:NSDirectoryEnumerationSkipsHiddenFiles | NSDirectoryEnumerationSkipsPackageDescendants
                    errorHandler:^BOOL(NSURL *url, NSError *error) { (void)url; (void)error;
                        if (!warnings.count) [warnings addObject:@"Some application folders could not be read"];
                        return YES;
                    }];
                for (NSURL *url in enumerator) {
                    if (![url.pathExtension isEqual:@"app"]) continue;
                    AddApplicationFamily(url, entries, warnings);
                    if (entries.count > 4096) break;
                }
                if (entries.count > 4096) break;
            }
        } else if ([op isEqual:@"running"]) {
            // AppKit's regular apps are a catalog, not arbitrary process/helper attribution.
            for (NSRunningApplication *running in NSWorkspace.sharedWorkspace.runningApplications) {
                if (running.activationPolicy != NSApplicationActivationPolicyRegular || !running.bundleURL) continue;
                NSDictionary *app = Application(running.bundleURL);
                if (app) entries[app[@"bundlePath"]] = app;
                if (entries.count > 4096) break;
            }
        }
        NSDictionary *result;
        if (!([op isEqual:@"installed"] || [op isEqual:@"running"])) result = @{@"error": @"Unsupported catalog operation"};
        else if (entries.count > 4096) result = @{@"error": @"Application catalog exceeds its size limit"};
        else result = @{@"version": @1, @"applications": entries.allValues, @"warnings": warnings};
        NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:NULL];
        return data ? strndup(data.bytes, data.length) : NULL;
    }
}
__attribute__((visibility("default"))) void perroute_catalog_free(void *value) { free(value); }
