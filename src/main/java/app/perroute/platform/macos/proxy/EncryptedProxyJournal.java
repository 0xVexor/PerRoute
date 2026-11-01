package app.perroute.platform.macos.proxy;

import app.perroute.persistence.PrivateStateFiles;
import app.perroute.persistence.StateCipher;
import app.perroute.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Inject the existing Keychain-backed cipher; this store never creates or substitutes a key. */
public final class EncryptedProxyJournal implements ProxyJournal {
    private final Path file;
    private final StateCipher cipher;
    public EncryptedProxyJournal(Path file, StateCipher cipher) {
        this.file = Objects.requireNonNull(file);
        this.cipher = Objects.requireNonNull(cipher);
    }
    @Override public Optional<Lease> load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        PrivateStateFiles.directory(file.toAbsolutePath().getParent());
        PrivateStateFiles.requireRegular(file);
        if (Files.size(file) > 1_048_576) throw new IOException("Proxy recovery journal is too large");
        byte[] plain = cipher.unprotect(Files.readAllBytes(file));
        try {
            var root = Json.object(new String(plain, StandardCharsets.UTF_8));
            if (!root.keySet().equals(Set.of("version", "lease")) || !Objects.equals(root.get("version"), 1))
                throw new IllegalArgumentException("Unsupported journal version");
            Object rawLease = root.get("lease");
            if (rawLease == null) return Optional.empty();
            Map<String, Object> lease = object(rawLease);
            requireKeys(lease, "owner", "entries");
            UUID owner = UUID.fromString((String) lease.get("owner"));
            var entries = new ArrayList<Entry>();
            for (Object raw : (List<?>) lease.get("entries")) {
                var entry = object(raw);
                requireKeys(entry, "serviceId", "before", "applied", "groups");
                var groups = EnumSet.noneOf(Group.class);
                for (Object group : (List<?>) entry.get("groups")) {
                    if (!groups.add(Group.valueOf((String) group))) throw new IllegalArgumentException("Duplicate ownership group");
                }
                entries.add(new Entry((String) entry.get("serviceId"), new ProxySettings(object(entry.get("before"))),
                        new ProxySettings(object(entry.get("applied"))), groups));
            }
            return Optional.of(new Lease(owner, entries));
        } catch (RuntimeException error) {
            // Parser messages may quote settings. Keep them out of the user-visible exception chain.
            throw new IOException("Invalid proxy recovery journal; existing bytes were preserved");
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    @Override public void save(Optional<Lease> lease) throws IOException {
        Object encoded = lease.<Object>map(value -> Map.of("owner", value.owner().toString(), "entries",
                value.entries().stream().map(entry -> Map.of("serviceId", entry.serviceId(), "before", entry.before().values(),
                        "applied", entry.applied().values(), "groups", entry.groups().stream().map(Enum::name).sorted().toList())).toList())).orElse(null);
        var root = new LinkedHashMap<String, Object>();
        root.put("version", 1); root.put("lease", encoded);
        byte[] plain = Json.stringify(root).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = cipher.protect(plain);
            if (encrypted.length > 1_048_576) throw new IOException("Proxy recovery journal is too large");
            // An encrypted empty lease is an atomic completion marker. Never delete before cleanup succeeds.
            PrivateStateFiles.write(file, encrypted);
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected object");
        return (Map<String, Object>) value;
    }
    private static void requireKeys(Map<String, Object> value, String... keys) {
        if (!value.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("Invalid journal fields");
    }
}
