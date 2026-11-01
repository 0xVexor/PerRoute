package app.perroute.persistence;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

final class StateProtector implements StateCipher {
    private static final byte[] DPAPI_HEADER = {'D', 'P', 'A', '1'};
    private static final byte[] AES_HEADER = {'A', 'G', 'C', '1'};
    private static final byte[] WRAPPED_KEY_HEADER = {'D', 'P', 'K', '1'};
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path keyFile;

    StateProtector(Path keyFile) {
        this.keyFile = keyFile;
    }

    public byte[] protect(byte[] plain) throws IOException {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadOrCreateKey(), "AES"), new GCMParameterSpec(128, nonce));
            return join(AES_HEADER, nonce, cipher.doFinal(plain));
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt application state", e);
        }
    }

    public byte[] unprotect(byte[] encrypted) throws IOException {
        if (startsWith(encrypted, DPAPI_HEADER)) {
            return WindowsDpapi.unprotect(Arrays.copyOfRange(encrypted, DPAPI_HEADER.length, encrypted.length));
        }
        if (startsWith(encrypted, AES_HEADER)) {
            if (encrypted.length < AES_HEADER.length + 12 + 16) throw new IOException("Encrypted state is truncated");
            try {
                byte[] nonce = Arrays.copyOfRange(encrypted, AES_HEADER.length, AES_HEADER.length + 12);
                byte[] payload = Arrays.copyOfRange(encrypted, AES_HEADER.length + 12, encrypted.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadKey(), "AES"), new GCMParameterSpec(128, nonce));
                return cipher.doFinal(payload);
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not decrypt application state", e);
            }
        }
        return WindowsDpapi.unprotect(encrypted);
    }

    private byte[] loadOrCreateKey() throws IOException {
        if (Files.isRegularFile(keyFile)) return loadKey();
        Files.createDirectories(keyFile.toAbsolutePath().getParent());
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            byte[] stored = wrapKey(key);
            Files.write(keyFile, stored, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return key;
        } catch (IOException e) {
            if (Files.isRegularFile(keyFile)) return loadKey();
            throw e;
        }
    }

    private byte[] loadKey() throws IOException {
        byte[] stored = Files.readAllBytes(keyFile);
        if (startsWith(stored, WRAPPED_KEY_HEADER)) {
            byte[] key = WindowsDpapi.unprotect(Arrays.copyOfRange(stored, WRAPPED_KEY_HEADER.length, stored.length));
            if (key.length != 32) throw new IOException("Local encryption key is invalid");
            repairKeyPermissions(stored);
            return key;
        }
        if (stored.length != 32) throw new IOException("Local encryption key is invalid");
        byte[] wrapped = wrapKey(stored);
        if (startsWith(wrapped, WRAPPED_KEY_HEADER)) {
            try {
                Path temporary = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
                Files.write(temporary, wrapped);
                Files.move(temporary, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
        return stored;
    }

    private static byte[] wrapKey(byte[] key) {
        try { return join(WRAPPED_KEY_HEADER, WindowsDpapi.protect(key)); }
        catch (IOException dpapiUnavailable) { return key; }
    }

    private void repairKeyPermissions(byte[] stored) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(keyFile, AclFileAttributeView.class);
            if (view == null || view.getAcl().size() != 1) return;
            Path temporary = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
            Files.write(temporary, stored);
            Files.move(temporary, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static byte[] join(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer result = ByteBuffer.allocate(length);
        for (byte[] value : values) result.put(value);
        return result.array();
    }
}
