#import "ProxyHelper.h"

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 3) return 64;
        BOOL valid = PRSignedSelf([NSString stringWithUTF8String:argv[1]]);
        BOOL expected = strcmp(argv[2], "yes") == 0;
        return valid == expected ? 0 : 1;
    }
}
