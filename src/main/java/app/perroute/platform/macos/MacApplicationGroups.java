package app.perroute.platform.macos;

import java.io.IOException;
import java.util.*;

/** Read-only picker grouping. Catalog observations never grant routing or migration authority. */
public final class MacApplicationGroups {
    public record Group(MacApplicationCatalog.Application application,
                        List<MacApplicationCatalog.Application> members) {
        public Group { members = List.copyOf(members); }
    }

    private MacApplicationGroups() { }

    public static List<Group> installed(MacApplicationCatalog.Snapshot snapshot) throws IOException {
        var inventory = inventory(snapshot);
        var groups = new TreeMap<String, List<MacApplicationCatalog.Application>>();
        for (var member : inventory.values()) {
            var outer = inventory.values().stream()
                    .filter(candidate -> contains(candidate, member))
                    .min(Comparator.comparingInt(candidate -> candidate.bundlePath().getNameCount()))
                    .orElse(member);
            var owner = verifiedMember(outer, member) ? outer : member;
            groups.computeIfAbsent(owner.bundlePath().toString(), ignored -> new ArrayList<>()).add(member);
        }
        var result = new ArrayList<Group>();
        groups.forEach((bundle, members) -> result.add(new Group(inventory.get(bundle), members)));
        return List.copyOf(result);
    }

    public static List<Group> running(MacApplicationCatalog.Snapshot installed,
                                      MacApplicationCatalog.Snapshot running) throws IOException {
        var known = inventory(installed);
        var observed = inventory(running);
        for (var app : observed.values()) {
            var previous = known.get(app.bundlePath().toString());
            if (previous != null && !previous.equals(app))
                throw new IOException("Application identity changed. Refresh the installed and running lists.");
        }
        var result = new ArrayList<Group>();
        // Resolve a helper-only running inventory through the complete installed inventory,
        // rather than inventing a new logical app from whichever process appeared first.
        for (var group : installed(installed)) {
            if (group.members().stream().anyMatch(app -> observed.containsKey(app.bundlePath().toString())))
                result.add(group);
        }
        for (var app : observed.values()) {
            if (!known.containsKey(app.bundlePath().toString())) result.add(new Group(app, List.of(app)));
        }
        result.sort(Comparator.comparing(group -> group.application().bundlePath().toString()));
        return List.copyOf(result);
    }

    private static boolean contains(MacApplicationCatalog.Application outer, MacApplicationCatalog.Application member) {
        return outer.bundlePath().equals(member.bundlePath()) ||
                member.bundlePath().startsWith(outer.bundlePath().resolve("Contents"));
    }

    private static boolean verifiedMember(MacApplicationCatalog.Application owner, MacApplicationCatalog.Application member) {
        if (owner.equals(member)) return true;
        // A shared team or display name alone cannot join apps. Missing team identity,
        // including system services outside the bundle, stays separate for attribution.
        return contains(owner, member) && owner.signatureValid() && member.signatureValid() &&
                !owner.signingIdentifier().isBlank() && !member.signingIdentifier().isBlank() &&
                !owner.teamIdentifier().isBlank() && owner.teamIdentifier().equals(member.teamIdentifier());
    }

    private static Map<String, MacApplicationCatalog.Application> inventory(MacApplicationCatalog.Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot);
        if (!snapshot.warnings().isEmpty())
            throw new IOException("Application discovery is incomplete. Refresh before grouping applications.");
        var result = new TreeMap<String, MacApplicationCatalog.Application>();
        for (var app : snapshot.applications()) {
            if (result.putIfAbsent(app.bundlePath().toString(), app) != null)
                throw new IOException("Duplicate application bundle in inventory.");
        }
        return result;
    }
}
