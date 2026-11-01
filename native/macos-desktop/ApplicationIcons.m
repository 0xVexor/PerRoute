#import <AppKit/AppKit.h>
#include <stdlib.h>
#include <string.h>

// Resolve the nearest conventional app whose declared main executable is this file.
// Icons are presentation metadata only and never broaden a routing selection.
static NSString *PRIconBundleForExecutable(NSString *path) {
    if (!path.length || path.length > 8192 || !path.isAbsolutePath) return nil;
    char *resolved = realpath(path.fileSystemRepresentation, NULL);
    if (!resolved) return nil;
    NSString *executable = [NSFileManager.defaultManager stringWithFileSystemRepresentation:resolved length:strlen(resolved)];
    free(resolved);
    BOOL directory = NO;
    if (![NSFileManager.defaultManager fileExistsAtPath:executable isDirectory:&directory] || directory ||
        ![NSFileManager.defaultManager isExecutableFileAtPath:executable]) return nil;
    NSString *candidate = executable.stringByDeletingLastPathComponent;
    for (NSUInteger depth = 0; depth < 64 && candidate.length > 1; depth++) {
        if ([candidate.pathExtension isEqual:@"app"]) {
            NSBundle *bundle = [NSBundle bundleWithPath:candidate];
            const char *declared = bundle.executablePath.fileSystemRepresentation;
            char *canonical = declared ? realpath(declared, NULL) : NULL;
            BOOL matches = canonical && !strcmp(canonical, executable.fileSystemRepresentation);
            free(canonical);
            if (matches) return candidate;
            return nil;
        }
        candidate = candidate.stringByDeletingLastPathComponent;
    }
    return nil;
}

__attribute__((visibility("default"))) char *perroute_application_icon(const char *executable) {
    @autoreleasepool {
        if (!executable || strnlen(executable, 32769) > 32768) return NULL;
        NSString *bundle = PRIconBundleForExecutable([NSString stringWithUTF8String:executable]);
        if (!bundle) return NULL;
        NSImage *icon = [NSWorkspace.sharedWorkspace iconForFile:bundle];
        if (!icon.isValid) return NULL;
        // A fixed bitmap bounds native output and avoids carrying full ICNS payloads into Compose.
        NSBitmapImageRep *bitmap = [[NSBitmapImageRep alloc] initWithBitmapDataPlanes:NULL
            pixelsWide:64 pixelsHigh:64 bitsPerSample:8 samplesPerPixel:4 hasAlpha:YES isPlanar:NO
            colorSpaceName:NSDeviceRGBColorSpace bytesPerRow:0 bitsPerPixel:0];
        if (!bitmap) return NULL;
        NSGraphicsContext *context = [NSGraphicsContext graphicsContextWithBitmapImageRep:bitmap];
        if (!context) return NULL;
        [NSGraphicsContext saveGraphicsState];
        @try {
            NSGraphicsContext.currentContext = context;
            [icon drawInRect:NSMakeRect(0,0,64,64) fromRect:NSZeroRect
                operation:NSCompositingOperationCopy fraction:1 respectFlipped:NO hints:nil];
        } @finally { [NSGraphicsContext restoreGraphicsState]; }
        NSData *png = [bitmap representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
        if (!png.length || png.length > 65536) return NULL;
        return strdup([png base64EncodedStringWithOptions:0].UTF8String);
    }
}

__attribute__((visibility("default"))) void perroute_application_icon_free(void *value) { free(value); }
