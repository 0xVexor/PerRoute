package app.perroute.persistence;

import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RoutingMode;
import app.perroute.model.RouteRule;
import app.perroute.model.ConnectionProfile;
import app.perroute.model.BandwidthPolicy;
import app.perroute.routing.AutoCandidatePool;
import app.perroute.routing.AutoSelection;
import app.perroute.parser.ShareLinkParser;
import app.perroute.util.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public final class AppStateStore {
    private final Path file;
    private final StateCipher protector;
    private final boolean privateFiles;
    private final boolean memoryOnly;
    private volatile byte[] memory;
    private final ShareLinkParser parser = new ShareLinkParser();

    public AppStateStore(Path file) {
        this.file = file;
        this.protector = new StateProtector(file.resolveSibling(file.getFileName() + ".key"));
        this.memoryOnly = false;
        this.privateFiles = false;
    }

    private AppStateStore() { file = null; protector = null; memoryOnly = true; privateFiles = false; }

    public AppStateStore(Path file, StateCipher protector) {
        this.file = file;
        this.protector = java.util.Objects.requireNonNull(protector);
        this.memoryOnly = false;
        this.privateFiles = true;
    }

    /** Keep restore snapshots under the same key policy as the primary store. */
    public AppStateStore sibling(Path path) {
        if (memoryOnly) return inMemory();
        return privateFiles ? new AppStateStore(path, protector) : new AppStateStore(path);
    }

    /** Development preview storage until platform key custody is available. Never creates a key file. */
    public static AppStateStore inMemory() { return new AppStateStore(); }

    /** The state format written by this version. States without a version were written by PerRoute 2 or earlier. */
    public static final int STATE_VERSION = 3;

    public boolean exists() { return !memoryOnly && Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS); }

    public AppState load() throws IOException {
        if (memoryOnly) return memory == null ? new AppState() : parse(memory);
        if (privateFiles) {
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return new AppState();
            PrivateStateFiles.requireRegular(file);
        } else if (!Files.isRegularFile(file)) return new AppState();
        return parse(loadProperties());
    }

    public Properties loadProperties() throws IOException {
        if (privateFiles) PrivateStateFiles.requireRegular(file);
        byte[] plain = memoryOnly ? (memory == null ? new byte[0] : memory.clone()) : protector.unprotect(Files.readAllBytes(file));
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(plain));
        return properties;
    }

    public AppState parse(byte[] plain) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(plain));
        return parse(properties);
    }

    public AppState parse(Properties properties) {
        AppState state = new AppState();
        int nodeCount = integer(properties, "node.count");
        for (int i = 0; i < nodeCount; i++) {
            String json = properties.getProperty("node." + i + ".json", "");
            String link = properties.getProperty("node." + i + ".link", "");
            try {
                ProxyNode node;
                if (!json.isBlank()) node = decodeNode(json);
                else if (!link.isBlank()) node = parser.parse(link);
                else continue;
                state.nodes().add(node);
                String source = properties.getProperty("node." + i + ".subscription", "");
                if (!source.isBlank()) state.nodeSources().put(node.key(), source);
            }
            catch (RuntimeException ignored) { }
        }
        int appCount = integer(properties, "app.count");
        for (int i = 0; i < appCount; i++) {
            String path = properties.getProperty("app." + i + ".path", "");
            if (!path.isBlank()) state.appPaths().add(path);
        }
        int subscriptionCount = integer(properties, "subscription.count");
        for (int i = 0; i < subscriptionCount; i++) {
            String url = properties.getProperty("subscription." + i + ".url", "");
            if (!url.isBlank()) state.subscriptionUrls().add(url);
        }
        try { state.setRoutingMode(RoutingMode.valueOf(properties.getProperty("routing.mode"))); }
        catch (RuntimeException ignored) { state.setRoutingMode(RoutingMode.INCLUDE_SELECTED); }
        try { state.setConnectionMode(ConnectionMode.valueOf(properties.getProperty("connection.mode"))); }
        catch (RuntimeException ignored) { state.setConnectionMode(ConnectionMode.SYSTEM_PROXY); }
        state.setSelectedNodeKey(properties.getProperty("selected.node", ""));
        readCsv(properties.getProperty("node.favorites", "")).forEach(state.favoriteNodeKeys()::add);
        readCsv(properties.getProperty("node.hidden", "")).forEach(state.hiddenNodeKeys()::add);
        int tagCount = integer(properties, "node.tag.count");
        for (int i = 0; i < tagCount; i++) {
            String key = properties.getProperty("node.tag." + i + ".key", "");
            String value = properties.getProperty("node.tag." + i + ".value", "");
            if (!key.isBlank() && !value.isBlank()) state.nodeTags().put(key, value);
        }
        int ruleCount = integer(properties, "rule.count");
        for (int i = 0; i < ruleCount; i++) {
            try { state.routeRules().add(decodeRule(properties.getProperty("rule." + i + ".json", ""))); }
            catch (RuntimeException ignored) {}
        }
        int profileCount = integer(properties, "profile.count");
        for (int i = 0; i < profileCount; i++) {
            try { state.profiles().add(decodeProfile(properties.getProperty("profile." + i + ".json", ""))); }
            catch (RuntimeException ignored) {}
        }
        state.setActiveProfileId(properties.getProperty("profile.active", ""));
        state.setAdvancedRouting(Boolean.parseBoolean(properties.getProperty("advanced.enabled", "false")));
        int routeCount = integer(properties, "route.count");
        for (int i = 0; i < routeCount; i++) {
            String app = AppRoutes.normalize(properties.getProperty("route." + i + ".app", ""));
            String nodeKey = properties.getProperty("route." + i + ".node", "");
            if (app.isBlank() || nodeKey.isBlank()) continue;
            state.appRoutes().put(app, nodeKey);
            String label = properties.getProperty("route." + i + ".label", "");
            if (!label.isBlank()) state.appRouteLabels().put(nodeKey, label);
        }
        String version = properties.getProperty("state.version", "");
        state.setMigratedFromV2(version.isBlank() || Boolean.parseBoolean(properties.getProperty("state.migratedFromV2", "false")));
        int autoCount = integer(properties, "auto.count");
        for (int i = 0; i < autoCount; i++) {
            String app = AppRoutes.normalize(properties.getProperty("auto." + i + ".app", ""));
            if (app.isBlank()) continue;
            state.autoApps().add(app);
            String pool = properties.getProperty("auto." + i + ".pool", "");
            if (!pool.isBlank()) state.autoPools().put(app, AutoCandidatePool.decode(pool).encode());
        }
        int selectionCount = integer(properties, "autosel.count");
        for (int i = 0; i < selectionCount; i++) {
            String app = AppRoutes.normalize(properties.getProperty("autosel." + i + ".app", ""));
            String nodeKey = properties.getProperty("autosel." + i + ".node", "");
            if (app.isBlank() || nodeKey.isBlank()) continue;
            long changed;
            try { changed = Long.parseLong(properties.getProperty("autosel." + i + ".at", "0")); }
            catch (NumberFormatException ignored) { changed = 0L; }
            state.autoSelections().put(app, new AutoSelection(nodeKey, changed));
        }
        int limitCount = integer(properties, "bandwidth.count");
        for (int i = 0; i < limitCount; i++) {
            String app = AppRoutes.normalize(properties.getProperty("bandwidth." + i + ".app", ""));
            BandwidthPolicy policy = BandwidthPolicy.decode(properties.getProperty("bandwidth." + i + ".limit", ""));
            if (!app.isBlank() && !policy.unlimited()) state.bandwidthPolicies().put(app, policy);
        }
        return state;
    }

    public void save(AppState state) throws IOException {
        write(serialize(state));
    }

    public byte[] serialize(AppState state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("node.count", String.valueOf(state.nodes().size()));
        for (int i = 0; i < state.nodes().size(); i++) {
            ProxyNode node = state.nodes().get(i);
            properties.setProperty("node." + i + ".json", encodeNode(node));
            String source = state.nodeSources().getOrDefault(node.key(), "");
            if (!source.isBlank()) properties.setProperty("node." + i + ".subscription", source);
        }
        properties.setProperty("app.count", String.valueOf(state.appPaths().size()));
        for (int i = 0; i < state.appPaths().size(); i++) properties.setProperty("app." + i + ".path", state.appPaths().get(i));
        properties.setProperty("subscription.count", String.valueOf(state.subscriptionUrls().size()));
        for (int i = 0; i < state.subscriptionUrls().size(); i++) {
            properties.setProperty("subscription." + i + ".url", state.subscriptionUrls().get(i));
        }
        properties.setProperty("routing.mode", state.routingMode().name());
        properties.setProperty("connection.mode", state.connectionMode().name());
        properties.setProperty("selected.node", state.selectedNodeKey());
        properties.setProperty("node.favorites", String.join(",", state.favoriteNodeKeys()));
        properties.setProperty("node.hidden", String.join(",", state.hiddenNodeKeys()));
        properties.setProperty("node.tag.count", String.valueOf(state.nodeTags().size()));
        int tagIndex = 0;
        for (var entry : state.nodeTags().entrySet()) {
            properties.setProperty("node.tag." + tagIndex + ".key", entry.getKey());
            properties.setProperty("node.tag." + tagIndex + ".value", entry.getValue());
            tagIndex++;
        }
        properties.setProperty("rule.count", String.valueOf(state.routeRules().size()));
        for (int i = 0; i < state.routeRules().size(); i++) {
            properties.setProperty("rule." + i + ".json", encodeRule(state.routeRules().get(i)));
        }
        properties.setProperty("profile.count", String.valueOf(state.profiles().size()));
        for (int i = 0; i < state.profiles().size(); i++) {
            properties.setProperty("profile." + i + ".json", encodeProfile(state.profiles().get(i)));
        }
        properties.setProperty("profile.active", state.activeProfileId());
        properties.setProperty("advanced.enabled", String.valueOf(state.advancedRouting()));
        properties.setProperty("route.count", String.valueOf(state.appRoutes().size()));
        int routeIndex = 0;
        for (var entry : state.appRoutes().entrySet()) {
            properties.setProperty("route." + routeIndex + ".app", entry.getKey());
            properties.setProperty("route." + routeIndex + ".node", entry.getValue());
            String label = state.appRouteLabels().getOrDefault(entry.getValue(), "");
            if (!label.isBlank()) properties.setProperty("route." + routeIndex + ".label", label);
            routeIndex++;
        }
        properties.setProperty("state.version", String.valueOf(STATE_VERSION));
        properties.setProperty("state.migratedFromV2", String.valueOf(state.migratedFromV2()));
        properties.setProperty("auto.count", String.valueOf(state.autoApps().size()));
        int autoIndex = 0;
        for (String app : state.autoApps()) {
            properties.setProperty("auto." + autoIndex + ".app", app);
            String pool = state.autoPools().getOrDefault(app, "");
            if (!pool.isBlank()) properties.setProperty("auto." + autoIndex + ".pool", pool);
            autoIndex++;
        }
        properties.setProperty("autosel.count", String.valueOf(state.autoSelections().size()));
        int selectionIndex = 0;
        for (var entry : state.autoSelections().entrySet()) {
            properties.setProperty("autosel." + selectionIndex + ".app", entry.getKey());
            properties.setProperty("autosel." + selectionIndex + ".node", entry.getValue().nodeKey());
            properties.setProperty("autosel." + selectionIndex + ".at", String.valueOf(entry.getValue().changedAt()));
            selectionIndex++;
        }
        properties.setProperty("bandwidth.count", String.valueOf(state.bandwidthPolicies().size()));
        int limitIndex = 0;
        for (var entry : state.bandwidthPolicies().entrySet()) {
            properties.setProperty("bandwidth." + limitIndex + ".app", entry.getKey());
            properties.setProperty("bandwidth." + limitIndex + ".limit", entry.getValue().encode());
            limitIndex++;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        properties.store(out, "PerRoute encrypted state");
        return out.toByteArray();
    }

    public synchronized void write(byte[] plain) throws IOException {
        if (memoryOnly) { memory = plain.clone(); return; }
        byte[] encrypted = protector.protect(plain);
        if (privateFiles) { PrivateStateFiles.write(file, encrypted); return; }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, encrypted);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int integer(Properties properties, String key) {
        try { return Integer.parseInt(properties.getProperty(key, "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    public static String encodeNode(ProxyNode node) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", node.key());
        data.put("name", node.name());
        data.put("protocol", node.protocol().name());
        data.put("address", node.address());
        data.put("port", node.port());
        data.put("credential", node.credential());
        data.put("options", node.options());
        data.put("sourceLink", node.sourceLink());
        return Json.stringify(data);
    }

    @SuppressWarnings("unchecked")
    public static ProxyNode decodeNode(String json) {
        Map<String, Object> data = Json.object(json);
        Map<String, String> options = new LinkedHashMap<>();
        Object rawOptions = data.get("options");
        if (rawOptions instanceof Map<?, ?> map) {
            map.forEach((key, value) -> options.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        }
        return new ProxyNode(string(data, "key"), string(data, "name"),
                Protocol.valueOf(string(data, "protocol")), string(data, "address"),
                ((Number)data.get("port")).intValue(), string(data, "credential"), options,
                string(data, "sourceLink"));
    }

    private static String string(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> readCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    public static String encodeRule(RouteRule rule) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", rule.id()); data.put("name", rule.name());
        data.put("applicationPath", rule.applicationPath()); data.put("matchType", rule.matchType().name());
        data.put("value", rule.value()); data.put("action", rule.action().name()); data.put("enabled", rule.enabled());
        return Json.stringify(data);
    }

    public static RouteRule decodeRule(String json) {
        Map<String, Object> data = Json.object(json);
        return new RouteRule(string(data, "id"), string(data, "name"), string(data, "applicationPath"),
                RouteRule.MatchType.valueOf(string(data, "matchType")), string(data, "value"),
                RouteRule.Action.valueOf(string(data, "action")), Boolean.TRUE.equals(data.get("enabled")));
    }

    public static String encodeProfile(ConnectionProfile profile) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", profile.id()); data.put("name", profile.name()); data.put("applicationPaths", profile.applicationPaths());
        data.put("nodeKey", profile.nodeKey()); data.put("mode", profile.mode().name());
        data.put("rules", profile.rules().stream().map(AppStateStore::encodeRule).map(Json::object).toList());
        data.put("advancedRouting", profile.advancedRouting());
        data.put("appRoutes", new LinkedHashMap<>(profile.appRoutes()));
        data.put("autoApps", new ArrayList<>(profile.autoApps()));
        return Json.stringify(data);
    }

    @SuppressWarnings("unchecked")
    public static ConnectionProfile decodeProfile(String json) {
        Map<String, Object> data = Json.object(json);
        List<String> apps = data.get("applicationPaths") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        List<RouteRule> rules = new ArrayList<>();
        if (data.get("rules") instanceof List<?> list) {
            for (Object value : list) if (value instanceof Map<?, ?> map) rules.add(decodeRule(Json.stringify(map)));
        }
        Map<String, String> routes = new LinkedHashMap<>();
        if (data.get("appRoutes") instanceof Map<?, ?> map) {
            map.forEach((app, nodeKey) -> { if (nodeKey != null) routes.put(String.valueOf(app), String.valueOf(nodeKey)); });
        }
        java.util.Set<String> auto = new java.util.LinkedHashSet<>();
        if (data.get("autoApps") instanceof List<?> autoList) autoList.forEach(item -> { if (item != null) auto.add(String.valueOf(item)); });
        return new ConnectionProfile(string(data, "id"), string(data, "name"), apps, string(data, "nodeKey"),
                ConnectionMode.valueOf(string(data, "mode")), rules,
                Boolean.TRUE.equals(data.get("advancedRouting")), routes, auto);
    }
}
