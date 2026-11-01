#import "TunPolicy.h"
#import "TunCapture.h"
static unsigned Checks;
#define CHECK(value) do { Checks++; if (!(value)) { fprintf(stderr,"Capture policy line %d: %s\n",__LINE__,#value); exit(1); } } while(0)
int main(int argc, const char **argv) { @autoreleasepool {
    CHECK(argc == 2);
    NSMutableDictionary *request = [@{@"version":@1,@"operation":@"prepare-tun",@"bindings":@[
        @{@"executable":@"/Applications/Selected.app/Contents/MacOS/Selected",@"socksPort":@17890},
        @{@"executable":@"/Applications/Other.app/Contents/MacOS/Other",@"socksPort":@17892}]} mutableCopy];
    NSArray *excluded = @[@"/Applications/PerRoute.app/Contents/app/resources/xray/macos-arm64/xray",
        @"/Applications/PerRoute.app/Contents/MacOS/PerRoute",
        @"/Library/Application Support/PerRoute/TunHelper/00000000-0000-0000-0000-000000000001/engine"];
    NSData *wire = [NSJSONSerialization dataWithJSONObject:request options:0 error:NULL];
    NSData *bytes = PRTunActiveConfiguration(wire,excluded,501,NULL); CHECK(bytes);
    NSDictionary *configuration = [NSJSONSerialization JSONObjectWithData:bytes options:0 error:NULL];
    CHECK([configuration[@"inbounds"][0][@"auto_route"] isEqual:@YES]);
    CHECK([configuration[@"inbounds"][0][@"interface_name"] isEqual:@PR_TUN_INTERFACE]);
    CHECK([configuration[@"route"][@"rules"][0][@"process_path"] containsObject:excluded[1]]);
    CHECK([configuration[@"route"][@"rules"][0][@"process_path"] containsObject:excluded[0]]);
    CHECK([configuration[@"route"][@"rules"][0][@"process_path"] containsObject:excluded[2]]);
    CHECK([configuration[@"route"][@"final"] isEqual:@"unmatched-direct"]);
    CHECK([configuration[@"outbounds"] count] == 3);
    CHECK([configuration[@"route"][@"rules"][1][@"user_id"] isEqual:@[@501]]);
    CHECK(!configuration[@"route"][@"rules"][0][@"user_id"]);
    CHECK(!PRTunActiveConfiguration(wire,excluded,0,NULL));
    request[@"interface_name"] = @"caller-tunnel";
    CHECK(!PRTunActiveConfiguration([NSJSONSerialization dataWithJSONObject:request options:0 error:NULL],excluded,501,NULL));
    CHECK([bytes writeToFile:[NSString stringWithUTF8String:argv[1]] atomically:YES]);
    NSSet *gatewayPorts = [NSSet setWithArray:@[@17890,@17892]];
    CHECK(([PRTunUpstreamPorts(@[@17990,@17992],gatewayPorts,NULL) isEqual:[NSSet setWithArray:@[@17990,@17992]]]));
    CHECK(!PRTunUpstreamPorts(@[@17990,@17990],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[@17990,@17892],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[@17990],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[@17990,@YES],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[@17990,@1.5],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[@17990,@0],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[@17990,@65536],gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@"ports",gatewayPorts,NULL));
    CHECK(!PRTunUpstreamPorts(@[],[NSSet set],NULL));
    printf("TUN native capture compiler: %u checks passed; no activation\n",Checks);
    return 0;
} }
