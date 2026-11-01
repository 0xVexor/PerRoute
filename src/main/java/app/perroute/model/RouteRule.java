package app.perroute.model;

import java.util.Objects;
import java.util.UUID;

public record RouteRule(
        String id,
        String name,
        String applicationPath,
        MatchType matchType,
        String value,
        Action action,
        boolean enabled
) {
    public enum MatchType { DOMAIN, IP, PORT }
    public enum Action { PROXY, DIRECT, BLOCK }

    public RouteRule {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = Objects.requireNonNullElse(name, "Rule").trim();
        applicationPath = Objects.requireNonNullElse(applicationPath, "");
        if (ApplicationIdentity.isMac(applicationPath)) ApplicationIdentity.path(applicationPath);
        else applicationPath = applicationPath.trim();
        matchType = Objects.requireNonNullElse(matchType, MatchType.DOMAIN);
        value = Objects.requireNonNullElse(value, "").trim();
        if (matchType == MatchType.DOMAIN) value = DomainEntries.normalizeList(value);
        action = Objects.requireNonNullElse(action, Action.PROXY);
        if (value.isBlank()) throw new IllegalArgumentException("Rule value is empty");
    }
}
