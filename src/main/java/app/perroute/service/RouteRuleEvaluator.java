package app.perroute.service;

import app.perroute.model.RouteRule;
import app.perroute.model.ApplicationIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RouteRuleEvaluator {
    private RouteRuleEvaluator() {}

    public enum Match { YES, NO, UNKNOWN }

    public record Verdict(RouteRule.Action action, RouteRule rule, boolean certain, String reason,
                          List<RouteRule> uncertain) {}

    public static Verdict evaluate(List<RouteRule> rules, List<String> applications, String application,
                                   String destination, int port) {
        String target = destination == null ? "" : destination.trim().toLowerCase(Locale.ROOT);
        String app = application == null ? "" : ApplicationIdentity.isMac(application) ? application : application.trim();
        byte[] address = literalAddress(target);
        if (address != null && isPrivate(address)) {
            return new Verdict(RouteRule.Action.DIRECT, null, true, "Private and LAN addresses always stay direct.", List.of());
        }
        List<RouteRule> uncertain = new ArrayList<>();
        for (RouteRule rule : rules) {
            if (rule == null || !rule.enabled() || !appliesTo(rule, applications, app)) continue;
            Match match = matches(rule, target, address, port);
            if (match == Match.YES) {
                return new Verdict(rule.action(), rule, uncertain.isEmpty(),
                        "Matched \"" + rule.name() + "\" (" + rule.matchType() + " " + rule.value() + ").", List.copyOf(uncertain));
            }
            if (match == Match.UNKNOWN) uncertain.add(rule);
        }
        return new Verdict(RouteRule.Action.PROXY, null, uncertain.isEmpty(),
                "No rule matched; routed traffic uses the proxy by default.", List.copyOf(uncertain));
    }

    private static boolean appliesTo(RouteRule rule, List<String> applications, String application) {
        if (rule.applicationPath().isBlank()) return true;
        boolean loaded = applications != null
                && applications.stream().anyMatch(path -> ApplicationIdentity.same(path, rule.applicationPath()));
        return loaded && ApplicationIdentity.same(rule.applicationPath(), application);
    }

    static Match matches(RouteRule rule, String target, byte[] address, int port) {
        List<String> values = Arrays.stream(rule.value().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        boolean unknown = false;
        for (String value : values) {
            Match match = switch (rule.matchType()) {
                case DOMAIN -> address != null ? Match.NO : matchDomain(value.toLowerCase(Locale.ROOT), target);
                case IP -> matchIp(value.toLowerCase(Locale.ROOT), address);
                case PORT -> matchPort(value, port);
            };
            if (match == Match.YES) return Match.YES;
            if (match == Match.UNKNOWN) unknown = true;
        }
        return unknown ? Match.UNKNOWN : Match.NO;
    }

    private static Match matchDomain(String value, String host) {
        if (host.isBlank()) return Match.UNKNOWN;
        int separator = value.indexOf(':');
        String kind = separator < 0 ? "domain" : value.substring(0, separator);
        String pattern = separator < 0 ? value : value.substring(separator + 1);
        return switch (kind) {
            case "domain" -> host.equals(pattern) || host.endsWith("." + pattern) ? Match.YES : Match.NO;
            case "full" -> host.equals(pattern) ? Match.YES : Match.NO;
            case "keyword" -> host.contains(pattern) ? Match.YES : Match.NO;
            case "regexp" -> {
                try { yield Pattern.compile(pattern).matcher(host).find() ? Match.YES : Match.NO; }
                catch (PatternSyntaxException invalid) { yield Match.UNKNOWN; }
            }
            default -> Match.UNKNOWN;
        };
    }

    private static Match matchIp(String value, byte[] address) {
        if (address == null) return Match.UNKNOWN;
        if (value.equals("geoip:private")) return isPrivate(address) ? Match.YES : Match.NO;
        if (value.contains(":") && !value.contains("/") && literalAddress(value) == null) return Match.UNKNOWN;
        int slash = value.indexOf('/');
        byte[] network = literalAddress(slash < 0 ? value : value.substring(0, slash));
        if (network == null) return Match.UNKNOWN;
        if (network.length != address.length) return Match.NO;
        int bits = network.length * 8;
        if (slash >= 0) {
            try { bits = Integer.parseInt(value.substring(slash + 1).trim()); }
            catch (NumberFormatException invalid) { return Match.UNKNOWN; }
            if (bits < 0 || bits > network.length * 8) return Match.UNKNOWN;
        }
        for (int index = 0; index < bits; index++) {
            int mask = 0x80 >>> (index % 8);
            if ((network[index / 8] & mask) != (address[index / 8] & mask)) return Match.NO;
        }
        return Match.YES;
    }

    private static Match matchPort(String value, int port) {
        if (port <= 0) return Match.UNKNOWN;
        try {
            int dash = value.indexOf('-');
            if (dash < 0) return Integer.parseInt(value) == port ? Match.YES : Match.NO;
            int from = Integer.parseInt(value.substring(0, dash).trim());
            int to = Integer.parseInt(value.substring(dash + 1).trim());
            return port >= from && port <= to ? Match.YES : Match.NO;
        } catch (NumberFormatException invalid) {
            return Match.UNKNOWN;
        }
    }

    static byte[] literalAddress(String text) {
        if (text == null || text.isBlank()) return null;
        boolean ipv6 = text.indexOf(':') >= 0 && text.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.');
        boolean ipv4 = text.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!ipv4 && !ipv6) return null;
        try { return InetAddress.getByName(text).getAddress(); }
        catch (UnknownHostException | RuntimeException invalid) { return null; }
    }

    private static boolean isPrivate(byte[] raw) {
        try {
            InetAddress address = InetAddress.getByAddress(raw);
            if (address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()) return true;
            if (raw.length == 4) return (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64;
            return (raw[0] & 0xfe) == 0xfc;
        } catch (UnknownHostException invalid) {
            return false;
        }
    }
}
