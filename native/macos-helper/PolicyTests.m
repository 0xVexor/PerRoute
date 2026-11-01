#import "ProxyHelper.h"
#import "ProxyJournal.h"
#include <stdio.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

static NSUInteger Checks;
static void Check(BOOL passed, NSString *message) { Checks++; if (!passed) { fprintf(stderr, "FAIL: %s\n", message.UTF8String); exit(1); } }
int main(void) {
    @autoreleasepool {
#ifdef PR_LOCAL_CERT_SHA1
        Check(PRRequirement(PRAppIdentifier, @"local") != nil, @"local application certificate requirement");
        Check(PRRequirement(PRHelperLabel, @"local") != nil, @"local helper certificate requirement");
        Check(PRRequirement(@"app.perroute.proxy-installer", @"local") != nil, @"local installer certificate requirement");
        Check(PRRequirement(@"other.application", @"local") == nil, @"foreign identifiers refused");
        Check(PRRequirement(PRAppIdentifier, @"caller-provided") == nil, @"caller identity substitution refused");
        Check(PRSigningTeam() == nil, @"ad-hoc test executable cannot select managed mode");
#endif
        Check(CFGetTypeID((__bridge CFTypeRef)PRSessionStatus(NO, NO)[@"pending"]) == CFBooleanGetTypeID(), @"idle pending serializes as boolean");
        Check(CFGetTypeID((__bridge CFTypeRef)PRSessionStatus(YES, YES)[@"pending"]) == CFBooleanGetTypeID(), @"live pending serializes as boolean");
        Check(CFGetTypeID((__bridge CFTypeRef)PRSessionStatus(YES, YES)[@"owned"]) == CFBooleanGetTypeID(), @"custody serializes as boolean");
        NSDictionary *original = @{@"ExceptionsList": @[@"localhost", @"*.local"], @"ExcludeSimpleHostnames": @1, @"FTPPassive": @1};
        NSError *error = nil;
        NSDictionary *applied = PRApplySettings(original, 17891, 17890, &error);
        Check(applied != nil && !error, @"supported settings accepted");
        Check([applied[@"HTTPProxy"] isEqual:@"127.0.0.1"] && [applied[@"HTTPSPort"] isEqual:@17891] && [applied[@"SOCKSPort"] isEqual:@17890], @"fixed loopback endpoints");
        Check([applied[@"ExceptionsList"] isEqual:original[@"ExceptionsList"]], @"bypass preserved");
        BOOL conflict;
        Check([PRRestoreSettings(applied, original, applied, &conflict) isEqual:original] && !conflict, @"restore exact original");
        Check([PRRestoreSettings(original, original, applied, &conflict) isEqual:original] && !conflict, @"idempotent recovery before any write");
        NSMutableDictionary *external = [applied mutableCopy]; external[@"HTTPPort"] = @8181; external[@"FutureKey"] = @"keep";
        NSDictionary *restored = PRRestoreSettings(external, original, applied, &conflict);
        Check(conflict && [restored[@"HTTPPort"] isEqual:@8181] && [restored[@"HTTPEnable"] isEqual:@1], @"external endpoint group preserved");
        Check(!restored[@"HTTPSProxy"] && !restored[@"SOCKSProxy"] && [restored[@"FutureKey"] isEqual:@"keep"], @"safe groups restored without deleting unrelated edits");
        Check(PRApplySettings(@{@"ProxyAutoConfigEnable": @1}, 17891, 17890, &error) == nil, @"active PAC refused");
        Check(PRApplySettings(@{@"ProxyAutoConfigURLString": @"https://example.com/proxy.pac"}, 17891, 17890, &error) == nil, @"unsupported PAC references refused");
        Check(PRApplySettings(@{@"HTTPUser": @"someone"}, 17891, 17890, &error) == nil, @"authentication refused");
        Check(PRApplySettings(@{@"FutureKey": @1}, 17891, 17890, &error) == nil, @"unknown fields refused on apply");
        Check(PRApplySettings(original, 80, 17890, &error) == nil, @"privileged ports refused");
        Check(PRApplySettings(original, 17890, 17890, &error) == nil, @"colliding ports refused");
        Check(PRApplySettings(@{@"HTTPEnable": @2}, 17891, 17890, &error) == nil, @"invalid flags refused");
        Check(PRApplySettings(@{@"HTTPEnable": @1}, 17891, 17890, &error) == nil, @"incomplete active endpoint refused");
        Check(PRApplySettings(@{@"SOCKSPort": @1.5}, 17891, 17890, &error) == nil, @"fractional port refused");
        Check(PRApplySettings(@{@"ExceptionsList": @[@1]}, 17891, 17890, &error) == nil, @"invalid bypass refused");
        NSMutableDictionary *normalized = [original mutableCopy]; normalized[@"HTTPEnable"] = @0;
        Check(PRSettingsEquivalent(original, normalized), @"disabled native enable normalization");
        Check(!PRSettingsEquivalent(applied, original), @"different active endpoints differ");
        NSMutableDictionary *preexisting = [applied mutableCopy]; preexisting[@"HTTPSPort"] = @9999;
        NSDictionary *owned = PRApplySettings(preexisting, 17891, 17890, &error);
        NSMutableDictionary *changed = [owned mutableCopy]; changed[@"HTTPPort"] = @9998;
        restored = PRRestoreSettings(changed, preexisting, owned, &conflict);
        Check(!conflict && [restored[@"HTTPPort"] isEqual:@9998] && [restored[@"HTTPSPort"] isEqual:@9999], @"matching preexisting groups are never claimed");
        Check(PREffectiveEquivalent(original, normalized), @"effective default normalization");
        Check(!PREffectiveEquivalent(applied, original), @"effective mismatch refused");
        NSMutableDictionary *noScope = [applied mutableCopy]; noScope[@"ExceptionsList"] = @[@"different"];
        Check(!PREffectiveEquivalent(applied, noScope), @"effective bypass mismatch refused");
        char temporary[] = "/tmp/perroute-helper-journal-XXXXXX";
        Check(mkdtemp(temporary) != NULL, @"private journal fixture directory");
        int directory = open(temporary, O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
        error = nil;
        PRRootJournal *journal = [[PRRootJournal alloc] initWithDirectoryDescriptor:directory error:&error];
        Check(journal && !error, @"private directory accepted");
        Check([journal load:&error] == nil && !error, @"missing journal is not corruption");
        NSDictionary *lease = @{@"version": @1, @"uid": @501, @"service": @"AA55AA55-1111-2222-3333-ABCDEFABCDEF", @"original": original, @"applied": applied};
        Check([journal save:lease error:&error] && !error, @"durable write-ahead journal saved");
        Check([[journal load:&error] isEqual:lease] && !error, @"binary plist round trip");
        struct stat info; fstatat(directory, "lease.plist", &info, AT_SYMLINK_NOFOLLOW);
        Check((info.st_mode & 0777) == 0600, @"journal has owner-only permissions");
        fchmodat(directory, "lease.plist", 0644, 0); error = nil;
        Check([journal load:&error] == nil && error, @"readable-by-others record refused");
        fchmodat(directory, "lease.plist", 0600, 0); error = nil;
        Check([journal clear:&error] && [journal load:&error] == nil && !error, @"durable completion removes record");
        symlinkat("/etc/hosts", directory, "lease.plist"); error = nil;
        Check([journal load:&error] == nil && error, @"symlink record refused");
        unlinkat(directory, "lease.plist", 0); error = nil;
        Check([journal save:@{@"version": @2} error:&error], @"corrupt fixture stored");
        Check([journal load:&error] == nil && error, @"unsupported schema preserved and refused");
        [journal clear:&error]; close(directory); journal = nil; rmdir(temporary);
        printf("Native proxy helper policy: %lu checks passed; no host settings written\n", (unsigned long)Checks);
    }
    return 0;
}
