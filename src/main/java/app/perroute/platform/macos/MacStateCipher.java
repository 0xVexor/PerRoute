package app.perroute.platform.macos;

import app.perroute.persistence.StateCipher;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** MAC1 deliberately rejects Windows DPAPI and the old local-key format; migration is explicit. */
public final class MacStateCipher implements StateCipher, AutoCloseable {
    private static final byte[] HEADER = {'M', 'A', 'C', '1'};
    private final byte[] key;
    private boolean closed;
    public MacStateCipher(byte[] key) throws IOException {
        if (key.length != 32) throw new IOException("Keychain state key must be 256 bits");
        this.key = key.clone();
    }
    @Override public synchronized byte[] protect(byte[] plain) throws IOException {
        requireOpen();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            cipher.updateAAD(HEADER);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(HEADER.length + nonce.length + encrypted.length).put(HEADER).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException error) { throw new IOException("Could not encrypt macOS state", error); }
    }
    @Override public synchronized byte[] unprotect(byte[] encrypted) throws IOException {
        requireOpen();
        if (encrypted.length < 32 || !Arrays.equals(HEADER, Arrays.copyOf(encrypted, 4)))
            throw new IOException("Unsupported or truncated macOS state. Existing data was preserved; automatic Windows/local-key migration is unavailable.");
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(encrypted, 4, 16));
            cipher.updateAAD(HEADER);
            return cipher.doFinal(encrypted, 16, encrypted.length - 16);
        } catch (GeneralSecurityException error) { throw new IOException("Could not decrypt macOS state. Existing data was preserved.", error); }
    }
    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher result = Cipher.getInstance("AES/GCM/NoPadding");
        result.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return result;
    }
    private void requireOpen() throws IOException { if (closed) throw new IOException("State key has been closed"); }
    @Override public synchronized void close() { Arrays.fill(key, (byte) 0); closed = true; }
}
