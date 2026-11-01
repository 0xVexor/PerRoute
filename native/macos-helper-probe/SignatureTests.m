#import "Identity.h"

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc < 3 || argc % 2 != 1) return 64;
        for (int i = 1; i < argc; i += 2) {
            NSURL *url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:argv[i]]];
            BOOL expected = strcmp(argv[i + 1], "accept") == 0;
            if (PRProbeCheckFile(url, PRProbeService) != expected) return 1;
        }
        printf("Helper probe real-signature checks: %d passed\n", (argc - 1) / 2);
    }
}
