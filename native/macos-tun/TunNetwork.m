#import "TunNetwork.h"
#import "TunCapture.h"
#import "TunStore.h"
#import <SystemConfiguration/SystemConfiguration.h>
#import <arpa/inet.h>
#import <ifaddrs.h>
#import <net/if.h>
#import <net/route.h>
#import <sys/sysctl.h>
NSString *const PRTunInterface = @PR_TUN_INTERFACE;
static const uint32_t TunAddress = 0xac1fff01U, TunMask = 0xfffffffcU;
static id Reject(NSError **error, NSString *message) { if (error) *error = PRTunError(message); return nil; }
NSArray<NSDictionary *> *PRTunDecodeRoutes(NSData *bytes, NSError **error) {
    if (![bytes isKindOfClass:NSData.class] || bytes.length > 4194304) return Reject(error,@"Invalid route observation");
    NSMutableArray *routes = [NSMutableArray array];
    const unsigned char *data = bytes.bytes;
    NSUInteger position = 0;
    while (position < bytes.length) {
        if (bytes.length-position < sizeof(struct rt_msghdr2)) return Reject(error,@"Truncated route observation");
        struct rt_msghdr2 header; memcpy(&header,data+position,sizeof(header));
        if (header.rtm_version != RTM_VERSION || header.rtm_msglen < sizeof(header) || header.rtm_msglen > bytes.length-position)
            return Reject(error,@"Invalid route record");
        const unsigned char *addresses[RTAX_MAX] = {0};
        NSUInteger cursor = position+sizeof(header), end = position+header.rtm_msglen;
        if (header.rtm_addrs & ~((1U<<RTAX_MAX)-1)) return Reject(error,@"Unknown route address fields");
        for (int i=0;i<RTAX_MAX;i++) if (header.rtm_addrs & (1<<i)) {
            if (end-cursor < 2) return Reject(error,@"Truncated route address");
            unsigned length = data[cursor];
            // Darwin routing messages align sockaddrs to four bytes on both CPUs.
            unsigned padded = length ? (length+3U)&~3U : 4;
            if (padded > end-cursor) return Reject(error,@"Invalid route address size");
            addresses[i] = data+cursor; cursor += padded;
        }
        if (cursor != end) return Reject(error,@"Unexpected route record bytes");
        const unsigned char *destination = addresses[RTAX_DST], *maskAddress = addresses[RTAX_NETMASK];
        if (destination && destination[1] == AF_INET) {
            if (destination[0] < 8) return Reject(error,@"Invalid IPv4 route destination");
            uint32_t network = 0, mask = 0;
            memcpy(&network,destination+4,4); network = ntohl(network);
            if (header.rtm_flags & RTF_HOST) mask = UINT32_MAX;
            else if (maskAddress && maskAddress[0]) {
                // A compressed kernel netmask is a sockaddr bitmask, not an
                // address. Its family byte may be 255; destination determines IPv4.
                if (maskAddress[0] < 4) return Reject(error,@"Invalid compressed route mask");
                if (maskAddress[0] > 4) memcpy(&mask,maskAddress+4,MIN((unsigned)maskAddress[0]-4,4U));
                mask = ntohl(mask);
            }
            uint32_t inverse = ~mask;
            if (inverse & (inverse+1U)) return Reject(error,@"Noncontiguous IPv4 route mask");
            [routes addObject:@{@"network":@(network&mask),@"mask":@(mask),@"index":@(header.rtm_index),@"flags":@(header.rtm_flags)}];
        }
        position = end;
    }
    return [routes copy];
}
NSArray<NSDictionary *> *PRTunReadRoutes(NSError **error) {
    int query[] = {CTL_NET,PF_ROUTE,0,AF_INET,NET_RT_DUMP2,0};
    size_t size = 0;
    for (int attempt=0;attempt<3;attempt++) {
        if (sysctl(query,6,NULL,&size,NULL,0) || !size || size > 4194304) return Reject(error,@"Cannot inspect bounded IPv4 routes");
        NSMutableData *bytes = [NSMutableData dataWithLength:size];
        if (!sysctl(query,6,bytes.mutableBytes,&size,NULL,0)) { bytes.length = size; return PRTunDecodeRoutes(bytes,error); }
        if (errno != ENOMEM) break;
    }
    return Reject(error,@"IPv4 routes changed during observation; retry");
}
static NSString *Primary(void) {
    SCDynamicStoreRef store = SCDynamicStoreCreate(NULL,CFSTR("PerRoute routing observation"),NULL,NULL);
    CFPropertyListRef copied = store ? SCDynamicStoreCopyValue(store,CFSTR("State:/Network/Global/IPv4")) : NULL;
    id value = copied && CFGetTypeID(copied) == CFDictionaryGetTypeID() ? [(__bridge NSDictionary *)copied objectForKey:@"PrimaryInterface"] : nil;
    NSString *name = [value isKindOfClass:NSString.class] ? [value copy] : nil;
    if (copied) CFRelease(copied);
    if (store) CFRelease(store);
    return name;
}
NSDictionary *PRTunNetworkBaseline(NSError **error) {
    NSString *primary = Primary();
    unsigned index = primary ? if_nametoindex(primary.UTF8String) : 0;
    if (!index || [primary hasPrefix:@"utun"] || [primary isEqual:@"lo0"])
        return Reject(error,@"Per-app routing requires a stable physical IPv4 connection; resolve competing tunnels first");
    if (if_nametoindex(PRTunInterface.UTF8String)) return Reject(error,@"PerRoute's reserved tunnel interface is already present");
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces)) return Reject(error,@"Cannot inspect network interfaces");
    BOOL conflict = NO;
    for (struct ifaddrs *entry=interfaces;entry;entry=entry->ifa_next) if (entry->ifa_addr && entry->ifa_addr->sa_family == AF_INET) {
        uint32_t address = ntohl(((struct sockaddr_in *)entry->ifa_addr)->sin_addr.s_addr);
        if ((address&TunMask) == (TunAddress&TunMask)) conflict = YES;
    }
    freeifaddrs(interfaces);
    if (conflict) return Reject(error,@"The reserved tunnel address is already in use");
    NSArray *routes = PRTunReadRoutes(error);
    if (!routes) return nil;
    for (NSDictionary *route in routes) {
        uint32_t mask = [route[@"mask"] unsignedIntValue], network = [route[@"network"] unsignedIntValue];
        uint32_t common = mask & TunMask;
        if (mask && (network&common) == (TunAddress&common)) return Reject(error,@"An existing route conflicts with the reserved tunnel subnet");
    }
    return @{@"primary":primary,@"index":@(index)};
}
BOOL PRTunRoutesCoverCaptureRange(NSArray<NSDictionary *> *routes, unsigned index) {
    if (!index) return NO;
    NSArray *ordered = [routes sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *left, NSDictionary *right) {
        return [left[@"network"] compare:right[@"network"]];
    }];
    // The pinned Darwin engine excludes 0/8 and partitions the remaining address
    // space into eight prefixes. Verify coverage, not one particular route layout.
    uint64_t covered = 0x01000000ULL, limit = 0x100000000ULL;
    for (NSDictionary *route in ordered) {
        unsigned flags = [route[@"flags"] unsignedIntValue];
        if ([route[@"index"] unsignedIntValue] != index || !(flags&RTF_UP) ||
            (flags&(RTF_IFSCOPE|RTF_HOST|RTF_REJECT|RTF_BLACKHOLE))) continue;
        uint32_t mask = [route[@"mask"] unsignedIntValue];
        uint64_t begin = [route[@"network"] unsignedIntValue];
        uint64_t end = begin+(uint64_t)(uint32_t)~mask+1;
        if (end <= covered) continue;
        if (begin > covered) return NO;
        covered = end;
        if (covered >= limit) return YES;
    }
    return NO;
}
BOOL PRTunPrimaryMatches(NSDictionary *baseline, NSString *primary, unsigned index) {
    return index && [primary isEqual:baseline[@"primary"]] && index == [baseline[@"index"] unsignedIntValue];
}
BOOL PRTunNetworkReady(NSDictionary *baseline, NSError **error) {
    NSString *primary = Primary();
    if (!PRTunPrimaryMatches(baseline,primary,primary ? if_nametoindex(primary.UTF8String) : 0)) {
        if (error) *error = PRTunNetworkChangedError();
        return NO;
    }
    unsigned index = if_nametoindex(PRTunInterface.UTF8String);
    if (!index) return NO; // Process startup can precede kernel interface creation.
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces)) { Reject(error,@"Cannot verify the tunnel interface"); return NO; }
    BOOL address = NO;
    for (struct ifaddrs *entry=interfaces;entry;entry=entry->ifa_next) if (entry->ifa_addr &&
        !strcmp(entry->ifa_name,PRTunInterface.UTF8String) && entry->ifa_addr->sa_family == AF_INET && (entry->ifa_flags & IFF_UP) &&
        ntohl(((struct sockaddr_in *)entry->ifa_addr)->sin_addr.s_addr) == TunAddress) address = YES;
    freeifaddrs(interfaces);
    if (!address) { Reject(error,@"The tunnel interface has unexpected state"); return NO; }
    NSArray *routes = PRTunReadRoutes(error);
    if (!routes) return NO;
    return PRTunRoutesCoverCaptureRange(routes,index);
}
