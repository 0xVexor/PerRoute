package app.perroute.platform.macos;

import java.io.IOException;

public interface StateKeychain {
    /** Null means absent. Access denial, locked storage and all other errors must throw. */
    byte[] read(String account) throws IOException;
    /** False means another writer already created the item. Never replace an existing key. */
    boolean add(String account, byte[] key) throws IOException;
}
