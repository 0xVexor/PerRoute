package app.perroute.backup;

import app.perroute.model.ApplicationIdentity;

import app.perroute.model.AppRoutes;
import app.perroute.model.AppState;
import app.perroute.model.BandwidthPolicy;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ConnectionProfile;
import app.perroute.model.ProxyNode;
import app.perroute.model.RouteRule;
import app.perroute.model.RoutingMode;
import app.perroute.persistence.AppStateStore;
import app.perroute.routing.AutoCandidatePool;
import app.perroute.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class BackupService {
    public static final int SCHEMA = 1;
    public static final String MANUAL_SOURCE = "__manual__";
    public static final String IMPORTED_SOURCE = "__imported__";

    public enum Section {
        SUBSCRIPTIONS("Subscriptions", "Saved subscription links and their servers"),
        MANUAL_SERVERS("Manual servers", "Servers added by link, QR code or file"),
        APP_ASSIGNMENTS("App assignments", "Selected apps, routing modes and bandwidth limits"),
        RULES("Rules", "Custom routing rules"),
        PROFILES("Profiles", "Saved profiles"),
        PREFERENCES("Preferences", "Connection mode, automation and appearance");

        private final String title;
        private final String detail;
        Section(String title, String detail) { this.title = title; this.detail = detail; }
        public String title() { return title; }
        public String detail() { return detail; }
    }

    public enum Mode { MERGE, REPLACE }

    public enum Compatibility { COMPATIBLE, MIGRATED }

    public record PathIssue(String originalPath, String suggestion) {}

    public record Counts(int subscriptions, int servers, int applications, int rules, int profiles, int preferences) {}

    public record RestorePreview(BackupCodec.Header header, Compatibility compatibility, Set<Section> sections, Counts counts,
                                 List<PathIssue> missingPaths, int serversAlreadyPresent, int profilesWithSameName,
                                 List<String> warnings, Payload payload) {}

    public record Plan(AppState state, Map<String, String> preferences, ConnectionMode connectionMode,
                       RoutingMode routingMode, List<String> summary, int skippedApplications) {}

    public static final class Payload {
        final Set<Section> sections = EnumSet.noneOf(Section.class);
        final List<String> subscriptions = new ArrayList<>();
        final List<ServerEntry> servers = new ArrayList<>();
        String selectedServer = "";
        final List<String> applications = new ArrayList<>();
        boolean advanced;
        final Map<String, String> routes = new LinkedHashMap<>();
        final Map<String, String> routeLabels = new LinkedHashMap<>();
        final Set<String> autoApps = new LinkedHashSet<>();
        final Map<String, String> autoPools = new LinkedHashMap<>();
        final Map<String, BandwidthPolicy> bandwidth = new LinkedHashMap<>();
        final List<RouteRule> rules = new ArrayList<>();
        final List<ConnectionProfile> profiles = new ArrayList<>();
        String activeProfile = "";
        final Map<String, String> preferences = new LinkedHashMap<>();
        ConnectionMode connectionMode;
        RoutingMode routingMode;
    }

    record ServerEntry(ProxyNode node, String source, boolean favorite, boolean hidden, String tag) {
        boolean manual() { return source.isBlank() || source.equals(MANUAL_SOURCE) || source.equals(IMPORTED_SOURCE); }
    }

    private BackupService() {}

    public static byte[] payload(AppState state, Map<String, String> preferences, Set<Section> sections) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("sections", sections.stream().map(Enum::name).toList());
        boolean subscriptions = sections.contains(Section.SUBSCRIPTIONS);
        boolean manual = sections.contains(Section.MANUAL_SERVERS);
        if (subscriptions) root.put("subscriptions", new ArrayList<>(state.subscriptionUrls()));
        if (subscriptions || manual) {
            List<Object> servers = new ArrayList<>();
            for (ProxyNode node : state.nodes()) {
                String source = state.nodeSources().getOrDefault(node.key(), IMPORTED_SOURCE);
                boolean isManual = source.equals(MANUAL_SOURCE) || source.equals(IMPORTED_SOURCE);
                if (isManual ? !manual : !subscriptions) continue;
                Map<String, Object> entry = new LinkedHashMap<>(Json.object(AppStateStore.encodeNode(node)));
                entry.put("source", source);
                entry.put("favorite", state.favoriteNodeKeys().contains(node.key()));
                entry.put("hidden", state.hiddenNodeKeys().contains(node.key()));
                entry.put("tag", state.nodeTags().getOrDefault(node.key(), ""));
                servers.add(entry);
            }
            root.put("servers", servers);
            root.put("selectedServer", state.selectedNodeKey());
        }
        if (sections.contains(Section.APP_ASSIGNMENTS)) {
            Map<String, Object> apps = new LinkedHashMap<>();
            apps.put("paths", new ArrayList<>(state.appPaths()));
            apps.put("advanced", state.advancedRouting());
            apps.put("routes", new LinkedHashMap<>(state.appRoutes()));
            apps.put("routeLabels", new LinkedHashMap<>(state.appRouteLabels()));
            apps.put("auto", new ArrayList<>(state.autoApps()));
            apps.put("pools", new LinkedHashMap<>(state.autoPools()));
            Map<String, Object> limits = new LinkedHashMap<>();
            state.bandwidthPolicies().forEach((app, policy) -> limits.put(app, policy.encode()));
            apps.put("bandwidth", limits);
            root.put("apps", apps);
        }
        if (sections.contains(Section.RULES)) {
            root.put("rules", state.routeRules().stream().map(rule -> (Object) Json.object(AppStateStore.encodeRule(rule))).toList());
        }
        if (sections.contains(Section.PROFILES)) {
            root.put("profiles", state.profiles().stream().map(profile -> (Object) Json.object(AppStateStore.encodeProfile(profile))).toList());
            root.put("activeProfile", state.activeProfileId());
        }
        if (sections.contains(Section.PREFERENCES)) {
            Map<String, Object> prefs = new LinkedHashMap<>(preferences);
            prefs.put("connectionMode", state.connectionMode().name());
            prefs.put("routingMode", state.routingMode().name());
            root.put("preferences", prefs);
        }
        return Json.stringify(root).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] export(AppState state, Map<String, String> preferences, Set<Section> sections,
                                char[] password, String application, long now) {
        if (sections == null || sections.isEmpty()) throw new IllegalArgumentException("Choose at least one thing to back up.");
        return BackupCodec.encode(payload(state, preferences, sections), SCHEMA, application, now, password);
    }

    public static RestorePreview preview(byte[] file, char[] password, AppState current,
                                         Predicate<String> exists, Collection<String> knownExecutables)
            throws BackupCodec.BackupException {
        BackupCodec.Header header = BackupCodec.header(file);
        if (header.schema() > SCHEMA) {
            throw new BackupCodec.BackupException(BackupCodec.BackupException.Reason.NEWER_FORMAT,
                    "This backup was made by a newer PerRoute (" + header.application() + "). Update PerRoute to restore it.");
        }
        byte[] plain = BackupCodec.decode(file, password);
        List<String> warnings = new ArrayList<>();
        Payload payload = read(plain, header.schema(), warnings);

        Set<String> identities = new HashSet<>();
        current.nodes().forEach(node -> identities.add(node.identity()));
        int present = (int) payload.servers.stream().filter(entry -> identities.contains(entry.node().identity())).count();
        Set<String> profileNames = new HashSet<>();
        current.profiles().forEach(profile -> profileNames.add(profile.name().toLowerCase(Locale.ROOT)));
        int sameName = (int) payload.profiles.stream().filter(profile -> profileNames.contains(profile.name().toLowerCase(Locale.ROOT))).count();

        Map<String, String> byFileName = new HashMap<>();
        if (knownExecutables != null) {
            for (String path : knownExecutables) {
                String name = fileName(path).toLowerCase(Locale.ROOT);
                if (!name.isBlank() && exists.test(path)) byFileName.putIfAbsent(name, path);
            }
        }
        List<PathIssue> missing = new ArrayList<>();
        for (String path : referencedApplications(payload)) {
            if (!exists.test(path)) missing.add(new PathIssue(path, byFileName.get(fileName(path).toLowerCase(Locale.ROOT))));
        }
        int preferences = payload.preferences.size() + (payload.connectionMode != null ? 1 : 0) + (payload.routingMode != null ? 1 : 0);
        Counts counts = new Counts(payload.subscriptions.size(), payload.servers.size(), payload.applications.size(),
                payload.rules.size(), payload.profiles.size(), preferences);
        return new RestorePreview(header, header.schema() < SCHEMA ? Compatibility.MIGRATED : Compatibility.COMPATIBLE,
                EnumSet.copyOf(payload.sections.isEmpty() ? EnumSet.noneOf(Section.class) : payload.sections), counts,
                List.copyOf(missing), present, sameName, List.copyOf(warnings), payload);
    }

    static List<String> referencedApplications(Payload payload) {
        Map<String, String> seen = new LinkedHashMap<>();
        payload.applications.forEach(path -> seen.putIfAbsent(AppRoutes.normalize(path), path));
        payload.routes.keySet().forEach(path -> seen.putIfAbsent(AppRoutes.normalize(path), path));
        payload.autoApps.forEach(path -> seen.putIfAbsent(AppRoutes.normalize(path), path));
        payload.bandwidth.keySet().forEach(path -> seen.putIfAbsent(AppRoutes.normalize(path), path));
        payload.rules.stream().map(RouteRule::applicationPath).filter(path -> !path.isBlank())
                .forEach(path -> seen.putIfAbsent(AppRoutes.normalize(path), path));
        payload.profiles.forEach(profile -> profile.applicationPaths().forEach(path -> seen.putIfAbsent(AppRoutes.normalize(path), path)));
        return List.copyOf(seen.values());
    }

    public static Plan plan(RestorePreview preview, AppState copyOfCurrent, Map<String, String> currentPreferences,
                            Mode mode, Map<String, String> remap, Predicate<String> exists) {
        Payload payload = preview.payload();
        AppState state = copyOfCurrent;
        List<String> summary = new ArrayList<>();
        Map<String, String> normalizedRemap = new HashMap<>();
        if (remap != null) remap.forEach((from, to) -> normalizedRemap.put(AppRoutes.normalize(from), to == null ? "" : ApplicationIdentity.isMac(to) ? to : to.trim()));
        Set<String> skipped = new LinkedHashSet<>();
        java.util.function.Function<String, String> mapPath = path -> {
            if (path == null || path.isBlank()) return path;
            String key = AppRoutes.normalize(path);
            String target = normalizedRemap.containsKey(key) ? normalizedRemap.get(key) : path;
            if (target.isBlank() || !exists.test(target)) { skipped.add(key); return null; }
            return ApplicationIdentity.isMac(target) ? AppRoutes.normalize(target) : Path.of(target).toAbsolutePath().normalize().toString();
        };

        Map<String, String> keyMap = new HashMap<>();
        boolean replace = mode == Mode.REPLACE;
        boolean subscriptions = payload.sections.contains(Section.SUBSCRIPTIONS);
        boolean manual = payload.sections.contains(Section.MANUAL_SERVERS);
        if (replace && (subscriptions || manual)) {
            Set<String> removed = new HashSet<>();
            for (ProxyNode node : state.nodes()) {
                String source = state.nodeSources().getOrDefault(node.key(), IMPORTED_SOURCE);
                boolean isManual = source.equals(MANUAL_SOURCE) || source.equals(IMPORTED_SOURCE);
                if (isManual ? manual : subscriptions) removed.add(node.key());
            }
            state.nodes().removeIf(node -> removed.contains(node.key()));
            removed.forEach(key -> {
                state.nodeSources().remove(key); state.favoriteNodeKeys().remove(key);
                state.hiddenNodeKeys().remove(key); state.nodeTags().remove(key);
            });
            if (subscriptions) state.subscriptionUrls().clear();
            summary.add("Replaced " + removed.size() + " saved servers.");
        }
        if (subscriptions) {
            for (String url : payload.subscriptions) {
                if (state.subscriptionUrls().stream().noneMatch(existing -> existing.equalsIgnoreCase(url))) state.subscriptionUrls().add(url);
            }
        }
        Map<String, String> identityToKey = new HashMap<>();
        Set<String> usedKeys = new HashSet<>();
        state.nodes().forEach(node -> { identityToKey.put(node.identity(), node.key()); usedKeys.add(node.key()); });
        int added = 0;
        for (ServerEntry entry : payload.servers) {
            ProxyNode node = entry.node();
            String existing = identityToKey.get(node.identity());
            if (existing != null) { keyMap.put(node.key(), existing); continue; }
            String key = usedKeys.contains(node.key()) ? UUID.randomUUID().toString() : node.key();
            ProxyNode stored = key.equals(node.key()) ? node : new ProxyNode(key, node.name(), node.protocol(), node.address(),
                    node.port(), node.credential(), node.options(), node.sourceLink());
            state.nodes().add(stored);
            usedKeys.add(key);
            identityToKey.put(stored.identity(), key);
            keyMap.put(node.key(), key);
            state.nodeSources().put(key, entry.source().isBlank() ? IMPORTED_SOURCE : entry.source());
            if (entry.favorite()) state.favoriteNodeKeys().add(key);
            if (entry.hidden()) state.hiddenNodeKeys().add(key);
            if (!entry.tag().isBlank()) state.nodeTags().put(key, entry.tag());
            added++;
        }
        if (subscriptions || manual) {
            summary.add("Added " + added + " servers" + (payload.servers.size() > added ? "; " + (payload.servers.size() - added) + " were already saved." : "."));
            String selected = keyMap.get(payload.selectedServer);
            if (replace && selected != null) state.setSelectedNodeKey(selected);
            if (state.nodes().stream().noneMatch(node -> node.key().equals(state.selectedNodeKey()))) {
                state.setSelectedNodeKey(state.nodes().isEmpty() ? "" : state.nodes().get(0).key());
            }
        }
        java.util.function.Function<String, String> mapKey = key -> keyMap.getOrDefault(key, key);

        if (payload.sections.contains(Section.APP_ASSIGNMENTS)) {
            if (replace) {
                state.appPaths().clear(); state.appRoutes().clear(); state.appRouteLabels().clear();
                state.autoApps().clear(); state.autoPools().clear(); state.autoSelections().clear();
                state.bandwidthPolicies().clear();
                state.setAdvancedRouting(payload.advanced);
            }
            Set<String> alreadyRouted = new HashSet<>(state.appRoutes().keySet());
            alreadyRouted.addAll(state.autoApps());
            int apps = 0;
            for (String path : payload.applications) {
                String mapped = mapPath.apply(path);
                if (mapped == null) continue;
                if (state.appPaths().stream().noneMatch(existing -> ApplicationIdentity.same(existing, mapped))) { state.appPaths().add(mapped); apps++; }
            }
            payload.routes.forEach((path, key) -> {
                String mapped = mapPath.apply(path);
                if (mapped == null) return;
                String app = AppRoutes.normalize(mapped);
                if (!replace && alreadyRouted.contains(app)) return;
                String node = mapKey.apply(key);
                state.appRoutes().put(app, node);
                String label = payload.routeLabels.getOrDefault(key, "");
                if (!label.isBlank()) state.appRouteLabels().putIfAbsent(node, label);
            });
            for (String path : payload.autoApps) {
                String mapped = mapPath.apply(path);
                if (mapped == null) continue;
                String app = AppRoutes.normalize(mapped);
                if (!replace && alreadyRouted.contains(app)) continue;
                state.autoApps().add(app);
                String pool = payload.autoPools.get(path);
                if (pool != null) state.autoPools().put(app, AutoCandidatePool.decode(pool).encode());
            }
            payload.bandwidth.forEach((path, policy) -> {
                String mapped = mapPath.apply(path);
                if (mapped == null) return;
                String app = AppRoutes.normalize(mapped);
                if (replace || !state.bandwidthPolicies().containsKey(app)) state.bandwidthPolicies().put(app, policy);
            });
            summary.add((replace ? "Restored " : "Added ") + apps + " apps with their routing and limits.");
        }

        if (payload.sections.contains(Section.RULES)) {
            if (replace) state.routeRules().clear();
            Set<String> ids = new HashSet<>();
            Set<String> contents = new HashSet<>();
            state.routeRules().forEach(rule -> { ids.add(rule.id()); contents.add(ruleContent(rule)); });
            int rules = 0;
            for (RouteRule rule : payload.rules) {
                String app = rule.applicationPath();
                if (!app.isBlank()) {
                    String mapped = mapPath.apply(app);
                    if (mapped == null) continue;
                    app = mapped;
                }
                RouteRule restored = new RouteRule(rule.id(), rule.name(), app, rule.matchType(), rule.value(), rule.action(), rule.enabled());
                if (ids.contains(restored.id()) || contents.contains(ruleContent(restored))) continue;
                state.routeRules().add(restored);
                ids.add(restored.id());
                contents.add(ruleContent(restored));
                rules++;
            }
            summary.add((replace ? "Restored " : "Added ") + rules + " rules.");
        }

        if (payload.sections.contains(Section.PROFILES)) {
            if (replace) { state.profiles().clear(); state.setActiveProfileId(""); }
            Set<String> names = new HashSet<>();
            state.profiles().forEach(profile -> names.add(profile.name().toLowerCase(Locale.ROOT)));
            int profiles = 0;
            for (ConnectionProfile profile : payload.profiles) {
                if (names.contains(profile.name().toLowerCase(Locale.ROOT))) continue;
                List<String> paths = profile.applicationPaths().stream().map(mapPath).filter(java.util.Objects::nonNull).toList();
                Map<String, String> routes = new LinkedHashMap<>();
                profile.appRoutes().forEach((path, key) -> { String mapped = mapPath.apply(path); if (mapped != null) routes.put(mapped, mapKey.apply(key)); });
                Set<String> auto = new LinkedHashSet<>();
                profile.autoApps().forEach(path -> { String mapped = mapPath.apply(path); if (mapped != null) auto.add(mapped); });
                List<RouteRule> rules = profile.rules().stream().map(rule -> {
                    if (rule.applicationPath().isBlank()) return rule;
                    String mapped = mapPath.apply(rule.applicationPath());
                    return mapped == null ? null : new RouteRule(rule.id(), rule.name(), mapped, rule.matchType(), rule.value(), rule.action(), rule.enabled());
                }).filter(java.util.Objects::nonNull).toList();
                state.profiles().add(new ConnectionProfile(profile.id(), profile.name(), paths, mapKey.apply(profile.nodeKey()), profile.mode(),
                        rules, profile.advancedRouting(), routes, auto));
                names.add(profile.name().toLowerCase(Locale.ROOT));
                profiles++;
            }
            if (replace && state.profiles().stream().anyMatch(profile -> profile.id().equals(payload.activeProfile))) {
                state.setActiveProfileId(payload.activeProfile);
            }
            summary.add((replace ? "Restored " : "Added ") + profiles + " profiles.");
        }

        Map<String, String> preferences = new LinkedHashMap<>(currentPreferences == null ? Map.of() : currentPreferences);
        ConnectionMode connectionMode = state.connectionMode();
        RoutingMode routingMode = state.routingMode();
        if (payload.sections.contains(Section.PREFERENCES)) {
            if (replace) {
                preferences.putAll(payload.preferences);
                if (payload.connectionMode != null) connectionMode = payload.connectionMode;
                if (payload.routingMode != null) routingMode = payload.routingMode;
                state.setConnectionMode(connectionMode);
                state.setRoutingMode(routingMode);
                summary.add("Restored preferences.");
            } else {
                summary.add("Kept your current preferences (Merge never overwrites settings).");
            }
        }
        if (!skipped.isEmpty()) summary.add("Skipped " + skipped.size() + " apps that are not on this computer.");
        return new Plan(state, preferences, connectionMode, routingMode, List.copyOf(summary), skipped.size());
    }

    private static String ruleContent(RouteRule rule) {
        return String.join("\u0000", rule.name(), AppRoutes.normalize(rule.applicationPath()), rule.matchType().name(),
                rule.value(), rule.action().name());
    }

    static Payload read(byte[] plain, int schema, List<String> warnings) throws BackupCodec.BackupException {
        Map<String, Object> root;
        try { root = Json.object(new String(plain, StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) {
            throw new BackupCodec.BackupException(BackupCodec.BackupException.Reason.DAMAGED, "The backup contents are damaged.");
        }
        root = migrate(root, schema);
        Payload payload = new Payload();
        if (root.get("sections") instanceof List<?> list) {
            for (Object value : list) {
                try { payload.sections.add(Section.valueOf(String.valueOf(value))); } catch (IllegalArgumentException ignored) { }
            }
        }
        strings(root.get("subscriptions")).stream().filter(BackupService::validSubscription).forEach(payload.subscriptions::add);
        int badServers = 0;
        if (root.get("servers") instanceof List<?> servers) {
            for (Object raw : servers) {
                if (!(raw instanceof Map<?, ?> map)) { badServers++; continue; }
                try {
                    ProxyNode node = AppStateStore.decodeNode(Json.stringify(map));
                    payload.servers.add(new ServerEntry(node, text(map.get("source")), Boolean.TRUE.equals(map.get("favorite")),
                            Boolean.TRUE.equals(map.get("hidden")), text(map.get("tag"))));
                } catch (RuntimeException invalid) { badServers++; }
            }
        }
        if (badServers > 0) warnings.add(badServers + " servers could not be read and will be skipped.");
        payload.selectedServer = text(root.get("selectedServer"));
        if (root.get("apps") instanceof Map<?, ?> apps) {
            strings(apps.get("paths")).stream().filter(path -> !path.isBlank()).forEach(payload.applications::add);
            payload.advanced = Boolean.TRUE.equals(apps.get("advanced"));
            stringMap(apps.get("routes")).forEach((app, key) -> { if (!app.isBlank() && !key.isBlank()) payload.routes.put(app, key); });
            payload.routeLabels.putAll(stringMap(apps.get("routeLabels")));
            strings(apps.get("auto")).stream().filter(path -> !path.isBlank()).forEach(payload.autoApps::add);
            payload.autoPools.putAll(stringMap(apps.get("pools")));
            stringMap(apps.get("bandwidth")).forEach((app, limit) -> {
                BandwidthPolicy policy = BandwidthPolicy.decode(limit);
                if (!app.isBlank() && !policy.unlimited()) payload.bandwidth.put(app, policy);
            });
        }
        int badRules = 0;
        if (root.get("rules") instanceof List<?> rules) {
            for (Object raw : rules) {
                try { payload.rules.add(AppStateStore.decodeRule(Json.stringify(raw))); }
                catch (RuntimeException invalid) { badRules++; }
            }
        }
        if (badRules > 0) warnings.add(badRules + " rules could not be read and will be skipped.");
        int badProfiles = 0;
        if (root.get("profiles") instanceof List<?> profiles) {
            for (Object raw : profiles) {
                try { payload.profiles.add(AppStateStore.decodeProfile(Json.stringify(raw))); }
                catch (RuntimeException invalid) { badProfiles++; }
            }
        }
        if (badProfiles > 0) warnings.add(badProfiles + " profiles could not be read and will be skipped.");
        payload.activeProfile = text(root.get("activeProfile"));
        if (root.get("preferences") instanceof Map<?, ?> prefs) {
            prefs.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (value == null) return;
                switch (name) {
                    case "connectionMode" -> {
                        try {
                            ConnectionMode restored = ConnectionMode.valueOf(String.valueOf(value));
                            payload.connectionMode = restored == ConnectionMode.TUN_COMPATIBILITY ? ConnectionMode.SYSTEM_PROXY : restored;
                        } catch (IllegalArgumentException ignored) { warnings.add("An unknown connection mode was ignored."); }
                    }
                    case "routingMode" -> {
                        try { payload.routingMode = RoutingMode.valueOf(String.valueOf(value)); }
                        catch (IllegalArgumentException ignored) { }
                    }
                    default -> payload.preferences.put(name, String.valueOf(value));
                }
            });
        }
        return payload;
    }

    static Map<String, Object> migrate(Map<String, Object> root, int schema) {
        Map<String, Object> current = root;
        return current;
    }

    private static boolean validSubscription(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            return uri.getScheme() != null && (uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> { if (key != null && item != null) result.put(String.valueOf(key), String.valueOf(item)); });
        return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    static String fileName(String path) {
        try { Path name = Path.of(ApplicationIdentity.path(path)).getFileName(); return name == null ? "" : name.toString(); }
        catch (RuntimeException invalid) { return ""; }
    }

    public static boolean executableExists(String path) {
        try { return path != null && !path.isBlank() && Files.isRegularFile(Path.of(ApplicationIdentity.path(path))); }
        catch (RuntimeException invalid) { return false; }
    }
}
