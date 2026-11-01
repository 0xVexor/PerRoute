#import "TunPolicy.h"
#include <stdio.h>

static NSUInteger Checks;
static void Check(BOOL passed, NSString *message) {
    Checks++;
    if (!passed) { fprintf(stderr, "FAIL: %s\n", message.UTF8String); exit(1); }
}
static NSData *Encode(id object) { return [NSJSONSerialization dataWithJSONObject:object options:0 error:NULL]; }
static NSDictionary *Decode(NSData *data) { return data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil; }
static NSDictionary *Request(id bindings) { return @{@"version": @1, @"operation": @"prepare-tun", @"bindings": bindings}; }
static NSDictionary *Binding(id path, id port) { return @{@"executable": path, @"socksPort": port}; }
static void Refuse(id request, NSArray *trusted) {
    NSError *error = nil;
    Check(PRTunPrepareConfiguration(Encode(request), trusted, &error) == nil && error != nil, @"invalid request refused");
}
int main(int argc, const char **argv) {
    @autoreleasepool {
        Check(argc == 3, @"Java fixture and native output arguments required");
        NSArray *trusted = @[@"/trusted/engine"];
        NSDictionary *request = Request(@[Binding(@"/Applications/App.app/Contents/MacOS/App", @17890)]);
        NSError *error = nil;
        NSDictionary *config = Decode(PRTunPrepareConfiguration(Encode(request), trusted, &error));
        Check(config != nil && !error, @"minimal typed request accepted");
        Check([config[@"inbounds"][0][@"auto_route"] isEqual:@NO], @"route activation remains disabled");
        Check([config[@"route"][@"final"] isEqual:@"unmatched-direct"], @"explicit direct fallback");
        Check([config[@"route"][@"rules"][0][@"process_path"] isEqual:trusted], @"trusted exclusions precede selections");
        for (NSString *key in @[@"command", @"engine", @"engineExecutables", @"config", @"auto_route", @"routes", @"dns", @"environment", @"uid", @"pid", @"interface", @"lease"]) {
            NSMutableDictionary *extra = [request mutableCopy]; extra[key] = @"caller-controlled"; Refuse(extra, trusted);
        }
        for (id value in @[@YES, @0, @2, @1.5, @"1", NSNull.null]) {
            NSMutableDictionary *changed = [request mutableCopy]; changed[@"version"] = value; Refuse(changed, trusted);
        }
        for (id value in @[@"start", @"stop", @"run", @1, NSNull.null]) {
            NSMutableDictionary *changed = [request mutableCopy]; changed[@"operation"] = value; Refuse(changed, trusted);
        }
        for (id value in @[@YES, @0, @65536, @1.5, @"17890", @(-1), NSNull.null])
            Refuse(Request(@[Binding(@"/app", value)]), trusted);
        for (id path in @[@"", @"relative", @"/", @"/app/", @"/app//file", @"/app/../file", @"/app/./file", @"/app\\file", @"/app\nfile", @1, NSNull.null])
            Refuse(Request(@[Binding(path, @1)]), trusted);
        Refuse(Request(@[Binding([@"/" stringByPaddingToLength:8193 withString:@"a" startingAtIndex:0], @1)]), trusted);
        Refuse(Request(@[Binding(@"/app", @1), Binding(@"/app", @2)]), trusted);
        Refuse(Request(@[Binding(@"/trusted/engine", @1)]), trusted);
        Refuse(Request(@[]), trusted);
        Refuse(Request(@{}), trusted);
        Refuse(@[], trusted);
        Refuse(@{@"version": @1, @"bindings": @[]}, trusted);
        Refuse(Request(@[@{@"executable": @"/app", @"socksPort": @1, @"server": @"192.0.2.1"}]), trusted);
        Refuse(Request(@[@{@"executable": @"/app"}]), trusted);
        Refuse(Request(@[@1]), trusted);
        Refuse(request, @[]);
        Refuse(request, @[@"/trusted/engine", @"/trusted/engine"]);
        Refuse(request, @[@"/trusted/../engine"]);
        Refuse(request, @[@1]);
        NSMutableArray *tooMany = [NSMutableArray array];
        for (int i = 0; i < 257; i++) [tooMany addObject:Binding([NSString stringWithFormat:@"/app/%d", i], @1)];
        Refuse(Request(tooMany), trusted);
        [tooMany removeLastObject];
        Check(PRTunPrepareConfiguration(Encode(Request(tooMany)), trusted, &error) != nil, @"256 explicit paths supported");
        [tooMany removeAllObjects];
        for (int i = 0; i < 33; i++) [tooMany addObject:[NSString stringWithFormat:@"/engine/%d", i]];
        Refuse(request, tooMany);
        NSData *large = [NSMutableData dataWithLength:65537];
        Check(!PRTunPrepareConfiguration(large, trusted, &error) && error, @"oversized bytes refused before parsing");
        Check(!PRTunPrepareConfiguration([@"{" dataUsingEncoding:NSUTF8StringEncoding], trusted, &error) && error, @"malformed JSON refused");
        Check(!PRTunPrepareConfiguration(nil, trusted, &error) && error, @"missing bytes refused");
        Check(!PRTunPrepareConfiguration(Encode(request), nil, &error) && error, @"missing trusted exclusions refused");
        Check(PRTunPrepareConfiguration(Encode(Request(@[Binding(@"/app", @1), Binding(@"/App", @65535)])), trusted, &error) != nil,
            @"case-sensitive paths and inclusive SOCKS port limits");
        NSMutableString *mutablePath = [@"/trusted/engine" mutableCopy];
        NSData *snapshot = PRTunPrepareConfiguration(Encode(request), @[mutablePath], &error);
        [mutablePath setString:@"/changed"];
        Check([Decode(snapshot)[@"route"][@"rules"][0][@"process_path"] isEqual:trusted], @"prepared bytes own their trusted-input snapshot");
        NSDictionary *fixture = Decode([NSData dataWithContentsOfFile:[NSString stringWithUTF8String:argv[1]]]);
        Check(fixture != nil, @"Java parity fixture loaded");
        NSDictionary *native = Decode(PRTunPrepareConfiguration(Encode(fixture[@"request"]), fixture[@"trusted"], &error));
        Check([native isEqual:fixture[@"expected"]], @"native config equals independent Java routing plan");
        Check([Encode(native) writeToFile:[NSString stringWithUTF8String:argv[2]] atomically:YES], @"native candidate written for official parser check");
        puts([[NSString stringWithFormat:@"macOS native TUN policy: %lu checks passed; no helper or networking changes", (unsigned long)Checks] UTF8String]);
        return 0;
    }
}
