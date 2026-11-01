package app.perroute.platform.macos;

import app.perroute.persistence.AppStateStore;
import java.io.*;
import java.util.Properties;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

/** A single encrypted preferences node; saves synchronously so UI-only changes survive native Quit. */
public final class EncryptedPreferences extends AbstractPreferences {
    private final Properties values;
    private final AppStateStore store;
    public EncryptedPreferences(AppStateStore store) throws IOException {
        super(null, "");
        this.store = store;
        values = store.exists() ? store.loadProperties() : new Properties();
    }
    @Override protected void putSpi(String key, String value) { change(key, value); }
    @Override protected String getSpi(String key) { return values.getProperty(key); }
    @Override protected void removeSpi(String key) { change(key, null); }
    private void change(String key, String value) {
        String before = values.getProperty(key);
        if (value == null) values.remove(key); else values.setProperty(key, value);
        try { persist(); }
        catch (IOException error) {
            if (before == null) values.remove(key); else values.setProperty(key, before);
            throw new UncheckedIOException("Could not save macOS preferences", error);
        }
    }
    private void persist() throws IOException {
        var bytes = new ByteArrayOutputStream();
        values.store(bytes, "PerRoute preferences");
        store.write(bytes.toByteArray());
    }
    @Override protected void removeNodeSpi() { throw new UnsupportedOperationException("Root preferences cannot be removed"); }
    @Override protected String[] keysSpi() { return values.stringPropertyNames().toArray(String[]::new); }
    @Override protected String[] childrenNamesSpi() { return new String[0]; }
    @Override protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException("Only root preferences are supported"); }
    @Override protected void syncSpi() throws BackingStoreException { flushSpi(); }
    @Override protected void flushSpi() throws BackingStoreException {
        try { persist(); } catch (IOException error) { throw new BackingStoreException(error); }
    }
}
