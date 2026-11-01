package app.perroute.persistence;

import java.io.IOException;

/** Encryption boundary shared by primary state, preferences and restore snapshots. */
public interface StateCipher {
    byte[] protect(byte[] plain) throws IOException;
    byte[] unprotect(byte[] encrypted) throws IOException;
}
