package app.perroute.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public final class AppState {
    private final List<ProxyNode> nodes = new ArrayList<>();
    private final List<String> appPaths = new ArrayList<>();
    private final List<String> subscriptionUrls = new ArrayList<>();
    private final Map<String, String> nodeSources = new LinkedHashMap<>();
    private final Set<String> favoriteNodeKeys = new LinkedHashSet<>();
    private final Set<String> hiddenNodeKeys = new LinkedHashSet<>();
    private final Map<String, String> nodeTags = new LinkedHashMap<>();
    private final List<RouteRule> routeRules = new ArrayList<>();
    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private final Map<String, String> appRoutes = new LinkedHashMap<>();
    private final Map<String, String> appRouteLabels = new LinkedHashMap<>();
    private final Set<String> autoApps = new LinkedHashSet<>();
    private final Map<String, String> autoPools = new LinkedHashMap<>();
    private final Map<String, app.perroute.routing.AutoSelection> autoSelections = new LinkedHashMap<>();
    private final Map<String, BandwidthPolicy> bandwidthPolicies = new LinkedHashMap<>();
    private final Map<String, String> applicationHistoryAliases = new LinkedHashMap<>();
    private String activeProfileId = "";
    private ConnectionMode connectionMode = ConnectionMode.SYSTEM_PROXY;
    private RoutingMode routingMode = RoutingMode.INCLUDE_SELECTED;
    private String selectedNodeKey = "";
    private boolean advancedRouting;
    private boolean migratedFromV2;

    public List<ProxyNode> nodes() { return nodes; }
    public List<String> appPaths() { return appPaths; }
    public List<String> subscriptionUrls() { return subscriptionUrls; }
    public Map<String, String> nodeSources() { return nodeSources; }
    public Set<String> favoriteNodeKeys() { return favoriteNodeKeys; }
    public Set<String> hiddenNodeKeys() { return hiddenNodeKeys; }
    public Map<String, String> nodeTags() { return nodeTags; }
    public List<RouteRule> routeRules() { return routeRules; }
    public List<ConnectionProfile> profiles() { return profiles; }
    public Map<String, String> appRoutes() { return appRoutes; }
    public Map<String, String> appRouteLabels() { return appRouteLabels; }
    public String activeProfileId() { return activeProfileId; }
    public void setActiveProfileId(String value) { activeProfileId = value == null ? "" : value; }
    public ConnectionMode connectionMode() { return connectionMode; }
    public void setConnectionMode(ConnectionMode value) { connectionMode = value == null ? ConnectionMode.SYSTEM_PROXY : value; }
    public RoutingMode routingMode() { return routingMode; }
    public void setRoutingMode(RoutingMode value) { routingMode = value == null ? RoutingMode.INCLUDE_SELECTED : value; }
    public String selectedNodeKey() { return selectedNodeKey; }
    public void setSelectedNodeKey(String value) { selectedNodeKey = value == null ? "" : value; }
    public boolean advancedRouting() { return advancedRouting; }
    public void setAdvancedRouting(boolean value) { advancedRouting = value; }
    public Set<String> autoApps() { return autoApps; }
    public Map<String, String> autoPools() { return autoPools; }
    public Map<String, app.perroute.routing.AutoSelection> autoSelections() { return autoSelections; }
    public Map<String, BandwidthPolicy> bandwidthPolicies() { return bandwidthPolicies; }
    public Map<String, String> applicationHistoryAliases() { return applicationHistoryAliases; }
    public boolean migratedFromV2() { return migratedFromV2; }
    public void setMigratedFromV2(boolean value) { migratedFromV2 = value; }

    public void replaceWith(AppState other) {
        nodes.clear(); nodes.addAll(other.nodes);
        appPaths.clear(); appPaths.addAll(other.appPaths);
        subscriptionUrls.clear(); subscriptionUrls.addAll(other.subscriptionUrls);
        nodeSources.clear(); nodeSources.putAll(other.nodeSources);
        favoriteNodeKeys.clear(); favoriteNodeKeys.addAll(other.favoriteNodeKeys);
        hiddenNodeKeys.clear(); hiddenNodeKeys.addAll(other.hiddenNodeKeys);
        nodeTags.clear(); nodeTags.putAll(other.nodeTags);
        routeRules.clear(); routeRules.addAll(other.routeRules);
        profiles.clear(); profiles.addAll(other.profiles);
        appRoutes.clear(); appRoutes.putAll(other.appRoutes);
        appRouteLabels.clear(); appRouteLabels.putAll(other.appRouteLabels);
        autoApps.clear(); autoApps.addAll(other.autoApps);
        autoPools.clear(); autoPools.putAll(other.autoPools);
        autoSelections.clear(); autoSelections.putAll(other.autoSelections);
        bandwidthPolicies.clear(); bandwidthPolicies.putAll(other.bandwidthPolicies);
        applicationHistoryAliases.clear(); applicationHistoryAliases.putAll(other.applicationHistoryAliases);
        activeProfileId = other.activeProfileId;
        connectionMode = other.connectionMode;
        routingMode = other.routingMode;
        selectedNodeKey = other.selectedNodeKey;
        advancedRouting = other.advancedRouting;
        migratedFromV2 = other.migratedFromV2;
    }
}
