# Refactor status

Where the incremental clean-up of PerRoute stands, why it was started, and how to continue it on another machine.

## Goal

Make PerRoute dependable on a clean Windows system without rewriting it. Three problems drove this:

1. Features were only proven against fakes, so real-machine failures (poisoned DNS breaking per-app, Store apps without
   icons) surfaced only after release.
2. Failures were swallowed (`runCatching`, `catch (...) { return List.of(); }`), so the cause was invisible to users and to us.
3. `PerRouteController.kt` held about 4000 lines of unrelated responsibilities, which made safe changes hard.

Decision: **no big-bang rewrite.** The code carries years of hard-won behaviour (DNS filtering, ProxiFyre quirks,
proxy recovery, migrations). Instead: build a safety net first, then peel responsibilities out of the controller one
at a time behind narrow interfaces, each with tests.

## Done

| Step | What | Where |
|---|---|---|
| Visible failures | `IssueLog` records handled problems in memory and in `issues.log`; discovery and icons use it; the Doctor warns when the app list may be incomplete | `service/IssueLog.java`, `service/InstalledApplicationDiscovery.java`, `ConnectionDoctor.kt` |
| Real-machine check | `gradlew runWindowsSmoke` checks app discovery, icon coverage (fails below 70%), the packet-filter driver and DNS (warns on private answers for public names) | `src/test/java/app/perroute/WindowsSmoke.java` |
| Store app icons | Packaged-app stubs have no shell icon and the JDK throws; the logo from `AppxManifest.xml` is used instead. Coverage went from about 60% to 98% on the dev machine | `service/StoreAppIcon.java`, `service/WindowsAppIcons.java` |
| Application catalog | Installed/running lists, caching and filtering moved out of the controller | `controller/ApplicationCatalog.kt` |
| Doctor | Connection doctor and Routing safety check moved out; they read the app through the narrow `DoctorContext` interface and never mutate it | `controller/ConnectionDoctor.kt` |
| Subscription tracking | Update history, health and attention counts moved out | `controller/SubscriptionTracker.kt` |
| Dead UI | The old Swing UI (`app.perroute.ui`, `Main`) was unused (the entry point is `ComposeMainKt`) and was removed, about 1900 lines | - |

`PerRouteController.kt`: 4012 lines before, 3726 now. All tests pass (1490 assertions, plus 87 platform-boundary).

Earlier in the same effort (already on `main`, PR 45): per-app routing on Windows now routes web traffic by the site name
read from the connection, so a fake address from a filtering DNS resolver (for example `10.10.34.x`) no longer leaves
traffic direct. ProxiFyre's LAN bypass is off so those addresses reach the core. This was confirmed for YouTube in
Brave on one machine; the other two systems that failed have not been re-tested.

## Not done

- **Routing / per-app service.** Connect, disconnect, route health, reconfiguration and `prepare(...)` are still in the
  controller. They share mutable runtime state (`activeRoutes`, `phase`, `activeMode`, the gateway), so extract them
  behind an interface like `DoctorContext` rather than passing the controller around.
- **Servers service.** Latency and speed tests (`pingServers`, `speedTestServer`, `fastestNode`), node merge and
  de-duplication, and import previews are still in the controller.
- **Remaining swallowed errors.** About 120 `runCatching` calls remain in the controller and about 50 catch blocks in
  `service/`. Only discovery and icons report to `IssueLog`. Next candidates: `ProxiFyreManager`, `XrayProcessManager`,
  `WindowsSystemProxyManager`.
- **CI.** `runWindowsSmoke` is not part of any workflow yet. It needs a Windows runner and only reports meaningful
  results on a real desktop session.
- **Release 3.7.0.** The tag `v3.7.0` was pushed to build the installer. The macOS job needs the
  `MACOS_SIGNING_STATE` secret; without it the release is not published. Check the Actions run.

## Continue on another machine

```bash
git fetch && git checkout fix/app-icons-and-issue-log
export JAVA_HOME="C:/Program Files/Java/jdk-21"      # JDK 17+ is required, the default Java 8 fails
./gradlew.bat runAllTests runPlatformBoundaryTests    # portable tests
./gradlew.bat runWindowsSmoke                         # Windows only, reads the real machine
```

Rules that kept this safe:

- One responsibility per PR, with the old behaviour kept and a test added before the code moves.
- A moved class talks to the app through a small interface; it never receives the controller itself.
- A fallback must record why it fell back (`IssueLog.record(source, message, cause)`).
- A local installer build needs Rust for the browser helper. Use the CI artifact (`release-windows-x64`) to test
  installers instead.

Known gaps in the DNS fix: traffic with no site name (neither HTTP nor TLS) aimed at a poisoned address is still not
recovered, IPv6 is untested, and turning the LAN bypass off may affect LAN discovery from routed apps.
