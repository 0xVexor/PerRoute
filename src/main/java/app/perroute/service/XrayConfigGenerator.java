package app.perroute.service;

import app.perroute.model.ApplicationIdentity;

import app.perroute.model.Protocol;
import app.perroute.model.ProxyNode;
import app.perroute.model.RoutingMode;
import app.perroute.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class XrayConfigGenerator {
    public static final int STATS_API_PORT = 17900 + PortOffset.VALUE;
    public static final int PER_APP_PORT_BASE = 18100 + PortOffset.VALUE;
    public static final int ROUTE_CHECK_PORT_BASE = 18600 + PortOffset.VALUE;
    public static final int GATEWAY_UPSTREAM_PORT_BASE = 19100 + PortOffset.VALUE;
    public static final String MAIN_OUTBOUND_TAG = "proxy";
    public static final String QUIC_BLOCK_RULE = "perroute-block-quic";

    private final int appPortBase;
    private final boolean runtimeControl;
    private boolean localNamesDirect;
    private List<String> localAllowed = List.of();
    private List<String> localBlocked = List.of();
    private boolean quicBlocked;

    public XrayConfigGenerator withLocalNetwork(boolean namesDirect, List<String> allowed, List<String> blocked) {
        this.localNamesDirect = namesDirect;
        this.localAllowed = allowed == null ? List.of() : List.copyOf(allowed);
        this.localBlocked = blocked == null ? List.of() : List.copyOf(blocked);
        return this;
    }

    public XrayConfigGenerator withQuicBlocked(boolean blocked) {
        this.quicBlocked = blocked;
        return this;
    }

    private List<Object> localRules(String directTag, String blockTag) {
        List<Object> rules = new ArrayList<>();
        if (!localBlocked.isEmpty()) {
            if (!localAllowed.isEmpty()) rules.add(Map.of("type", "field", "ip", localAllowed, "outboundTag", directTag, "ruleTag", "perroute-local-allowed"));
            rules.add(Map.of("type", "field", "ip", localBlocked, "outboundTag", blockTag, "ruleTag", "perroute-local-blocked"));
        }
        rules.add(Map.of("type", "field", "ip", List.of("geoip:private"), "outboundTag", directTag, "ruleTag", "perroute-private-direct"));
        if (localNamesDirect) rules.add(Map.of("type", "field", "domain", app.perroute.localnet.LocalNetworkPolicy.LOCAL_NAME_RULES,
                "outboundTag", directTag, "ruleTag", "perroute-local-names"));
        if (quicBlocked) rules.add(Map.of("type", "field", "network", "udp", "port", "443", "outboundTag", blockTag, "ruleTag", QUIC_BLOCK_RULE));
        return rules;
    }

    public XrayConfigGenerator() { this(PER_APP_PORT_BASE, false); }

    public XrayConfigGenerator(int appPortBase) { this(appPortBase, false); }

    public XrayConfigGenerator(int appPortBase, boolean runtimeControl) {
        if (appPortBase != PER_APP_PORT_BASE && appPortBase != GATEWAY_UPSTREAM_PORT_BASE) {
            throw new IllegalArgumentException("Unsupported per-app port base " + appPortBase);
        }
        this.appPortBase = appPortBase;
        this.runtimeControl = runtimeControl;
    }

    public static int gatewayUpstreamPort(int index) {
        if (index < 0 || index >= 500) throw new IllegalArgumentException("Too many selected applications");
        return GATEWAY_UPSTREAM_PORT_BASE + index;
    }

    public String generateApplicationProxy(ProxyNode node) {
        return generateApplicationProxy(node, List.of());
    }

    public String generateApplicationProxy(ProxyNode node, List<String> applicationPaths) {
        return generateApplicationProxy(node, applicationPaths, List.of());
    }

    public String generateApplicationProxy(ProxyNode node, List<String> applicationPaths,
                                           List<app.perroute.model.RouteRule> routeRules) {
        return generateApplicationProxy(node, applicationPaths, routeRules, List.of());
    }

    public String generateApplicationProxy(ProxyNode node, List<String> applicationPaths,
                                           List<app.perroute.model.RouteRule> routeRules,
                                           List<ProxyNode> applicationNodes) {
        if (node == null) throw new IllegalArgumentException("A server must be selected");
        List<String> apps = applicationPaths == null ? List.of() : List.copyOf(applicationPaths);
        RouteLayout layout = RouteLayout.of(node, apps, applicationNodes);
        if (node.protocol() == Protocol.XRAY_PROFILE) return generateApplicationProxyProfile(node, apps, routeRules, layout);

        Map<String, Object> root = map();
        root.put("log", Map.of("loglevel", "info"));
        List<Object> inbounds = applicationProxyInbounds(apps, layout.customs().size());
        root.put("inbounds", inbounds);
        List<Object> outbounds = new ArrayList<>(List.of(proxyOutbound(node, "proxy"), directOutbound(), blockOutbound()));
        List<CustomRoute> customs = customRoutes(layout, outbounds, List.of());
        root.put("outbounds", outbounds);
        List<RouteGroup> groups = layout.groups(apps, new Target("outboundTag", "proxy"),
                customs.stream().map(CustomRoute::target).toList());

        List<Object> rules = new ArrayList<>(localRules("direct", "block"));
        rules.addAll(customProfileRules(customs, groups));
        rules.addAll(customRules(apps, routeRules, "direct", groups, "block"));
        rules.addAll(applicationRules(groups));
        enableStats(root, inbounds, rules, runtimeControl);
        Map<String, Object> routing = map();
        routing.put("domainStrategy", "IPIfNonMatch");
        routing.put("rules", rules);
        mergeCustomSections(root, routing, customs);
        root.put("routing", routing);
        return Json.stringify(root) + System.lineSeparator();
    }

    public static List<String> customRouteKeys(ProxyNode main, List<String> applicationPaths, List<ProxyNode> applicationNodes) {
        List<String> apps = applicationPaths == null ? List.of() : applicationPaths;
        return RouteLayout.of(main, apps, applicationNodes).customs().stream().map(ProxyNode::key).toList();
    }

    public static String applicationRouteRuleTag(int index) { return index < 0 ? "perroute-app-proxy" : "perroute-app-route-" + index; }

    @SuppressWarnings("unchecked")
    public static Map<String, String> applicationRouteTargets(String config) {
        Map<String, String> targets = new LinkedHashMap<>();
        Map<String, Object> root = Json.object(config);
        if (!(root.get("routing") instanceof Map<?, ?> routing) || !(routing.get("rules") instanceof List<?> rules)) return targets;
        for (Object raw : rules) {
            if (!(raw instanceof Map<?, ?> rule)) continue;
            String tag = objectString(rule.get("ruleTag"));
            String outbound = objectString(rule.get("outboundTag"));
            if ((tag.equals("perroute-app-proxy") || tag.startsWith("perroute-app-route-")) && !outbound.isBlank()) targets.put(tag, outbound);
        }
        return targets;
    }

    public record RuntimeChange(String outbounds, String routing) {}

    public record RuntimePlan(String inbounds, String outbounds, String routing, List<String> removeOutbounds,
                              List<String> removeInbounds, List<String> addedInboundTags, List<String> addedOutboundTags, String config) {}

    @SuppressWarnings("unchecked")
    public static RuntimePlan runtimePlan(String running, String next, Map<String, String> keep) {
        Map<String, Object> live = Json.object(running);
        Map<String, Object> planned = Json.object(next);
        for (Map<String, Object> root : List.of(live, planned)) {
            if (root.get("routing") instanceof Map<?, ?> routing && routing.get("balancers") instanceof List<?> balancers && !balancers.isEmpty()) return null;
            if (root.containsKey("observatory") || root.containsKey("burstObservatory")) return null;
        }
        Map<String, Map<String, Object>> liveOutbounds = byTag(live.get("outbounds"));
        Map<String, Map<String, Object>> liveInbounds = byTag(live.get("inbounds"));
        if (liveOutbounds == null || liveInbounds == null) return null;
        String candidate = next;
        Map<String, Map<String, Object>> plannedOutbounds = byTag(planned.get("outbounds"));
        if (plannedOutbounds == null) return null;
        Map<String, String> placeholders = new LinkedHashMap<>();
        int counter = 0;
        for (String tag : plannedOutbounds.keySet()) {
            if (!isRouteOutbound(tag)) continue;
            String placeholder = "~perroute-pending-" + counter++;
            placeholders.put(tag, placeholder);
            candidate = renameOutbound(candidate, tag, placeholder);
        }
        java.util.Set<String> taken = new java.util.HashSet<>(liveOutbounds.keySet());
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String liveTag = keep.get(entry.getKey());
            String target;
            if (liveTag != null && liveOutbounds.containsKey(liveTag)) target = liveTag;
            else {
                String base = entry.getKey().replaceFirst("~\\d+$", "");
                target = base;
                int suffix = 1;
                while (taken.contains(target)) target = base + "~" + suffix++;
                taken.add(target);
            }
            candidate = renameOutbound(candidate, entry.getValue(), target);
        }
        Map<String, Object> result = Json.object(candidate);
        Map<String, Map<String, Object>> nextOutbounds = byTag(result.get("outbounds"));
        Map<String, Map<String, Object>> nextInbounds = byTag(result.get("inbounds"));
        if (nextOutbounds == null || nextInbounds == null) return null;
        List<Object> addOutbounds = new ArrayList<>();
        List<String> addedOutboundTags = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : nextOutbounds.entrySet()) {
            Map<String, Object> before = liveOutbounds.get(entry.getKey());
            if (before == null) {
                if (!isRouteOutbound(entry.getKey())) return null;
                String protocol = objectString(entry.getValue().get("protocol")).toLowerCase(Locale.ROOT);
                if (!List.of("vless", "vmess", "trojan", "shadowsocks").contains(protocol) || entry.getValue().containsKey("proxySettings")) return null;
                addOutbounds.add(entry.getValue());
                addedOutboundTags.add(entry.getKey());
            } else if (!Json.stringify(before).equals(Json.stringify(entry.getValue()))) {
                return null;
            }
        }
        List<String> removeOutbounds = new ArrayList<>();
        for (String tag : liveOutbounds.keySet()) {
            if (nextOutbounds.containsKey(tag)) continue;
            if (!isRouteOutbound(tag)) return null;
            removeOutbounds.add(tag);
        }
        List<Object> addInbounds = new ArrayList<>();
        List<String> addedInboundTags = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : nextInbounds.entrySet()) {
            Map<String, Object> before = liveInbounds.get(entry.getKey());
            if (before == null) {
                if (!entry.getKey().startsWith("perroute-check-")) return null;
                addInbounds.add(entry.getValue());
                addedInboundTags.add(entry.getKey());
            } else if (!Json.stringify(before).equals(Json.stringify(entry.getValue()))) {
                return null;
            }
        }
        List<String> removeInbounds = new ArrayList<>();
        for (String tag : liveInbounds.keySet()) {
            if (nextInbounds.containsKey(tag)) continue;
            if (!tag.startsWith("perroute-check-")) return null;
            removeInbounds.add(tag);
        }
        for (String section : List.of("api", "stats", "policy", "dns", "log", "fakedns")) {
            if (!Json.stringify(live.get(section)).equals(Json.stringify(result.get(section)))) return null;
        }
        if (!(result.get("routing") instanceof Map<?, ?> routing) || !(routing.get("rules") instanceof List<?> rules)) return null;
        if (live.get("routing") instanceof Map<?, ?> liveRouting
                && !objectString(liveRouting.get("domainStrategy")).equals(objectString(routing.get("domainStrategy")))) return null;
        return new RuntimePlan(
                addInbounds.isEmpty() ? null : Json.stringify(Map.of("inbounds", addInbounds)) + System.lineSeparator(),
                addOutbounds.isEmpty() ? null : Json.stringify(Map.of("outbounds", addOutbounds)) + System.lineSeparator(),
                Json.stringify(Map.of("routing", Map.of("rules", rules))) + System.lineSeparator(),
                List.copyOf(removeOutbounds), List.copyOf(removeInbounds), List.copyOf(addedInboundTags), List.copyOf(addedOutboundTags),
                Json.stringify(result) + System.lineSeparator());
    }

    private static boolean isRouteOutbound(String tag) {
        return tag.equals(MAIN_OUTBOUND_TAG) || tag.startsWith(MAIN_OUTBOUND_TAG + "~") || tag.startsWith("perroute-route-");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byTag(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) return null;
            String tag = objectString(map.get("tag"));
            if (tag.isBlank() || result.containsKey(tag)) return null;
            result.put(tag, (Map<String, Object>) map);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static RuntimeChange runtimeChange(String config, String tag) {
        Map<String, Object> root = Json.object(config);
        if (!(root.get("routing") instanceof Map<?, ?> routing) || !(routing.get("rules") instanceof List<?> rules)) return null;
        if (routing.get("balancers") instanceof List<?> balancers && !balancers.isEmpty()) return null;
        if (!(root.get("outbounds") instanceof List<?> outbounds)) return null;
        Map<String, Object> outbound = null;
        for (Object raw : outbounds) {
            if (raw instanceof Map<?, ?> map && tag.equals(objectString(map.get("tag")))) outbound = (Map<String, Object>) map;
        }
        if (outbound == null) return null;
        String protocol = objectString(outbound.get("protocol")).toLowerCase(Locale.ROOT);
        if (!List.of("vless", "vmess", "trojan", "shadowsocks").contains(protocol) || outbound.containsKey("proxySettings")) return null;
        return new RuntimeChange(Json.stringify(Map.of("outbounds", List.of(outbound))) + System.lineSeparator(),
                Json.stringify(Map.of("routing", Map.of("rules", rules))) + System.lineSeparator());
    }

    @SuppressWarnings("unchecked")
    public static String renameOutbound(String config, String from, String to) {
        Map<String, Object> root = Json.object(config);
        if (root.get("outbounds") instanceof List<?> outbounds) {
            for (Object raw : outbounds) {
                if (raw instanceof Map<?, ?> map && from.equals(objectString(map.get("tag")))) ((Map<String, Object>) map).put("tag", to);
            }
        }
        if (root.get("routing") instanceof Map<?, ?> routing && routing.get("rules") instanceof List<?> rules) {
            for (Object raw : rules) {
                if (raw instanceof Map<?, ?> rule && from.equals(objectString(rule.get("outboundTag")))) ((Map<String, Object>) rule).put("outboundTag", to);
            }
        }
        return Json.stringify(root) + System.lineSeparator();
    }

    public String generateProbe(ProxyNode node, int socksPort, int apiPort) {
        return generateIsolated(node, socksPort, apiPort, List.of());
    }

    @SuppressWarnings("unchecked")
    public String generateIsolated(ProxyNode node, int socksPort, int apiPort, List<app.perroute.model.RouteRule> globalRules) {
        List<app.perroute.model.RouteRule> rules = globalRules == null ? List.of()
                : globalRules.stream().filter(rule -> rule != null && rule.applicationPath().isBlank()).toList();
        Map<String, Object> root = Json.object(generateApplicationProxy(node, List.of(), rules));
        List<Object> inbounds = (List<Object>)root.get("inbounds");
        inbounds.removeIf(value -> value instanceof Map<?, ?> map && "perroute-http".equals(map.get("tag")));
        for (Object value : inbounds) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> inbound = (Map<String, Object>)raw;
            if ("perroute-socks".equals(inbound.get("tag"))) inbound.put("port", socksPort);
            if ("perroute-api".equals(inbound.get("tag"))) inbound.put("port", apiPort);
        }
        return Json.stringify(root) + System.lineSeparator();
    }

    public record BridgeInbound(String tag, int port, String user, String password) {}

    public static final String BRIDGE_PRIVATE_RULE = "perroute-bridge-private";
    public static final String BRIDGE_LOCAL_NAMES_RULE = "perroute-bridge-local-names";

    @SuppressWarnings("unchecked")
    public String generateBridge(ProxyNode node, String listen, List<BridgeInbound> devices, int apiPort) {
        if (listen == null || listen.isBlank()) throw new IllegalArgumentException("Choose the network to share on");
        if (devices == null || devices.isEmpty()) throw new IllegalArgumentException("Add a device first");
        Map<String, Object> root = Json.object(generateApplicationProxy(node, List.of(), List.of()));
        List<Object> inbounds = new ArrayList<>();
        Object api = ((List<Object>) root.get("inbounds")).stream()
                .filter(value -> value instanceof Map<?, ?> map && "perroute-api".equals(map.get("tag"))).findFirst().orElse(null);
        if (api instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) raw);
            copy.put("port", apiPort);
            inbounds.add(copy);
        }
        List<String> tags = new ArrayList<>();
        for (BridgeInbound device : devices) {
            Map<String, Object> settings = map();
            settings.put("auth", "password");
            settings.put("accounts", List.of(Map.of("user", device.user(), "pass", device.password())));
            settings.put("udp", true);
            settings.put("ip", listen);
            Map<String, Object> sniffing = map();
            sniffing.put("enabled", true);
            sniffing.put("destOverride", List.of("http", "tls"));
            sniffing.put("routeOnly", true);
            Map<String, Object> inbound = map();
            inbound.put("tag", device.tag());
            inbound.put("listen", listen);
            inbound.put("port", device.port());
            inbound.put("protocol", "mixed");
            inbound.put("settings", settings);
            inbound.put("sniffing", sniffing);
            inbounds.add(inbound);
            tags.add(device.tag());
        }
        root.put("inbounds", inbounds);
        String blockTag = ((List<Object>) root.get("outbounds")).stream()
                .filter(value -> value instanceof Map<?, ?> map && "blackhole".equals(map.get("protocol")))
                .map(value -> String.valueOf(((Map<?, ?>) value).get("tag"))).findFirst().orElse("block");
        Map<String, Object> routing = (Map<String, Object>) root.get("routing");
        List<Object> rules = new ArrayList<>(routing.get("rules") instanceof List<?> list ? list : List.of());
        rules.add(0, Map.of("type", "field", "inboundTag", tags, "outboundTag", blockTag, "ruleTag", BRIDGE_LOCAL_NAMES_RULE,
                "domain", List.of("full:localhost", "domain:local", "domain:lan", "domain:home.arpa", "domain:internal", "domain:localdomain")));
        rules.add(0, Map.of("type", "field", "inboundTag", tags, "outboundTag", blockTag, "ruleTag", BRIDGE_PRIVATE_RULE,
                "ip", List.of("geoip:private", "127.0.0.0/8", "0.0.0.0/8", "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10")));
        routing.put("rules", rules);
        return Json.stringify(root) + System.lineSeparator();
    }

    public String generate(ProxyNode node, List<String> applicationPaths, RoutingMode mode) {
        if (node == null) throw new IllegalArgumentException("A server must be selected");
        if (applicationPaths == null || applicationPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one application must be selected");
        }
        if (node.protocol() == Protocol.XRAY_PROFILE) {
            return generateXrayProfile(node, applicationPaths, mode);
        }

        Map<String, Object> root = map();
        root.put("log", Map.of("loglevel", "info"));
        root.put("inbounds", List.of(tunInbound()));
        root.put("outbounds", List.of(proxyOutbound(node, "proxy"), directOutbound(), blockOutbound()));
        root.put("routing", routing(applicationPaths, mode));
        return Json.stringify(root) + System.lineSeparator();
    }

    private List<Object> applicationProxyInbounds(List<String> applicationPaths, int customRoutes) {
        List<Object> inbounds = new ArrayList<>();
        Map<String, Object> sniffing = map();
        sniffing.put("enabled", true);
        sniffing.put("destOverride", List.of("http", "tls", "quic"));
        sniffing.put("routeOnly", true);

        Map<String, Object> socks = map();
        socks.put("listen", "127.0.0.1");
        socks.put("port", AppProxyLauncher.SOCKS_PORT);
        socks.put("protocol", "socks");
        socks.put("tag", "perroute-socks");
        socks.put("settings", Map.of("udp", true));
        socks.put("sniffing", sniffing);

        Map<String, Object> http = map();
        http.put("listen", "127.0.0.1");
        http.put("port", AppProxyLauncher.HTTP_PORT);
        http.put("protocol", "http");
        http.put("tag", "perroute-http");
        http.put("settings", Map.of());
        http.put("sniffing", sniffing);
        inbounds.add(socks);
        inbounds.add(http);
        for (int index = 0; index < applicationPaths.size(); index++) {
            Map<String, Object> app = map();
            app.put("listen", "127.0.0.1");
            app.put("port", appPortBase == PER_APP_PORT_BASE ? perAppPort(index) : gatewayUpstreamPort(index));
            app.put("protocol", "socks");
            app.put("tag", perAppTag(index));
            app.put("settings", Map.of("udp", true));
            if (ApplicationIdentity.isMac(applicationPaths.get(index))) {
                // Transparent capture supplies a locally resolved IP. Preserve the HTTP/TLS hostname
                // for the proxy server instead of requiring that local DNS result to be trustworthy.
                Map<String, Object> transparentSniffing = new LinkedHashMap<>(sniffing);
                transparentSniffing.put("routeOnly", false);
                app.put("sniffing", transparentSniffing);
            } else {
                app.put("sniffing", sniffing);
            }
            inbounds.add(app);
        }
        for (int index = 0; index < customRoutes; index++) {
            Map<String, Object> check = map();
            check.put("listen", "127.0.0.1");
            check.put("port", routeCheckPort(index));
            check.put("protocol", "socks");
            check.put("tag", routeCheckTag(index));
            check.put("settings", Map.of("udp", false));
            inbounds.add(check);
        }
        return inbounds;
    }

    public static int perAppPort(int index) {
        if (index < 0 || index >= 500) throw new IllegalArgumentException("Too many selected applications");
        return PER_APP_PORT_BASE + index;
    }

    public static String perAppTag(int index) { return "perroute-app-" + index; }

    public static int routeCheckPort(int index) {
        if (index < 0 || index >= 500) throw new IllegalArgumentException("Too many custom servers");
        return ROUTE_CHECK_PORT_BASE + index;
    }

    public static String routeCheckTag(int index) { return "perroute-check-" + index; }
    public static String routeOutboundTag(int index) { return "perroute-route-" + index; }
    public static String routeRuleSuffix(int index) { return "#route" + index; }

    private static List<String> proxyInboundTags(List<String> applications) {
        List<String> tags = new ArrayList<>(List.of("perroute-socks", "perroute-http"));
        for (int index = 0; index < applications.size(); index++) tags.add(perAppTag(index));
        return tags;
    }

    private record RouteGroup(Target target, List<String> inboundTags, String ruleSuffix, int customIndex) {}

    private record RouteLayout(List<ProxyNode> customs, int[] applicationGroup) {
        static RouteLayout of(ProxyNode main, List<String> applications, List<ProxyNode> applicationNodes) {
            int[] groups = new int[applications.size()];
            java.util.Arrays.fill(groups, -1);
            List<ProxyNode> customs = new ArrayList<>();
            if (applicationNodes == null || applicationNodes.isEmpty()) return new RouteLayout(customs, groups);
            if (applicationNodes.size() != applications.size()) {
                throw new IllegalArgumentException("Every application needs exactly one server");
            }
            for (int index = 0; index < applications.size(); index++) {
                ProxyNode node = applicationNodes.get(index);
                if (node == null || (main != null && node.key().equals(main.key()))) continue;
                int position = -1;
                for (int known = 0; known < customs.size(); known++) {
                    if (customs.get(known).key().equals(node.key())) position = known;
                }
                if (position < 0) { customs.add(node); position = customs.size() - 1; }
                groups[index] = position;
            }
            return new RouteLayout(customs, groups);
        }

        List<RouteGroup> groups(List<String> applications, Target mainTarget, List<Target> customTargets) {
            List<String> mainTags = new ArrayList<>(List.of("perroute-socks", "perroute-http"));
            List<List<String>> customInbounds = new ArrayList<>();
            for (int index = 0; index < customs.size(); index++) customInbounds.add(new ArrayList<>());
            for (int index = 0; index < applications.size(); index++) {
                if (applicationGroup[index] < 0) mainTags.add(perAppTag(index));
                else customInbounds.get(applicationGroup[index]).add(perAppTag(index));
            }
            List<RouteGroup> result = new ArrayList<>();
            result.add(new RouteGroup(mainTarget, mainTags, "", -1));
            for (int index = 0; index < customs.size(); index++) {
                result.add(new RouteGroup(customTargets.get(index), customInbounds.get(index), routeRuleSuffix(index), index));
            }
            return result;
        }
    }

    private record CustomRoute(List<Object> outbounds, Target target, List<Map<String, Object>> rules,
                               List<Object> balancers, Map<String, Object> observatory,
                               Map<String, Object> burstObservatory) {}

    private List<CustomRoute> customRoutes(RouteLayout layout, List<Object> outbounds, List<String> mainSelectors) {
        List<CustomRoute> routes = new ArrayList<>();
        for (int index = 0; index < layout.customs().size(); index++) {
            String base = uniqueTag(outbounds, routeBase(index, mainSelectors));
            CustomRoute route = customRoute(layout.customs().get(index), base);
            outbounds.addAll(route.outbounds());
            routes.add(route);
        }
        return routes;
    }

    private static String routeBase(int index, List<String> mainSelectors) {
        for (String candidate : List.of(routeOutboundTag(index), "~" + routeOutboundTag(index), "zz." + routeOutboundTag(index))) {
            if (mainSelectors.stream().noneMatch(candidate::startsWith)) return candidate;
        }
        throw new IllegalArgumentException("The main server's profile selects every outbound, so it cannot share "
                + "the connection with a custom server. Choose another main server or turn off Advanced routing.");
    }

    @SuppressWarnings("unchecked")
    private CustomRoute customRoute(ProxyNode node, String base) {
        if (node.protocol() != Protocol.XRAY_PROFILE) {
            return new CustomRoute(List.of(proxyOutbound(node, base)), new Target("outboundTag", base),
                    List.of(), List.of(), null, null);
        }
        String raw = node.option("rawProfileJson");
        if (raw.isBlank()) throw new IllegalArgumentException("Embedded Xray profile is empty");
        Map<String, Object> profile = Json.object(raw);
        if (!(profile.get("outbounds") instanceof List<?> source) || source.isEmpty()) {
            throw new IllegalArgumentException("Embedded Xray profile has no outbounds");
        }
        List<Object> own = new ArrayList<>(source);
        Map<String, Object> routing = profile.get("routing") instanceof Map<?, ?> map ? (Map<String, Object>)map : Map.of();
        List<Object> ownRules = routing.get("rules") instanceof List<?> list ? new ArrayList<>(list) : List.of();
        Target ownTarget = findProfileTarget(own, ownRules);
        String namespace = base + "/";

        List<Object> outbounds = new ArrayList<>();
        for (Object value : own) {
            if (!(value instanceof Map<?, ?> rawOutbound)) continue;
            Map<String, Object> outbound = new LinkedHashMap<>((Map<String, Object>)rawOutbound);
            renameTag(outbound, "tag", namespace);
            if (outbound.get("proxySettings") instanceof Map<?, ?> proxySettings) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>)proxySettings);
                renameTag(copy, "tag", namespace);
                outbound.put("proxySettings", copy);
            }
            if (outbound.get("streamSettings") instanceof Map<?, ?> stream && stream.get("sockopt") instanceof Map<?, ?> sockopt) {
                Map<String, Object> sockoptCopy = new LinkedHashMap<>((Map<String, Object>)sockopt);
                renameTag(sockoptCopy, "dialerProxy", namespace);
                Map<String, Object> streamCopy = new LinkedHashMap<>((Map<String, Object>)stream);
                streamCopy.put("sockopt", sockoptCopy);
                outbound.put("streamSettings", streamCopy);
            }
            outbounds.add(outbound);
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Object value : ownRules) {
            if (!(value instanceof Map<?, ?> rawRule) || rawRule.containsKey("inboundTag")) continue;
            Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>)rawRule);
            renameTag(rule, "outboundTag", namespace);
            renameTag(rule, "balancerTag", namespace);
            renameTag(rule, "ruleTag", namespace);
            rules.add(rule);
        }

        List<Object> balancers = new ArrayList<>();
        if (routing.get("balancers") instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> rawBalancer)) continue;
                Map<String, Object> balancer = new LinkedHashMap<>((Map<String, Object>)rawBalancer);
                renameTag(balancer, "tag", namespace);
                renameTag(balancer, "fallbackTag", namespace);
                renameSelectors(balancer, "selector", namespace);
                balancers.add(balancer);
            }
        }
        return new CustomRoute(outbounds, new Target(ownTarget.key, namespace + ownTarget.value), rules, balancers,
                observer(profile.get("observatory"), namespace), observer(profile.get("burstObservatory"), namespace));
    }

    private static void renameTag(Map<String, Object> target, String key, String namespace) {
        String value = objectString(target.get(key));
        if (!value.isBlank()) target.put(key, namespace + value);
    }

    private static void renameSelectors(Map<String, Object> target, String key, String namespace) {
        if (target.get(key) instanceof List<?> list) {
            target.put(key, list.stream().map(item -> (Object)(namespace + item)).toList());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> observer(Object raw, String namespace) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>)map);
        renameSelectors(copy, "subjectSelector", namespace);
        return copy;
    }

    private static List<Object> customProfileRules(List<CustomRoute> customs, List<RouteGroup> groups) {
        List<Object> rules = new ArrayList<>();
        for (int index = 0; index < customs.size(); index++) {
            for (Map<String, Object> rule : customs.get(index).rules()) {
                Map<String, Object> confined = new LinkedHashMap<>(rule);
                confined.put("inboundTag", groups.get(index + 1).inboundTags());
                rules.add(confined);
            }
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static void mergeCustomSections(Map<String, Object> root, Map<String, Object> routing, List<CustomRoute> customs) {
        List<Object> balancers = routing.get("balancers") instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        customs.forEach(custom -> balancers.addAll(custom.balancers()));
        if (!balancers.isEmpty()) routing.put("balancers", balancers);
        for (String section : List.of("observatory", "burstObservatory")) {
            List<Map<String, Object>> parts = customs.stream()
                    .map(custom -> section.equals("observatory") ? custom.observatory() : custom.burstObservatory())
                    .filter(java.util.Objects::nonNull).toList();
            if (parts.isEmpty()) continue;
            Map<String, Object> merged = new LinkedHashMap<>(root.get(section) instanceof Map<?, ?> existing
                    ? (Map<String, Object>)existing : parts.get(0));
            List<Object> selectors = new ArrayList<>();
            if (root.get(section) instanceof Map<?, ?> existing && existing.get("subjectSelector") instanceof List<?> list) {
                selectors.addAll(list);
            }
            for (Map<String, Object> part : parts) {
                if (part.get("subjectSelector") instanceof List<?> list) selectors.addAll(list);
            }
            merged.put("subjectSelector", selectors);
            root.put(section, merged);
        }
    }

    private static List<String> profileSelectors(Map<String, Object> profile) {
        List<String> selectors = new ArrayList<>();
        if (profile.get("routing") instanceof Map<?, ?> routing && routing.get("balancers") instanceof List<?> balancers) {
            for (Object value : balancers) {
                if (value instanceof Map<?, ?> balancer && balancer.get("selector") instanceof List<?> list) {
                    list.forEach(item -> selectors.add(String.valueOf(item)));
                }
            }
        }
        for (String section : List.of("observatory", "burstObservatory")) {
            if (profile.get(section) instanceof Map<?, ?> observer && observer.get("subjectSelector") instanceof List<?> list) {
                list.forEach(item -> selectors.add(String.valueOf(item)));
            }
        }
        return selectors;
    }

    private static List<Object> applicationRules(List<RouteGroup> groups) {
        List<Object> rules = new ArrayList<>();
        for (RouteGroup group : groups) {
            Map<String, Object> rule = map();
            rule.put("type", "field");
            List<String> inboundTags = new ArrayList<>(group.inboundTags());
            if (group.customIndex() >= 0) inboundTags.add(routeCheckTag(group.customIndex()));
            rule.put("inboundTag", inboundTags);
            rule.put("network", "tcp,udp");
            rule.put(group.target().key, group.target().value);
            rule.put("ruleTag", group.customIndex() < 0 ? "perroute-app-proxy" : "perroute-app-route-" + group.customIndex());
            rules.add(rule);
        }
        return rules;
    }

    private static void enableStats(Map<String, Object> root, List<Object> inbounds, List<Object> rules, boolean runtimeControl) {
        Map<String, Object> apiInbound = map();
        apiInbound.put("listen", "127.0.0.1");
        apiInbound.put("port", STATS_API_PORT);
        apiInbound.put("protocol", "dokodemo-door");
        apiInbound.put("tag", "perroute-api");
        apiInbound.put("settings", Map.of("address", "127.0.0.1"));
        inbounds.add(apiInbound);
        rules.add(0, Map.of("type", "field", "inboundTag", List.of("perroute-api"),
                "outboundTag", "api", "ruleTag", "perroute-api-route"));
        root.put("api", Map.of("tag", "api", "services", runtimeControl
                ? List.of("StatsService", "HandlerService", "RoutingService") : List.of("StatsService")));
        root.put("stats", Map.of());
        root.put("policy", Map.of("system", Map.of(
                "statsInboundUplink", true,
                "statsInboundDownlink", true,
                "statsOutboundUplink", true,
                "statsOutboundDownlink", true)));
    }

    private Map<String, Object> tunInbound() {
        Map<String, Object> settings = map();
        settings.put("name", "PerRoute");
        settings.put("desc", "PerRoute");
        settings.put("mtu", 1500);
        settings.put("gateway", List.of("10.66.0.1/30", "fd66::1/126"));
        settings.put("dns", List.of("1.1.1.1", "8.8.8.8"));
        settings.put("autoSystemRoutingTable", List.of("0.0.0.0/0", "::/0"));
        settings.put("autoOutboundsInterface", "auto");

        Map<String, Object> sniffing = map();
        sniffing.put("enabled", true);
        sniffing.put("destOverride", List.of("http", "tls", "quic"));
        sniffing.put("routeOnly", true);

        Map<String, Object> inbound = map();
        inbound.put("tag", "tun-in");
        inbound.put("protocol", "tun");
        inbound.put("settings", settings);
        inbound.put("sniffing", sniffing);
        return inbound;
    }

    private Map<String, Object> proxyOutbound(ProxyNode node, String tag) {
        Map<String, Object> outbound = map();
        outbound.put("tag", tag);
        outbound.put("protocol", node.protocol().name().toLowerCase(Locale.ROOT));
        outbound.put("settings", protocolSettings(node));
        Map<String, Object> stream = streamSettings(node);
        if (!stream.isEmpty()) outbound.put("streamSettings", stream);
        return outbound;
    }

    private Map<String, Object> protocolSettings(ProxyNode node) {
        Map<String, Object> settings = map();
        settings.put("address", node.address());
        settings.put("port", node.port());
        switch (node.protocol()) {
            case VLESS -> {
                settings.put("id", node.credential());
                settings.put("encryption", value(node, "encryption", "none"));
                putNonBlank(settings, "flow", node.option("flow"));
            }
            case VMESS -> {
                settings.put("id", node.credential());
                settings.put("security", value(node, "security", "auto"));
            }
            case TROJAN -> settings.put("password", node.credential());
            case SHADOWSOCKS -> {
                settings.put("method", value(node, "method", "chacha20-poly1305"));
                settings.put("password", node.credential());
            }
            case XRAY_PROFILE -> throw new IllegalArgumentException("Embedded profiles use the profile generator");
        }
        return settings;
    }

    @SuppressWarnings("unchecked")
    private String generateXrayProfile(ProxyNode node, List<String> applicationPaths, RoutingMode mode) {
        String raw = node.option("rawProfileJson");
        if (raw.isBlank()) throw new IllegalArgumentException("Embedded Xray profile is empty");
        Map<String, Object> root = Json.object(raw);
        root.put("log", Map.of("loglevel", "info"));
        Object rawOutbounds = root.get("outbounds");
        if (!(rawOutbounds instanceof List<?> sourceOutbounds) || sourceOutbounds.isEmpty()) {
            throw new IllegalArgumentException("Embedded Xray profile has no outbounds");
        }
        List<Object> outbounds = new ArrayList<>(sourceOutbounds);
        String directTag = uniqueTag(outbounds, "perroute-direct");
        outbounds.add(Map.of("tag", directTag, "protocol", "freedom", "settings", Map.of()));
        root.put("outbounds", outbounds);
        root.put("inbounds", List.of(tunInbound()));

        Map<String, Object> routing;
        Object rawRouting = root.get("routing");
        if (rawRouting instanceof Map<?, ?> map) routing = (Map<String, Object>)map;
        else { routing = map(); root.put("routing", routing); }
        List<Object> originalRules = routing.get("rules") instanceof List<?> list ? new ArrayList<>(list) : List.of();
        Target profileTarget = findProfileTarget(outbounds, originalRules);
        List<String> processes = processPaths(applicationPaths);
        List<Object> rules = new ArrayList<>();
        rules.add(Map.of("type", "field", "ip", List.of("geoip:private"), "outboundTag", directTag,
                "ruleTag", "perroute-private"));

        if (mode == RoutingMode.EXCLUDE_SELECTED) {
            rules.add(processRule(processes, new Target("outboundTag", directTag), "perroute-selected-direct"));
        }
        for (Object value : originalRules) {
            if (!(value instanceof Map<?, ?> rawRule)) continue;
            Map<String, Object> rule = new LinkedHashMap<>();
            rawRule.forEach((key, item) -> rule.put(String.valueOf(key), item));
            if (rule.containsKey("inboundTag")) continue;
            if (mode == RoutingMode.INCLUDE_SELECTED) rule.put("process", processes);
            rules.add(rule);
        }
        if (mode == RoutingMode.INCLUDE_SELECTED) {
            rules.add(processRule(processes, profileTarget, "perroute-selected-profile"));
            rules.add(Map.of("type", "field", "network", "tcp,udp", "outboundTag", directTag,
                    "ruleTag", "perroute-fallback-direct"));
        } else {
            rules.add(targetRule(profileTarget, "perroute-fallback-profile"));
        }
        routing.put("domainStrategy", routing.getOrDefault("domainStrategy", "IPIfNonMatch"));
        routing.put("rules", rules);
        return Json.stringify(root) + System.lineSeparator();
    }

    @SuppressWarnings("unchecked")
    private String generateApplicationProxyProfile(ProxyNode node, List<String> applicationPaths,
                                                   List<app.perroute.model.RouteRule> routeRules, RouteLayout layout) {
        String raw = node.option("rawProfileJson");
        if (raw.isBlank()) throw new IllegalArgumentException("Embedded Xray profile is empty");
        Map<String, Object> root = Json.object(raw);
        root.put("log", Map.of("loglevel", "info"));

        Object rawOutbounds = root.get("outbounds");
        if (!(rawOutbounds instanceof List<?> sourceOutbounds) || sourceOutbounds.isEmpty()) {
            throw new IllegalArgumentException("Embedded Xray profile has no outbounds");
        }
        List<Object> outbounds = new ArrayList<>(sourceOutbounds);
        String directTag = uniqueTag(outbounds, "perroute-direct");
        String blockTag = uniqueTag(outbounds, "perroute-block");
        outbounds.add(Map.of("tag", directTag, "protocol", "freedom", "settings", Map.of()));
        outbounds.add(Map.of("tag", blockTag, "protocol", "blackhole", "settings", Map.of()));
        Map<String, Object> routing;
        Object rawRouting = root.get("routing");
        if (rawRouting instanceof Map<?, ?> existingRouting) routing = new LinkedHashMap<>((Map<String, Object>)existingRouting);
        else routing = map();
        List<Object> originalRules = routing.get("rules") instanceof List<?> list ? new ArrayList<>(list) : List.of();
        Target profileTarget = findProfileTarget(outbounds, originalRules);
        List<CustomRoute> customs = customRoutes(layout, outbounds, profileSelectors(root));
        root.put("outbounds", outbounds);
        List<Object> inbounds = applicationProxyInbounds(applicationPaths, layout.customs().size());
        root.put("inbounds", inbounds);
        List<RouteGroup> groups = layout.groups(applicationPaths, profileTarget,
                customs.stream().map(CustomRoute::target).toList());
        List<Object> rules = new ArrayList<>(localRules(directTag, blockTag));
        for (Object value : originalRules) {
            if (!(value instanceof Map<?, ?> rawRule)) continue;
            Map<String, Object> rule = new LinkedHashMap<>();
            rawRule.forEach((key, item) -> rule.put(String.valueOf(key), item));
            if (rule.containsKey("inboundTag")) continue;
            if (!layout.customs().isEmpty()) rule.put("inboundTag", groups.get(0).inboundTags());
            rules.add(rule);
        }
        rules.addAll(customProfileRules(customs, groups));
        rules.addAll(customRules(applicationPaths, routeRules, directTag, groups, blockTag));
        rules.addAll(applicationRules(groups));
        enableStats(root, inbounds, rules, runtimeControl);
        routing.put("domainStrategy", routing.getOrDefault("domainStrategy", "IPIfNonMatch"));
        routing.put("rules", rules);
        mergeCustomSections(root, routing, customs);
        root.put("routing", routing);
        return Json.stringify(root) + System.lineSeparator();
    }

    private static List<Object> customRules(List<String> applications,
                                            List<app.perroute.model.RouteRule> source,
                                            String directTag, List<RouteGroup> groups, String blockTag) {
        if (source == null || source.isEmpty()) return List.of();
        List<Object> result = new ArrayList<>();
        for (app.perroute.model.RouteRule rule : source) {
            if (rule == null || !rule.enabled() || rule.value().isBlank()) continue;
            List<String> inboundTags = new ArrayList<>();
            if (rule.applicationPath().isBlank()) inboundTags.addAll(proxyInboundTags(applications));
            else {
                for (int i = 0; i < applications.size(); i++) {
                    if (ApplicationIdentity.same(applications.get(i), rule.applicationPath())) inboundTags.add(perAppTag(i));
                }
            }
            if (inboundTags.isEmpty()) continue;
            String ruleTag = "perroute-custom-" + rule.id();
            if (rule.action() != app.perroute.model.RouteRule.Action.PROXY) {
                String target = rule.action() == app.perroute.model.RouteRule.Action.DIRECT ? directTag : blockTag;
                result.add(customRule(rule, inboundTags, new Target("outboundTag", target), ruleTag));
                continue;
            }
            if (groups.size() == 1) {
                result.add(customRule(rule, inboundTags, groups.get(0).target(), ruleTag));
                continue;
            }
            for (RouteGroup group : groups) {
                List<String> matching = inboundTags.stream().filter(group.inboundTags()::contains).toList();
                if (!matching.isEmpty()) result.add(customRule(rule, matching, group.target(), ruleTag + group.ruleSuffix()));
            }
        }
        return result;
    }

    private static Map<String, Object> customRule(app.perroute.model.RouteRule rule, List<String> inboundTags,
                                                  Target target, String ruleTag) {
        Map<String, Object> item = map();
        item.put("type", "field");
        item.put("inboundTag", inboundTags);
        List<String> values = Arrays.stream(rule.value().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        switch (rule.matchType()) {
            case DOMAIN -> item.put("domain", values.stream().map(value ->
                    value.contains(":") ? value : "domain:" + value).toList());
            case IP -> item.put("ip", values);
            case PORT -> item.put("port", String.join(",", values));
        }
        item.put(target.key, target.value);
        item.put("ruleTag", ruleTag);
        return item;
    }

    public static String customRuleId(String ruleTag) {
        if (ruleTag == null || !ruleTag.startsWith("perroute-custom-")) return null;
        return ruleTag.substring("perroute-custom-".length()).replaceFirst("#route\\d+$", "");
    }

    @SuppressWarnings("unchecked")
    private Target findProfileTarget(List<Object> outbounds, List<Object> rules) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (!(rules.get(i) instanceof Map<?, ?> rule)) continue;
            String balancer = objectString(rule.get("balancerTag"));
            if (!balancer.isBlank()) return new Target("balancerTag", balancer);
            String outbound = objectString(rule.get("outboundTag"));
            if (rule.containsKey("inboundTag") && !outbound.isBlank() &&
                    !outbound.equalsIgnoreCase("direct") && !outbound.equalsIgnoreCase("block")) {
                return new Target("outboundTag", outbound);
            }
        }
        for (Object value : outbounds) {
            if (!(value instanceof Map<?, ?> rawOutbound)) continue;
            String protocol = objectString(rawOutbound.get("protocol"));
            if (!List.of("vless", "vmess", "trojan", "shadowsocks").contains(protocol.toLowerCase(Locale.ROOT))) continue;
            Map<String, Object> outbound = (Map<String, Object>)rawOutbound;
            String tag = objectString(outbound.get("tag"));
            if (tag.isBlank()) {
                tag = uniqueTag(outbounds, "perroute-profile-primary");
                outbound.put("tag", tag);
            }
            return new Target("outboundTag", tag);
        }
        throw new IllegalArgumentException("Embedded Xray profile has no supported proxy target");
    }

    private static Map<String, Object> processRule(List<String> processes, Target target, String ruleTag) {
        Map<String, Object> rule = map();
        rule.put("type", "field");
        rule.put("process", processes);
        rule.put(target.key, target.value);
        rule.put("ruleTag", ruleTag);
        return rule;
    }

    private static Map<String, Object> targetRule(Target target, String ruleTag) {
        Map<String, Object> rule = map();
        rule.put("type", "field");
        rule.put("network", "tcp,udp");
        rule.put(target.key, target.value);
        rule.put("ruleTag", ruleTag);
        return rule;
    }

    private static String uniqueTag(List<Object> outbounds, String base) {
        List<String> tags = outbounds.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(map -> objectString(map.get("tag"))).toList();
        String candidate = base;
        int suffix = 2;
        while (tags.contains(candidate)) candidate = base + "-" + suffix++;
        return candidate;
    }

    private static List<String> processPaths(List<String> applicationPaths) {
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        for (String applicationPath : applicationPaths) {
            Path path = Path.of(applicationPath).toAbsolutePath().normalize();
            patterns.add(path.toString().replace('\\', '/'));
            Path fileName = path.getFileName();
            if (fileName != null) {
                String processName = fileName.toString();
                if (processName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    processName = processName.substring(0, processName.length() - 4);
                }
                patterns.add(processName);
                patterns.add(processName.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(patterns);
    }

    private static String objectString(Object value) { return value == null ? "" : String.valueOf(value); }
    private record Target(String key, String value) {}

    private Map<String, Object> streamSettings(ProxyNode node) {
        Map<String, Object> stream = map();
        String method = normalizeTransport(value(node, "type", "raw"));
        String security = normalizeSecurity(node);
        stream.put("method", method);
        stream.put("security", security);

        switch (method) {
            case "websocket" -> {
                Map<String, Object> ws = map();
                putNonBlank(ws, "path", node.option("path"));
                putNonBlank(ws, "host", node.option("host"));
                stream.put("wsSettings", ws);
            }
            case "grpc" -> {
                Map<String, Object> grpc = map();
                putNonBlank(grpc, "serviceName", first(node.option("serviceName"), node.option("path")));
                putNonBlank(grpc, "authority", node.option("authority"));
                if ("multi".equalsIgnoreCase(node.option("mode"))) grpc.put("multiMode", true);
                stream.put("grpcSettings", grpc);
            }
            case "xhttp" -> {
                Map<String, Object> xhttp = map();
                putNonBlank(xhttp, "path", node.option("path"));
                putNonBlank(xhttp, "host", node.option("host"));
                putNonBlank(xhttp, "mode", node.option("mode"));
                stream.put("xhttpSettings", xhttp);
            }
            case "httpupgrade" -> {
                Map<String, Object> upgrade = map();
                putNonBlank(upgrade, "path", node.option("path"));
                putNonBlank(upgrade, "host", node.option("host"));
                stream.put("httpupgradeSettings", upgrade);
            }
            case "mkcp" -> stream.put("kcpSettings", Map.of());
            default -> {
                String headerType = node.option("headerType");
                if (!headerType.isBlank() && !headerType.equalsIgnoreCase("none")) {
                    stream.put("rawSettings", Map.of("header", Map.of("type", headerType)));
                }
            }
        }

        String sni = first(node.option("sni"), node.option("serverName"), node.option("host"));
        if (security.equals("tls")) {
            Map<String, Object> tls = map();
            putNonBlank(tls, "serverName", sni);
            putNonBlank(tls, "fingerprint", node.option("fingerprint"));
            if (truthy(node.option("allowInsecure"))) tls.put("allowInsecure", true);
            if (!node.option("alpn").isBlank()) tls.put("alpn", splitComma(node.option("alpn")));
            stream.put("tlsSettings", tls);
        } else if (security.equals("reality")) {
            Map<String, Object> reality = map();
            putNonBlank(reality, "serverName", sni);
            putNonBlank(reality, "fingerprint", value(node, "fingerprint", "chrome"));
            putNonBlank(reality, "password", first(node.option("password"), node.option("pbk"), node.option("publicKey")));
            putNonBlank(reality, "shortId", first(node.option("sid"), node.option("shortId")));
            putNonBlank(reality, "spiderX", node.option("spx"));
            stream.put("realitySettings", reality);
        }
        return stream;
    }

    private Map<String, Object> routing(List<String> applicationPaths, RoutingMode mode) {
        List<String> processes = processPaths(applicationPaths);

        List<Object> rules = new ArrayList<>();
        rules.add(Map.of("type", "field", "ip", List.of("geoip:private"), "outboundTag", "direct"));
        String selectedTarget = mode == RoutingMode.INCLUDE_SELECTED ? "proxy" : "direct";
        String fallbackTarget = mode == RoutingMode.INCLUDE_SELECTED ? "direct" : "proxy";
        rules.add(Map.of("type", "field", "process", processes, "outboundTag", selectedTarget,
                "ruleTag", "selected-applications"));
        rules.add(Map.of("type", "field", "network", "tcp,udp", "outboundTag", fallbackTarget,
                "ruleTag", "fallback"));

        Map<String, Object> routing = map();
        routing.put("domainStrategy", "IPIfNonMatch");
        routing.put("rules", rules);
        return routing;
    }

    private Map<String, Object> directOutbound() {
        return Map.of("tag", "direct", "protocol", "freedom", "settings", Map.of());
    }

    private Map<String, Object> blockOutbound() {
        return Map.of("tag", "block", "protocol", "blackhole", "settings", Map.of());
    }

    private static String normalizeTransport(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "tcp", "raw", "none" -> "raw";
            case "ws", "websocket" -> "websocket";
            case "kcp", "mkcp" -> "mkcp";
            case "http", "h2", "xhttp", "splithttp" -> "xhttp";
            case "httpupgrade" -> "httpupgrade";
            case "grpc" -> "grpc";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    private static String normalizeSecurity(ProxyNode node) {
        String value = first(node.option("securityLayer"), node.option("security"));
        if (value.isBlank() && node.protocol() == Protocol.TROJAN) return "tls";
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "tls" -> "tls";
            case "reality" -> "reality";
            default -> "none";
        };
    }

    private static List<String> splitComma(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String value(ProxyNode node, String key, String fallback) {
        String value = node.option(key);
        return value.isBlank() ? fallback : value;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static void putNonBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
}
