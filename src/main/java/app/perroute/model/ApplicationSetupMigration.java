package app.perroute.model;

import app.perroute.backend.api.ApplicationGroup;
import app.perroute.routing.AutoCandidatePool;
import java.util.*;

/** Builds a detached migration. Applying it never mutates the input state or an active route. */
public final class ApplicationSetupMigration {
    public static final String KEEP_EXACT = "keep-exact";
    public record Key(String scope, String target) { }
    public record Settings(String server, boolean auto, String pool, BandwidthPolicy limit) { }
    public record Variant(String identity, boolean selected, Settings settings) { }
    public record Entry(Key key, String scopeName, String applicationName, List<Variant> variants,
                        boolean expandsSelection, int scopedRules, boolean conflict) {
        public Entry { variants = List.copyOf(variants); }
        public boolean requiresChoice() { return conflict || expandsSelection || scopedRules > 0; }
    }
    public record Plan(List<ApplicationGroup> groups, List<Entry> entries) {
        public Plan { groups = List.copyOf(groups); entries = List.copyOf(entries); }
    }
    private record Setup(List<String> selected, Map<String,String> routes, Set<String> auto,
                         Map<String,String> pools, Map<String,BandwidthPolicy> limits, List<RouteRule> rules) { }
    private ApplicationSetupMigration() { }

    public static Plan preview(AppState state, List<ApplicationGroup> groups) {
        var owners = new HashMap<String,String>();
        for (var group : groups) for (String alias : aliases(group)) {
            String prior = owners.putIfAbsent(alias, group.application().path());
            if (prior != null && !prior.equals(group.application().path()))
                throw new IllegalArgumentException("Application groups overlap. Refresh the application inventory.");
        }
        var entries = new ArrayList<Entry>();
        inspect("current", "Current settings", current(state), groups, entries);
        var scopes = new HashSet<String>(Set.of("current", "shared"));
        for (var profile : state.profiles()) {
            if (!scopes.add(profile.id())) throw new IllegalArgumentException("Duplicate profile identity. Repair the profile before grouping.");
            inspect(profile.id(), "Profile: " + profile.name(), setup(profile), groups, entries);
        }
        // Pools and bandwidth limits are global in the existing state format, not
        // profile-local. Resolve them once while retaining old keys for kept exact profiles.
        var sharedGroups = groups.stream().filter(group -> aliases(group).stream().anyMatch(alias ->
                state.autoPools().containsKey(alias) || state.bandwidthPolicies().containsKey(alias)) &&
                (entries.stream().anyMatch(entry ->
                entry.key().target().equals(group.application().path())) || aliases(group).stream().anyMatch(alias ->
                !alias.equals(group.application().path()) &&
                (state.autoPools().containsKey(alias) || state.bandwidthPolicies().containsKey(alias)) &&
                !group.application().path().equals(state.applicationHistoryAliases().get(alias))))).toList();
        var allSelections = new ArrayList<>(state.appPaths());
        state.profiles().forEach(profile -> allSelections.addAll(profile.applicationPaths()));
        inspect("shared", "Shared Auto pools and bandwidth limits", new Setup(allSelections, Map.of(), Set.of(),
                state.autoPools(), state.bandwidthPolicies(), List.of()), sharedGroups, entries);
        return new Plan(groups, entries);
    }
    private static Setup current(AppState state) {
        return new Setup(state.appPaths(), state.appRoutes(), state.autoApps(), Map.of(), Map.of(), state.routeRules());
    }
    private static Setup setup(ConnectionProfile profile) {
        return new Setup(profile.applicationPaths(), profile.appRoutes(), profile.autoApps(), Map.of(), Map.of(), profile.rules());
    }
    private static Set<String> aliases(ApplicationGroup group) {
        String main = ApplicationIdentity.path(group.application().path());
        var aliases = new LinkedHashSet<>(group.executables());
        aliases.add(ApplicationIdentity.macExecutable(main));
        aliases.add(ApplicationIdentity.macAppFamily(main));
        return aliases;
    }
    private static void inspect(String scope, String name, Setup setup, List<ApplicationGroup> groups, List<Entry> result) {
        var referenced = new LinkedHashSet<>(setup.selected());
        referenced.addAll(setup.routes().keySet()); referenced.addAll(setup.auto());
        referenced.addAll(setup.pools().keySet()); referenced.addAll(setup.limits().keySet());
        setup.rules().stream().map(RouteRule::applicationPath).filter(ApplicationIdentity::isMac).forEach(referenced::add);
        for (var group : groups) {
            var present = aliases(group).stream().filter(referenced::contains).toList();
            String target = group.application().path();
            if (present.stream().noneMatch(alias -> !alias.equals(target))) continue;
            var variants = present.stream().map(alias -> new Variant(alias, setup.selected().contains(alias),
                    new Settings(setup.routes().getOrDefault(alias, ""), setup.auto().contains(alias),
                            AutoCandidatePool.decode(setup.pools().get(alias)).encode().toLowerCase(Locale.ROOT),
                            setup.limits().getOrDefault(alias, BandwidthPolicy.UNLIMITED)))).toList();
            boolean expands = !scope.equals("shared") && ApplicationIdentity.isMacFamily(target) && !setup.selected().contains(target) &&
                    variants.stream().anyMatch(Variant::selected);
            int rules = (int) setup.rules().stream().filter(rule -> present.contains(rule.applicationPath())).count();
            boolean conflict = variants.stream().map(Variant::settings).distinct().count() > 1;
            result.add(new Entry(new Key(scope, target), name, group.application().name(), variants, expands, rules, conflict));
        }
    }

    public static AppState apply(AppState original, Plan plan, Map<Key,String> choices) {
        if (!preview(original, plan.groups()).equals(plan))
            throw new IllegalArgumentException("Application settings changed. Review a fresh migration preview.");
        var keys = plan.entries().stream().map(Entry::key).collect(java.util.stream.Collectors.toSet());
        if (!keys.containsAll(choices.keySet())) throw new IllegalArgumentException("Unknown migration choice.");
        // Resolve every scope before touching even the detached copy: a later profile
        // conflict must not leave a partially migrated current setup.
        var resolved = new LinkedHashMap<Key,String>();
        for (var entry : plan.entries()) {
            String choice = choices.get(entry.key());
            if (choice == null && !entry.requiresChoice()) choice = entry.variants().getFirst().identity();
            if (choice == null)
                throw new IllegalArgumentException("Choose which settings to keep for " + entry.applicationName() + " in " + entry.scopeName() + ".");
            if (!KEEP_EXACT.equals(choice)) {
                String source = choice;
                if (entry.variants().stream().noneMatch(v -> v.identity().equals(source)))
                    throw new IllegalArgumentException("Invalid migration source.");
            }
            resolved.put(entry.key(), choice);
        }
        for (var entry : plan.entries()) {
            if (!entry.key().scope().equals("shared") || !KEEP_EXACT.equals(resolved.get(entry.key()))) continue;
            boolean combines = plan.entries().stream().anyMatch(other -> !other.key().scope().equals("shared") &&
                    other.key().target().equals(entry.key().target()) && !KEEP_EXACT.equals(resolved.get(other.key())));
            if (combines) throw new IllegalArgumentException("Choose the shared Auto pool and limits for " + entry.applicationName() +
                    ", or keep its exact selections in every scope.");
        }
        var copy = new AppState(); copy.replaceWith(original);
        var historyAliases = copy.applicationHistoryAliases();
        var current = migrate(current(original), "current", plan, resolved, historyAliases);
        copy.appPaths().clear(); copy.appPaths().addAll(current.selected());
        copy.appRoutes().clear(); copy.appRoutes().putAll(current.routes());
        copy.autoApps().clear(); copy.autoApps().addAll(current.auto());
        var shared = migrate(new Setup(List.of(), Map.of(), Set.of(), original.autoPools(), original.bandwidthPolicies(), List.of()),
                "shared", plan, resolved, historyAliases);
        copy.autoPools().clear(); copy.autoPools().putAll(shared.pools());
        copy.bandwidthPolicies().clear(); copy.bandwidthPolicies().putAll(shared.limits());
        copy.routeRules().clear(); copy.routeRules().addAll(current.rules());
        for (var entry : plan.entries()) {
            if (!entry.key().scope().equals("current") || KEEP_EXACT.equals(resolved.get(entry.key()))) continue;
            var selection = entry.conflict() ? original.autoSelections().get(resolved.get(entry.key())) : entry.variants().stream()
                    .map(v -> original.autoSelections().get(v.identity())).filter(Objects::nonNull)
                    .max(Comparator.comparingLong(app.perroute.routing.AutoSelection::changedAt)).orElse(null);
            entry.variants().forEach(v -> copy.autoSelections().remove(v.identity()));
            if (selection != null) copy.autoSelections().put(entry.key().target(), selection);
        }
        copy.profiles().clear();
        for (var profile : original.profiles()) {
            var next = migrate(setup(profile), profile.id(), plan, resolved, historyAliases);
            copy.profiles().add(new ConnectionProfile(profile.id(), profile.name(), next.selected(), profile.nodeKey(),
                    profile.mode(), next.rules(), profile.advancedRouting(), next.routes(), next.auto()));
        }
        // Existing aliases can point at another migrated identity. Flatten them so
        // history lookup remains a single deterministic operation after restart.
        historyAliases.replaceAll((source, target) -> historyIdentity(target, historyAliases));
        return copy;
    }
    private static Setup migrate(Setup original, String scope, Plan plan, Map<Key,String> choices, Map<String,String> history) {
        var selected = new ArrayList<>(original.selected());
        var routes = new LinkedHashMap<>(original.routes()); var auto = new LinkedHashSet<>(original.auto());
        var pools = new LinkedHashMap<>(original.pools()); var limits = new LinkedHashMap<>(original.limits());
        var mapping = new LinkedHashMap<String,String>();
        for (var entry : plan.entries()) {
            if (!entry.key().scope().equals(scope) || KEEP_EXACT.equals(choices.get(entry.key()))) continue;
            var variant = entry.variants().stream().filter(v -> v.identity().equals(choices.get(entry.key()))).findFirst().orElseThrow();
            String target = entry.key().target();
            int insertion = selected.size(); boolean wasSelected = false;
            for (var old : entry.variants()) {
                int index = selected.indexOf(old.identity());
                if (index >= 0) { insertion = Math.min(insertion, index); wasSelected = true; }
                selected.remove(old.identity()); routes.remove(old.identity()); auto.remove(old.identity());
                if (!scope.equals("shared")) { pools.remove(old.identity()); limits.remove(old.identity()); }
                mapping.put(old.identity(), target);
                if (!old.identity().equals(target)) history.put(old.identity(), target);
            }
            if (wasSelected) selected.add(Math.min(insertion, selected.size()), target);
            var settings = variant.settings();
            if (!settings.server().isEmpty()) routes.put(target, settings.server());
            if (settings.auto()) auto.add(target);
            pools.remove(target); limits.remove(target);
            if (!settings.pool().equals("all")) pools.put(target, settings.pool());
            if (!settings.limit().unlimited()) limits.put(target, settings.limit());
        }
        var rules = original.rules().stream().map(rule -> new RouteRule(rule.id(), rule.name(),
                mapping.getOrDefault(rule.applicationPath(), rule.applicationPath()), rule.matchType(), rule.value(), rule.action(), rule.enabled())).toList();
        return new Setup(selected.stream().distinct().toList(), routes, auto, pools, limits, rules);
    }
    public static String historyIdentity(String identity, Map<String,String> aliases) {
        var seen = new HashSet<String>();
        String current = identity;
        while (aliases.containsKey(current) && seen.add(current)) current = aliases.get(current);
        if (aliases.containsKey(current)) throw new IllegalArgumentException("Cyclic application history aliases.");
        return current;
    }
}
