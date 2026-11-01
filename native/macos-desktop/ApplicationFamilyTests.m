#import "ApplicationCatalog.m"
#include <sys/stat.h>

static NSUInteger Checks;
static void Check(BOOL value, NSString *message) {
    Checks++;
    if (!value) @throw [NSException exceptionWithName:@"FixtureAssertion" reason:message userInfo:nil];
}
static NSURL *Bundle(NSURL *root, NSString *relative, NSString *name) {
    NSURL *url = [root URLByAppendingPathComponent:relative];
    NSURL *contents = [url URLByAppendingPathComponent:@"Contents"];
    NSURL *binary = [contents URLByAppendingPathComponent:@"MacOS/Fixture"];
    [NSFileManager.defaultManager createDirectoryAtURL:[binary URLByDeletingLastPathComponent]
        withIntermediateDirectories:YES attributes:nil error:NULL];
    [@"fixture, never executed" writeToURL:binary atomically:YES encoding:NSUTF8StringEncoding error:NULL];
    chmod(binary.fileSystemRepresentation, 0700);
    [@{@"CFBundleExecutable": @"Fixture", @"CFBundleIdentifier": @"example.fixture",
        @"CFBundleName": name, @"CFBundlePackageType": @"APPL"}
        writeToURL:[contents URLByAppendingPathComponent:@"Info.plist"] atomically:YES];
    return url;
}
int main(void) {
    @autoreleasepool {
        NSString *temporary = [NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString];
        [NSFileManager.defaultManager createDirectoryAtPath:temporary withIntermediateDirectories:YES attributes:nil error:NULL];
        NSURL *root = CanonicalURL([NSURL fileURLWithPath:temporary]);
        @try {
            NSURL *main = Bundle(root, @"Browser.app", @"Browser");
            NSURL *helper = Bundle(main, @"Contents/Frameworks/Engine.framework/Versions/A/Helpers/Network.app", @"Network Helper");
            Bundle(main, @"Contents/Library/LoginItems/Login.app", @"Login Helper");
            NSURL *resource = Bundle(main, @"Contents/Resources/Data.app", @"Resource Fixture");
            NSURL *outside = Bundle(root, @"Outside.app", @"Outside");
            NSString *alias = [main.path stringByAppendingPathComponent:@"Contents/Frameworks/Alias.app"];
            [NSFileManager.defaultManager createSymbolicLinkAtPath:alias withDestinationPath:outside.path error:NULL];
            NSString *mainAlias = [root.path stringByAppendingPathComponent:@"BrowserAlias.app"];
            [NSFileManager.defaultManager createSymbolicLinkAtPath:mainAlias withDestinationPath:main.path error:NULL];
            Check([Application([NSURL fileURLWithPath:mainAlias])[@"bundlePath"] isEqual:CanonicalURL(main).path],
                @"Top-level application aliases resolve to the executable's filesystem namespace");
            NSMutableDictionary *entries = [NSMutableDictionary dictionary];
            NSMutableArray *warnings = [NSMutableArray array];
            AddApplicationFamily(main, entries, warnings);
            Check(entries.count == 3, @"Main and two contained helper bundles discovered");
            Check([entries[helper.path][@"name"] isEqual:@"Browser — Network Helper"], @"Helper labelled with owner");
            Check([entries[helper.path][@"executablePath"] isEqual:[helper.path stringByAppendingPathComponent:@"Contents/MacOS/Fixture"]], @"Exact helper executable retained");
            Check(!entries[outside.path], @"Outside symlink target excluded");
            Check(!entries[resource.path], @"Resources are not executable helper discovery roots");
            Check(warnings.count == 0, @"Complete fixture has no warning");
            AddApplicationFamily(main, entries, warnings);
            Check(entries.count == 3, @"Repeated family deduplicated");
            [[NSURL fileURLWithPath:mainAlias] setResourceValue:@YES forKey:NSURLIsHiddenKey error:NULL];
            NSMutableDictionary *aliases = [NSMutableDictionary dictionary];
            AddRootApplicationAliases(root, aliases, warnings);
            Check(aliases.count == 3, @"Hidden root app alias discovers the canonical application family");
            AddRootApplicationAliases(root, entries, warnings);
            Check(entries.count == 3, @"Root aliases deduplicate existing application families");
            NSMutableDictionary *limited = [NSMutableDictionary dictionary];
            NSMutableArray *limitedWarnings = [NSMutableArray array];
            AddApplicationFamilyLimited(main, limited, limitedWarnings, 1);
            Check(limitedWarnings.count == 1, @"Traversal exhaustion is explicit");
            Check(limited.count == 1, @"Exhausted traversal preserves only observed main");
            NSURL *escaped = Bundle(main, @"Contents/Helpers/Escaped.app", @"Escaped Helper");
            NSString *escapedExecutable = [escaped.path stringByAppendingPathComponent:@"Contents/MacOS/Fixture"];
            [NSFileManager.defaultManager removeItemAtPath:escapedExecutable error:NULL];
            [NSFileManager.defaultManager createSymbolicLinkAtPath:escapedExecutable
                withDestinationPath:[outside.path stringByAppendingPathComponent:@"Contents/MacOS/Fixture"] error:NULL];
            AddApplicationFamily(main, entries, warnings);
            Check(!entries[escaped.path], @"Helper executable escaping its bundle excluded");
        } @finally {
            [NSFileManager.defaultManager removeItemAtURL:root error:NULL];
        }
        printf("Application family: %lu checks passed; fixture executables never launched\n", (unsigned long)Checks);
    }
    return 0;
}
