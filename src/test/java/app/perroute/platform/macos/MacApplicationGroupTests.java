package app.perroute.platform.macos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class MacApplicationGroupTests {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void refuses(Work work) throws Exception {
        try { work.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected grouping refusal");
    }
    private static MacApplicationCatalog.Application app(String bundle, String name, String team, boolean valid) {
        return new MacApplicationCatalog.Application(name, Path.of(bundle), Path.of(bundle, "Contents/MacOS/App"),
                "example.same-id", "example.signing", team, valid, bundle.contains("/Contents/"));
    }
    private static MacApplicationCatalog.Snapshot snapshot(MacApplicationCatalog.Application... apps) {
        return new MacApplicationCatalog.Snapshot(List.of(apps), List.of());
    }
    public static void main(String[] args) throws Exception {
        var chrome = app("/Applications/Browser.app", "Browser فارسی", "TEAM", true);
        var helper = app("/Applications/Browser.app/Contents/Frameworks/Network.app", "Network", "TEAM", true);
        var oldHelper = app("/Applications/Browser.app/Contents/Frameworks/Old/Network.app", "Network", "TEAM", true);
        var other = app("/Applications/Browser.app-other.app", chrome.name(), "TEAM", true);
        var installed = snapshot(helper, other, chrome, oldHelper);
        var groups = MacApplicationGroups.installed(installed);
        check(groups.size() == 2, "Contained verified helpers collapse; lookalike apps remain separate");
        var browser = groups.stream().filter(group -> group.application().equals(chrome)).findFirst().orElseThrow();
        check(browser.members().size() == 3, "All observed helper versions belong to one logical app");
        check(browser.application().name().equals("Browser فارسی"), "Main app name is preserved");
        check(browser.members().containsAll(List.of(chrome, helper, oldHelper)), "Exact member identities remain available");
        check(MacApplicationGroups.descriptor(browser).application().path().equals(
                app.perroute.model.ApplicationIdentity.macAppFamily(chrome.executablePath().toString())), "Picker emits one logical family choice");
        check(MacApplicationGroups.descriptor(browser).observationFingerprint().length()==64, "Preview binds observed signing/role metadata");
        var changedTeam = MacApplicationGroups.installed(snapshot(app(chrome.bundlePath().toString(), chrome.name(), "NEWTEAM", true),
                app(helper.bundlePath().toString(), helper.name(), "NEWTEAM", true),
                app(oldHelper.bundlePath().toString(), oldHelper.name(), "NEWTEAM", true))).getFirst();
        check(!MacApplicationGroups.descriptor(changedTeam).observationFingerprint().equals(MacApplicationGroups.descriptor(browser).observationFingerprint()),
                "Same paths with changed signing team invalidate the observation fingerprint");
        var alone = MacApplicationGroups.installed(snapshot(chrome)).getFirst();
        check(MacApplicationGroups.descriptor(alone).application().path().equals(MacApplicationGroups.descriptor(browser).application().path()),
                "Logical app identity is stable when an update removes helpers");
        check(MacApplicationGroups.running(installed, snapshot(helper)).equals(List.of(browser)), "Helper-only running list resolves to installed main");
        var renamed = new MacApplicationCatalog.Application("Different display label", helper.bundlePath(), helper.executablePath(),
                helper.bundleIdentifier(), helper.signingIdentifier(), helper.teamIdentifier(), helper.signatureValid(), true);
        check(MacApplicationGroups.running(installed, snapshot(renamed)).equals(List.of(browser)), "Different installed/running labels do not change identity");
        var bundledApp = new MacApplicationCatalog.Application("Independent bundled app", helper.bundlePath(), helper.executablePath(),
                helper.bundleIdentifier(), helper.signingIdentifier(), helper.teamIdentifier(), true, false);
        check(MacApplicationGroups.installed(snapshot(chrome, bundledApp)).size()==2, "Same-team foreground app is not classified as a helper");
        check(MacApplicationGroups.running(installed, snapshot(chrome, helper, oldHelper)).size() == 1, "Running processes do not duplicate the app");
        check(MacApplicationGroups.running(installed, snapshot()).isEmpty(), "Installed apps are not invented as running");
        check(MacApplicationGroups.installed(snapshot(oldHelper, chrome, other, helper)).equals(groups), "Grouping is independent of discovery order");
        for (var unverified : List.of(
                app(helper.bundlePath().toString(), "Network", "OTHER", true),
                app(helper.bundlePath().toString(), "Network", "", true),
                app(helper.bundlePath().toString(), "Network", "TEAM", false))) {
            check(MacApplicationGroups.installed(snapshot(chrome, unverified)).size() == 2, "Unverified helper is not silently merged");
        }
        var unsignedMain = app(chrome.bundlePath().toString(), chrome.name(), "TEAM", false);
        check(MacApplicationGroups.installed(snapshot(unsignedMain, helper)).size() == 2, "Unsigned main cannot absorb helpers");
        var ungrouped = MacApplicationGroups.installed(snapshot(unsignedMain, helper)).stream().filter(group -> group.application().equals(helper)).findFirst().orElseThrow();
        check(!app.perroute.model.ApplicationIdentity.isMacFamily(MacApplicationGroups.descriptor(ungrouped).application().path()),
                "Unverified contained helper remains an exact executable, not an invalid nested family");
        var system = app("/System/Library/Frameworks/WebKit.framework/Network.app", "Network", "TEAM", true);
        check(MacApplicationGroups.installed(snapshot(chrome, system)).size() == 2, "External shared service never joins by signing team");
        var lower = app("/Applications/browser.app", chrome.name(), "TEAM", true);
        check(MacApplicationGroups.installed(snapshot(chrome, lower)).size() == 2, "Case-sensitive app paths stay distinct");
        var unknown = app("/Volumes/External/Player.app", "Player", "TEAM", true);
        check(MacApplicationGroups.running(installed, snapshot(unknown)).getFirst().application().equals(unknown), "Uncatalogued running app remains standalone");
        refuses(() -> MacApplicationGroups.running(installed, snapshot(unsignedMain)));
        refuses(() -> MacApplicationGroups.installed(new MacApplicationCatalog.Snapshot(List.of(chrome), List.of("partial"))));
        refuses(() -> MacApplicationGroups.running(installed, new MacApplicationCatalog.Snapshot(List.of(helper), List.of("partial"))));
        refuses(() -> MacApplicationGroups.installed(snapshot(chrome, chrome)));
        try { groups.clear(); throw new AssertionError("Mutable groups"); } catch (UnsupportedOperationException expected) { checks++; }
        try { browser.members().clear(); throw new AssertionError("Mutable members"); } catch (UnsupportedOperationException expected) { checks++; }
        check(installed.applications().size() == 4, "Projection never changes the source inventory or stored selections");
        System.out.println("macOS application groups: " + checks + " checks passed; synthetic read-only inventories");
    }
}
