package app.perroute.platform.macos;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;

/** File-based macOS Keychain Services for unsigned desktop development. No secrets enter argv/stdout. */
public final class MacKeychain implements StateKeychain {
    public interface Security extends Library {
        int SecKeychainSetUserInteractionAllowed(byte allowed);
        int SecItemCopyMatching(Pointer query, PointerByReference result);
        int SecItemAdd(Pointer attributes, PointerByReference result);
    }
    public interface CoreFoundation extends Library {
        Pointer CFStringCreateWithCString(Pointer allocator, String text, int encoding);
        Pointer CFDataCreate(Pointer allocator, byte[] data, NativeLong length);
        NativeLong CFDataGetLength(Pointer data);
        Pointer CFDataGetBytePtr(Pointer data);
        NativeLong CFGetTypeID(Pointer value);
        NativeLong CFDataGetTypeID();
        Pointer CFDictionaryCreateMutable(Pointer allocator, NativeLong capacity, Pointer keys, Pointer values);
        void CFDictionarySetValue(Pointer dictionary, Pointer key, Pointer value);
        Pointer CFArrayCreate(Pointer allocator, Pointer[] values, NativeLong count, Pointer callbacks);
        void CFRelease(Pointer value);
    }
    static final String SERVICE = "app.perroute.state.v1";
    static final int NOT_FOUND = -25300;
    static final int DUPLICATE = -25299;
    private static final NativeLibrary SEC_LIB = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security");
    private static final NativeLibrary CF_LIB = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
    static final Security SEC = Native.load(SEC_LIB.getFile().toString(), Security.class);
    static final CoreFoundation CF = Native.load(CF_LIB.getFile().toString(), CoreFoundation.class);
    private final Pointer keychain;
    private final boolean interactionAllowed;

    public MacKeychain() { this(null, false); }
    public static MacKeychain interactiveDesktop() { return new MacKeychain(null, true); }
    // A retained keychain handle supplied only by the isolated native test fixture.
    MacKeychain(Pointer keychain) { this(keychain, false); }
    private MacKeychain(Pointer keychain, boolean interactionAllowed) {
        this.keychain = keychain; this.interactionAllowed = interactionAllowed;
    }

    @Override public byte[] read(String account) throws IOException {
        configureInteraction();
        try (var query = base(account)) {
            query.set("kSecReturnData", cfConstant("kCFBooleanTrue"));
            query.set("kSecMatchLimit", constant("kSecMatchLimitOne"));
            if (keychain != null) {
                Pointer list = CF.CFArrayCreate(null, new Pointer[]{keychain}, new NativeLong(1), CF_LIB.getGlobalVariableAddress("kCFTypeArrayCallBacks"));
                try { query.set("kSecMatchSearchList", list); } finally { CF.CFRelease(list); }
            }
            var result = new PointerByReference();
            int status = SEC.SecItemCopyMatching(query.pointer, result);
            if (status == NOT_FOUND) return null;
            requireSuccess("read", status);
            Pointer data = result.getValue();
            if (data == null) throw new IOException("Keychain returned no state key");
            try {
                if (!CF.CFGetTypeID(data).equals(CF.CFDataGetTypeID()) || CF.CFDataGetLength(data).longValue() != 32)
                    throw new IOException("Keychain state key is invalid");
                return CF.CFDataGetBytePtr(data).getByteArray(0, 32);
            } finally { CF.CFRelease(data); }
        }
    }
    @Override public boolean add(String account, byte[] key) throws IOException {
        configureInteraction();
        if (key.length != 32) throw new IOException("State key must be 256 bits");
        try (var attributes = base(account)) {
            Pointer data = CF.CFDataCreate(null, key, new NativeLong(key.length));
            try { attributes.set("kSecValueData", data); } finally { CF.CFRelease(data); }
            if (keychain != null) attributes.set("kSecUseKeychain", keychain);
            int status = SEC.SecItemAdd(attributes.pointer, null);
            if (status == DUPLICATE) return false;
            requireSuccess("create", status);
            return true;
        }
    }
    private void configureInteraction() throws IOException {
        // File-based unlock dialogs also require the process-wide policy. Only explicit desktop
        // startup opts in; headless callers and native fixtures continue to fail without prompting.
        requireSuccess("set process interaction", SEC.SecKeychainSetUserInteractionAllowed((byte) (interactionAllowed ? 1 : 0)));
    }
    private Dictionary base(String account) {
        var result = new Dictionary();
        result.set("kSecClass", constant("kSecClassGenericPassword"));
        result.string("kSecAttrService", SERVICE);
        result.string("kSecAttrAccount", account);
        result.set("kSecUseAuthenticationUI", constant(interactionAllowed ? "kSecUseAuthenticationUIAllow" : "kSecUseAuthenticationUIFail"));
        return result;
    }
    static void requireSuccess(String operation, int status) throws IOException {
        if (status != 0) throw new IOException("Keychain " + operation + " failed (OSStatus " + status + "). Unlock/authorize the keychain and retry; saved files were preserved.");
    }
    private static Pointer constant(String name) { return SEC_LIB.getGlobalVariableAddress(name).getPointer(0); }
    private static Pointer cfConstant(String name) { return CF_LIB.getGlobalVariableAddress(name).getPointer(0); }
    private static final class Dictionary implements AutoCloseable {
        final Pointer pointer = CF.CFDictionaryCreateMutable(null, new NativeLong(0),
                CF_LIB.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"), CF_LIB.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"));
        void set(String key, Pointer value) { CF.CFDictionarySetValue(pointer, constant(key), value); }
        void string(String key, String value) {
            Pointer text = CF.CFStringCreateWithCString(null, value, 0x08000100);
            try { set(key, text); } finally { CF.CFRelease(text); }
        }
        @Override public void close() { CF.CFRelease(pointer); }
    }
}
