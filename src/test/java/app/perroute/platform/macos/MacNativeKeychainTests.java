package app.perroute.platform.macos;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Creates and deletes only its own disposable keychain; never queries a login item or changes defaults. */
public final class MacNativeKeychainTests {
    public interface TestSecurity extends Library {
        int SecKeychainCreate(String path, int length, byte[] password, byte prompt, Pointer access, PointerByReference result);
        int SecKeychainDelete(Pointer keychain);
        int SecKeychainOpen(String path, PointerByReference result);
        int SecKeychainLock(Pointer keychain);
        int SecKeychainUnlock(Pointer keychain, int length, byte[] password, byte usePassword);
        int SecKeychainCopySearchList(PointerByReference list);
        int SecKeychainCopyDefault(PointerByReference result);
    }
    public interface TestCF extends Library { byte CFEqual(Pointer first, Pointer second); }
    static final TestSecurity SEC = Native.load("/System/Library/Frameworks/Security.framework/Security", TestSecurity.class);
    static final TestCF CF = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", TestCF.class);
    private static int assertions;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--read")) {
            var handle = new PointerByReference();
            MacKeychain.requireSuccess("open fixture", SEC.SecKeychainOpen(args[1], handle));
            try (var session = MacStateSession.open(Path.of(args[2]), new MacKeychain(handle.getValue()))) {
                check(session.preferences().get("fixture", "").equals("survives-process"), "New JVM reads Keychain-backed state");
                System.out.println("Fresh JVM reopened secure state");
            } finally { MacKeychain.CF.CFRelease(handle.getValue()); }
            return;
        }
        Path root = Files.createTempDirectory("perroute-native-keychain-").toRealPath();
        byte[] password = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        var handle = new PointerByReference();
        var beforeSearch = new PointerByReference();
        var beforeDefault = new PointerByReference();
        MacKeychain.requireSuccess("snapshot search list", SEC.SecKeychainCopySearchList(beforeSearch));
        MacKeychain.requireSuccess("snapshot default", SEC.SecKeychainCopyDefault(beforeDefault));
        Path keychainPath = root.resolve("fixture.keychain-db");
        try {
            MacKeychain.requireSuccess("create fixture", SEC.SecKeychainCreate(keychainPath.toString(), password.length, password, (byte) 0, null, handle));
            var keys = new MacKeychain(handle.getValue());
            check(keys.read("absent-fixture") == null, "Absent item maps to null");
            byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            check(keys.add("fixture-key", key), "Native generic password can be added");
            check(Arrays.equals(key, keys.read("fixture-key")), "Native key round-trips");
            check(!keys.add("fixture-key", new byte[32]), "Native duplicate does not replace key");
            check(Arrays.equals(key, keys.read("fixture-key")), "Duplicate leaves original key intact");
            Arrays.fill(key, (byte) 0);
            Path state = root.resolve("state");
            try (var session = MacStateSession.open(state, keys)) {
                session.preferences().put("fixture", "survives-process");
                session.store().write(session.store().serialize(session.state()));
            }
            Process reader = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), MacNativeKeychainTests.class.getName(), "--read", keychainPath.toString(), state.toString())
                    .redirectErrorStream(true).start();
            try {
                check(reader.waitFor(20, TimeUnit.SECONDS), "Fresh JVM terminates");
                String output = new String(reader.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                check(reader.exitValue() == 0 && output.contains("Fresh JVM reopened"), "Fresh JVM decrypts persisted settings: " + output);
            } finally { if (reader.isAlive()) reader.destroyForcibly(); }
            byte[] before = Files.readAllBytes(state.resolve("state.bin"));
            MacKeychain.requireSuccess("lock fixture", SEC.SecKeychainLock(handle.getValue()));
            boolean denied = false;
            try (var ignored = MacStateSession.open(state, keys)) { } catch (java.io.IOException expected) { denied = true; }
            check(denied, "Locked fixture fails without authentication UI");
            check(Arrays.equals(before, Files.readAllBytes(state.resolve("state.bin"))), "Locked fixture preserves existing data");
            MacKeychain.requireSuccess("unlock fixture", SEC.SecKeychainUnlock(handle.getValue(), password.length, password, (byte) 1));
            try (var session = MacStateSession.open(state, keys)) {
                check(session.preferences().get("fixture", "").equals("survives-process"), "Unlock restores access");
            }
        } finally {
            Arrays.fill(password, (byte) 0);
            if (handle.getValue() != null) {
                MacKeychain.requireSuccess("delete fixture", SEC.SecKeychainDelete(handle.getValue()));
                MacKeychain.CF.CFRelease(handle.getValue());
            }
            var afterSearch = new PointerByReference(); var afterDefault = new PointerByReference();
            MacKeychain.requireSuccess("check search list", SEC.SecKeychainCopySearchList(afterSearch));
            MacKeychain.requireSuccess("check default", SEC.SecKeychainCopyDefault(afterDefault));
            try {
                check(CF.CFEqual(beforeSearch.getValue(), afterSearch.getValue()) != 0, "User search list unchanged after fixture cleanup");
                check(CF.CFEqual(beforeDefault.getValue(), afterDefault.getValue()) != 0, "Default keychain unchanged");
            } finally {
                for (Pointer pointer : new Pointer[]{beforeSearch.getValue(), beforeDefault.getValue(), afterSearch.getValue(), afterDefault.getValue()}) MacKeychain.CF.CFRelease(pointer);
                MacSecureStateTests.deleteTree(root);
            }
        }
        System.out.println("macOS native Keychain checks passed (" + assertions + " assertions)");
    }
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
