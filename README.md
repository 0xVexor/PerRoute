<div align="center">

<img src="src/main/resources/icons/perroute-icon-256.png" width="96" alt="PerRoute logo">

# PerRoute

**Route only the applications you choose through your proxy. Everything else stays on your regular connection.**

A modern Windows client for VLESS, VMess, Trojan and Shadowsocks, built on [Xray-core](https://github.com/XTLS/Xray-core).

![Platform](https://img.shields.io/badge/platform-Windows%2010%20%7C%2011%20x64-0078D6)
![Version](https://img.shields.io/badge/version-3.5.1-6B57D9)
![Built with](https://img.shields.io/badge/built%20with-Kotlin%20%2B%20Compose%20Desktop-7F52FF)
![Core](https://img.shields.io/badge/core-Xray-0B9B83)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

[Download](#download) · [Features](#features) · [Tour](#tour) · [Connection modes](#connection-modes) · [Build from source](#build-from-source)

<img src="docs/screenshots/dashboard-connected.png" width="820" alt="PerRoute connected in Smart per-app mode">

</div>

---

## Why PerRoute

Most proxy clients are all-or-nothing: either the whole system goes through the tunnel or nothing does. PerRoute is built around the opposite idea. You pick the applications that need the tunnel; the rest of the machine keeps its normal, direct connection. No browser extensions, no per-application proxy settings, and no launching applications from inside the client.

When you do want everything proxied, System proxy and Local proxy modes are one click away.

## Features

| | |
|---|---|
| **Per-application routing** | Route arbitrary Windows applications, including ones with no proxy settings of their own. Already-running applications are picked up without being restarted. |
| **A server per app (optional)** | Advanced routing lets each selected application follow the main server, use a server you pick, or let PerRoute pick one automatically. It is off by default. |
| **Network quality score** *(3.0)* | Every server gets an explainable 0–100 score from latency, jitter, success rate, stability and tunnel setup time, with a confidence level and freshness. Unmeasured servers are shown as Unknown, never as healthy. |
| **Auto server per app** *(3.0)* | An application in Auto mode gets its own route to the best measured server. It changes only when another server is clearly better and a cooldown has passed, fails over when its server stops working, and never falls back to the direct route. |
| **Independent route recovery** *(3.0)* | Every live route is checked through its own port and recovered on its own, with bounded retries. A failing route does not restart or interrupt the others. |
| **Per-app bandwidth limits** *(3.0)* | Download and upload limits per application, shared by all of its connections (TCP and UDP), enforced by PerRoute's traffic gateway. |
| **Connection inspector** *(3.0)* | Each selected app's live connections with destination, protocol, route, matched rule and traffic. What is configured, what the core was observed doing, and what a route check verified are always shown apart. |
| **Backup and restore** *(3.0)* | Portable backups, optionally encrypted with a password, with a preview, path remapping for other computers, Merge or Replace, a rollback snapshot and Undo. |
| **Network Time Machine** *(3.5)* | Snapshots of the network settings PerRoute touches, a comparison that says who changed what, and a restore that undoes only PerRoute's own leftovers. **PerRoute Recovery** does the same even when PerRoute does not open. |
| **Safe update and rollback** *(3.5)* | The installer keeps the version it replaces; a version that cannot start is noticed and you can go back without a download. |
| **Temporary servers** *(3.5)* | One app on another server for a while, from the app, the Connection screen, a server or the tray. It goes back by itself; nothing permanent changes. |
| **Compare with a live route** *(3.5)* | A candidate server and a live route open the same sites side by side, without touching the route. |
| **Routing recipes** *(3.5)* | Share rules and app routes as a file or a line of text, without servers, links, passwords or folders. Import with a full preview and Undo. |
| **Browser workspaces** *(3.5)* | Separate browser profiles, each on a route of its own, with DNS, QUIC and WebRTC kept inside it. |
| **Local network** *(3.5)* | See your networks and devices, keep local names local, and block local devices for routed apps on networks you do not trust. |
| **Connection Doctor and Safety Center** *(3.5)* | Why something does not connect, with repairs you confirm; and real tests that protection works as configured. |
| **Subscription intelligence** *(3.5)* | Usage, quota, expiry and update history of every subscription, with warnings for expired, exhausted, failing or suddenly changed ones. |
| **Gaming test** *(3.5)* | Steady latency, jitter, packet loss and UDP support per server, and a score built for games. |
| **Three connection modes** | Smart per-app, System proxy, and Local proxy (SOCKS5 + HTTP). |
| **Subscriptions** | Import by URL, pasted share links, Xray JSON, or QR code (image, clipboard, or camera). Updates keep your favorites, tags and selection. |
| **Latency and speed tests** | Endpoint ping, a real tunnel test, and a download speed test per server, with sorting and filtering. |
| **Live switching** | Change servers while connected without restarting your applications. Where possible only the main route is replaced, so other routes keep their connections. A failed switch rolls back automatically. |
| **Auto reconnect and failover** | Recovers after sleep or network changes and moves to a working server when the active one stops responding. |
| **Per-app kill switch** | Keeps selected applications offline whenever their tunnel is unavailable. Other applications are unaffected. |
| **Routing rules** | Per-application rules by domain, IP/CIDR or port with Proxy, Direct and Block actions. First match wins. |
| **One-click scenarios** | Work, Gaming and Streaming presets that set automation and add a small rule set on top of yours. |
| **Profiles** | Save applications, server, mode and rules together and switch between them. |
| **Connection diagnostics** | Check every route on its own: the public IP it exits from and when it last passed or failed. See your system DNS resolvers, which rules are loaded in the running core, and which rule a destination will hit. |
| **Traffic history** | Live speed graph plus daily, monthly and per-application totals. Stored locally; destinations are never recorded. |
| **Quick Access** | A compact tray panel to connect, switch the main server or jump to the fastest one. |
| **Safe recovery** | Windows proxy settings are restored after a crash, forced close, or sudden restart. |
| **Signed updates** | Update manifests are verified against a pinned Ed25519 key; downloads are checked by size and SHA-256. |
| **Light and dark themes** | |

## Download

Get the latest build from the [Releases](https://github.com/AliMirzad/PerRoute/releases/latest) page.

| Package | Use it when |
|---|---|
| `PerRoute-Setup-3.5.1.exe` | You want everything, including Smart per-app mode. The installer asks for Administrator approval once and installs PerRoute together with the per-app engine. |
| `PerRoute-Portable-3.5.1.zip` | You want a no-install build. Extract the folder and run `PerRoute.exe`. System proxy and Local proxy work out of the box; Smart per-app works only if the per-app engine is already installed. |

**Requirements:** Windows 10 or 11, 64-bit. The Java runtime and Xray core are bundled; nothing else needs to be installed.

Verify a download against the SHA-256 values published with each release:

```powershell
Get-FileHash .\PerRoute-Setup-3.5.1.exe -Algorithm SHA256
```

## Quick start

1. Open **Servers**, choose **Add servers**, and paste your subscription URL (or use **Paste links** or **QR code**). Review the list, then add it.
2. Select a server and choose **Use as main server**. Latency appears automatically; the best servers are speed-tested in the background.
3. Open **Apps**, tick the programs that should use the tunnel, and choose **Save changes**.
4. Open **Connection** and press **Connect**.

## Tour

The window has eight labelled sections: Connection, Apps, Servers, Rules, Profiles, Diagnostics, Traffic and Settings.

### Connection

Shows the connection state, the active mode, live download and upload speed, session duration, the main server's latency, and one row per route in use with the applications on it and its own health. A dot marks the selected applications that are sending traffic right now.

With route monitoring on (*Settings > Routing*), each route is checked through its own port once a minute. A route that stops answering shows **Recovering** with the attempt number and a countdown; it expands to say that only this route is affected, whether the kill switch is holding its apps, that there is no direct fallback, and a short recovery timeline. **Retry now** checks it again at once. Healthy routes keep running and are not restarted.

<p align="center">
  <img src="docs/screenshots/dashboard.png" width="49%" alt="Connection, ready to connect">
  <img src="docs/screenshots/dashboard-light.png" width="49%" alt="Connection in the light theme">
</p>

### Servers

Add and update subscriptions, filter by subscription, favorites, tag or country, search, and sort by latency or quality. Each server shows its ping, measured download speed and **network quality** (score, label and freshness), and carries a badge when it is the main server, a favorite, or tagged. Expand a server to see what its score is made of: latency, jitter, success rate, stability, tunnel setup time, when it was last tested, the confidence, and a sentence explaining the result. Star a server to make it a favorite. The tag button on each row lists your existing tags, so a server goes into one with a single click; **New tag** creates another, and **No tag** clears it. Click a speed chip to test only that server. Select a server and choose **Use as main server** to make it the main route; this also works while connected.

<p align="center"><img src="docs/screenshots/servers.png" width="820" alt="Server list with latency, speed and usage"></p>

- **Add servers** takes a subscription URL, pasted share links, or a QR code (image, clipboard or camera). Whatever you choose is only read first: you see the servers that were found and any entries that could not be parsed before anything is saved. The camera stays off until you start it.
- **Real tunnel test** starts an isolated Xray instance per server and verifies actual traffic through the tunnel.
- **Speed test** downloads a bounded test file through each server, one at a time. After every ping pass the 10 best servers without a result are measured automatically (up to 10 MB each). This can be turned off under *Settings > Automation*.
- Servers are de-duplicated by their effective configuration, not by name.

<p align="center"><img src="docs/screenshots/add-servers.png" width="640" alt="Reviewing servers before they are added"></p>

### Apps

Choose from installed applications, running applications, or browse for any executable. Multi-process applications appear once. Changes are staged: nothing is applied until you choose **Save changes**, and leaving the screen with unsaved edits asks whether to save or discard them.

Select an application to open its panel on the right: its route mode, the current decision and the reason for it, and its **bandwidth limit** (download and upload in megabits per second, or unlimited). Limits are saved and applied at once, even while connected; the panel says whether the limit is **Enforced**, **Throttling**, **Pending** (applies when you connect) or **Not enforced** and why.

<p align="center"><img src="docs/screenshots/applications.png" width="820" alt="Choosing applications to route"></p>

### Advanced routing: a server per app

Advanced routing is optional and **off by default**. While it is off, every selected application uses the main server and no per-app server controls are shown.

Turn it on in **Apps** or under *Settings > Connection* (both switch the same setting) and each selected application gets three modes. **Main** (the default) follows the main server. **Manual** uses a server you pick, shown as a **Custom** route. **Auto** lets PerRoute pick a server for that application from the quality scores. Applications you did not select stay direct.

How **Auto** behaves:

- Each Auto application is decided on its own and gets a route of its own, so its server can change without touching any other application.
- It only picks servers whose score is backed by measurements; an unmeasured server is never treated as healthy. Until any server has data, the app uses the main server and says so.
- It keeps a working server unless another one is better by the switch margin (10 points by default) and the cooldown since the last change (10 minutes by default) has passed. Both are set in *Settings > Routing*.
- When its route fails and does not recover, it moves to the best working server right away (at most once a minute). With no working alternative it keeps its server: it is never released to the direct route.
- You can restrict the choice to your favorites or to one tag. The current choice survives a restart.
- Manual choices are never changed automatically. Auto is off for every application until you choose it.

<p align="center">
  <img src="docs/screenshots/advanced-apps.png" width="49%" alt="Choosing a custom server for an application">
  <img src="docs/screenshots/advanced-connection.png" width="49%" alt="One row per server while connected">
</p>

- Turning Advanced routing off does not delete anything. Your custom choices are kept and apply again when you turn it back on.
- Custom servers apply in Smart per-app mode. System proxy and Local proxy keep them saved and ignore them.
- Routes and the Advanced switch can be changed while disconnected only.
- If an application's custom server is removed, or disappears from a subscription, the application is neither moved to the main server nor released to the direct route. PerRoute asks you to choose a replacement before it connects. While Advanced routing is off, such a leftover choice does not block anything.
- Every server has its own check. A passing main server says nothing about a custom one, and results older than five minutes are marked stale.
- All servers share one Xray process. When route monitoring or an Auto app is in use, a change to one route (switching the main server, an Auto failover, rebuilding a stuck route) replaces only that route's outbound in the running core: other routes keep their connections. Embedded Xray profiles, and a switch to a server that is also another app's custom server, still restart the core and briefly interrupt every routed application. Manual servers are never switched or failed over automatically.
- Any saved server can be a custom server, including embedded Xray JSON profiles. A custom profile keeps its own outbounds, balancers, observatory and rules, and its rules apply only to the applications on it. Sections that Xray has only once per process (DNS, policy) come from the main server.
- If Xray rejects one of the servers, PerRoute does not connect and names that server and the application using it.
- In the picker, favorites are listed first and marked with a star; with many servers a filter field appears.

### Routing rules

Rules apply to all routed applications or to a single one. They are evaluated top to bottom and the first match wins; use the arrows to reorder your rules, and the side panel to add or edit one. **Proxy** sends a connection through the application's own server: its custom server, or the main server when it has none.

<p align="center"><img src="docs/screenshots/rules.png" width="820" alt="Routing rules with the rule editor"></p>

| Match | Examples |
|---|---|
| Domain | `example.com` (the domain and its subdomains), `full:api.example.com`, `keyword:tracker`, `geosite:netflix` |
| IP | `203.0.113.0/24`, `geoip:ir` |
| Port | `80,443,1000-2000` |

### Profiles and presets

A **preset** is a ready-made bundle. It sets the automation switches and adds its own rules above yours without touching your applications, servers, app routes, connection mode, or existing rules. **Preview changes** lists exactly what it would change before you apply it, and **Remove preset rules** takes its rules out again.

| Preset | Kill switch | Fastest on connect | Rules it adds |
|---|---|---|---|
| Work | On | Off | Collaboration tools always proxied; peer-to-peer ports blocked |
| Gaming | Off | On | Game download CDNs stay direct |
| Streaming | On | Off | Streaming services always proxied |

A **profile** saves your own applications, main server, mode, Advanced routing with its app routes, and rules together, so you can switch whole setups at once with **Apply**. Apply a preset first and save a profile to keep it.

<p align="center"><img src="docs/screenshots/scenarios-profiles.png" width="820" alt="Presets and saved profiles"></p>

### Diagnostics

The **Inspector** tab lists the live connections of the selected applications in Smart per-app mode: application, destination (with the domain the core sniffed), protocol, route, status, live traffic and last activity, with search and filters by app, route and status. It refreshes every two seconds and can be paused. Selecting a connection shows three separate blocks: **Configured** (what your rules and assignments say should happen, a prediction), **Observed** (what the running core logged for this connection: its outbound and the rule that matched) and **Verified** (the last external check of that route). Nothing is written to disk and no payload is ever read; with the traffic gateway off, rows come from the core's log only and carry no byte counts.

**Run checks** looks up the public IP and country of the direct route and of every server in use, each through its own connection, and records when it last passed or failed. The system DNS section shows the resolvers Windows uses; they describe the computer, not a single server. The rule tester tells you which rule and which server a destination would get, and while connected every rule shows whether it is loaded in the running core.

<p align="center"><img src="docs/screenshots/inspector.png" width="820" alt="Connection diagnostics with one check per route"></p>

> [!NOTE]
> Diagnostics report what public servers observed at that moment, and the rule tester predicts a routing decision without opening a connection. Neither captures packets, and neither is a substitute for a dedicated leak test.

### Traffic

A live speed graph for the last 90 seconds, with exact values under the pointer, and download/upload totals for today, for the month, per day and per application. Totals are byte counts only: there is no per-server or per-destination history.

**Bandwidth by app** lists every selected application with its current rate, session totals, limits, how much of each limit it is using, and whether the limit is enforced or throttling right now. Limits can be edited or removed from each row.

<p align="center"><img src="docs/screenshots/traffic-history.png" width="820" alt="Traffic and usage"></p>

### Settings

Grouped into General (theme and reset), Connection (mode and Advanced routing), Routing (background quality checks, Auto margin and cooldown, route monitoring, the traffic gateway), Automation, Updates, Components, Backup & Restore, and a selectable Activity log.

**Backup & Restore** writes a `.prb` file with the parts you choose: subscriptions, manual servers, app assignments (with route modes and bandwidth limits), rules, profiles and preferences. Encryption with a password is on by default (AES-256-GCM, key derived with PBKDF2-HMAC-SHA256 and 600,000 iterations; every header field is authenticated). Restoring shows a preview first: what the backup contains, whether it is compatible, which applications are missing on this computer (with a way to point to their new location, or skip them), and **Merge** (adds what is new, keeps everything you have, including preferences) or **Replace** (replaces the parts the backup contains). A rollback snapshot is written before anything changes, the restore is applied in one step, and **Undo last restore** brings the previous setup back. Restoring works while disconnected.

**Reset everything** under *Settings > General* returns PerRoute to how it was when first installed: it clears every server and subscription, the selected applications and their routes, rules, profiles, the traffic history and all settings. It asks for confirmation, works only while disconnected, and cannot be undone. Installed components are not touched.

<p align="center"><img src="docs/screenshots/settings.png" width="820" alt="Connection settings"></p>

### Quick Access

Closing or minimizing the main window keeps PerRoute in the notification area. Clicking the tray icon opens Quick Access, where you can connect, switch the main server, and reopen the main window. The list starts with the servers in use (the main server, then any custom servers), followed by your favorites and then the rest. Jumping to the fastest server, **Use a server for a while** (one app on another server, for a limited time) and quitting are in its overflow menu; apps on a temporary server are listed with the time left and an End link. With Advanced routing on it shows how many applications and servers are involved; app routes themselves are edited in the main window.

<p align="center"><img src="docs/screenshots/quick-access.png" width="360" alt="Quick Access tray panel"></p>

### New in 3.5: where to find it

| Feature | Where |
|---|---|
| Temporary servers | An app's panel in Apps, the Connection screen, a server's menu in Servers, Quick Access |
| Compare with a live route | A server's menu in Servers, the Connection screen |
| Gaming test, Best for gaming | Servers: Test servers menu, a server's menu and its details, the sort menu |
| Subscription details | Servers: select a subscription, then the details button next to it |
| Recipes | Profiles > Recipes |
| Browser workspaces | Tools > Workspaces |
| Connection Doctor, Safety Center | Tools > Health |
| Local network | Settings > Local network |
| Network Time Machine, rollback | Settings > Recovery; **PerRoute Recovery** in the Start menu |

See [docs/releases/v3.5.0.md](docs/releases/v3.5.0.md) for how each works and its limits.

## Connection modes

| Mode | What it routes | Administrator | Notes |
|---|---|---|---|
| **Smart per-app** | Only the applications you select | Once, at install time | Works for applications with no proxy settings. Requires the per-app engine installed by the Full Installer. |
| **System proxy** | Applications that follow Windows proxy settings | Never | The previous Windows proxy settings are saved and restored exactly. |
| **Local proxy** | Applications you configure yourself | Never | SOCKS5 on `127.0.0.1:17890`, HTTP on `127.0.0.1:17891`. Nothing system-wide is changed. |

Connection modes, the app selection, app routes and rules cannot be changed while connected. Switching the main server and changing bandwidth limits are always available.

### Traffic gateway and bandwidth limits

In Smart per-app mode the per-app engine sends every connection of a selected application to a loopback port of its own. With the **traffic gateway** on (*Settings > Routing*), PerRoute listens on that port and passes each connection on, unchanged, to the application's own inbound in the core. Because every connection of an application passes the same place, PerRoute can:

- enforce one download and one upload limit for the application as a whole (all connections together, TCP and UDP), with a token bucket that holds at most 100 ms of traffic;
- count the application's bytes exactly, without polling the core's statistics every second;
- give the connection inspector each connection's destination, byte counts and open/closed state.

The gateway understands just enough SOCKS5 to read destinations and to carry UDP associations through its own relay. It never reads or stores payload, and routing decisions (server, rule, direct or block) stay with the core. Limits:

- Limits apply in Smart per-app mode only. System proxy and Local proxy have no per-application path, so their limits show **Not enforced**.
- Applications that are not selected are not routed through PerRoute and cannot be limited.
- The gateway adds a loopback hop: about 0.7–1.3 ms when a connection opens and roughly 0.2–0.7 ms of CPU per megabyte transferred (measured on loopback; see [docs/v3-engineering-report.md](docs/v3-engineering-report.md)). Throughput was not reduced in the same test.
- Priority classes (High/Normal/Low) are not offered: without a shared bottleneck they cannot be enforced honestly.

The gateway and the switch are part of the data path, so it can only be turned on or off while disconnected.

## Upgrading from 2.x

PerRoute 3 reads version 2 settings as they are. Nothing about your routing changes until you choose to:

- No application is in Auto mode, and every application is unlimited.
- On an installation upgraded from 2.x, background quality checks, route monitoring and the traffic gateway start **off**; turn them on in *Settings > Routing*. A fresh installation starts with them on.
- Without route monitoring or an Auto app, the core keeps the read-only statistics API of version 2, and switching the main server restarts the core as before.
- Profiles saved by version 2 still apply; they never put an application in Auto mode.
- The new settings are written alongside the old ones. Running version 2 again afterwards ignores them (and drops them on its next save).

The tunnel health check is stricter in 3.0: a route only passes when an HTTP request sent through it gets an answer from the far side. In 2.x a SOCKS acknowledgement from the local core was enough, which also passed for a server that was down.

## Security and privacy

- **Saved state is encrypted** with AES-256-GCM. The key is wrapped with Windows DPAPI for the signed-in account, so the state file cannot be opened from another Windows account or machine.
- **No telemetry.** PerRoute talks only to your subscription URLs, your proxy servers, the endpoints used by the tests you start, and the update feed you configure.
- **Traffic history is local** and stores byte totals only, never destinations.
- **Updates are verified.** Only an HTTPS manifest signed with the project's offline Ed25519 key is accepted, and the installer's size and SHA-256 are checked before it is used.
- **Pinned dependencies.** The Full Installer embeds unmodified, hash-verified copies of ProxiFyre, Windows Packet Filter and the Visual C++ Runtime.
- **Loopback only.** Local proxy, gateway and statistics ports listen on `127.0.0.1`. Loopback ports are not separated between Windows accounts: on a computer shared by several signed-in users, another user could use the local proxy ports, and, while route monitoring or an Auto app is in use, the core's routing API (which has no authentication). On a single-user computer this changes nothing.
- **Backups protect credentials.** Server credentials are inside a backup. Encrypted backups use AES-256-GCM with a password-derived key (PBKDF2-HMAC-SHA256, 600,000 iterations, random salt and nonce) and authenticate every header field; the password is never stored or logged. An unencrypted backup is only checksummed and should be kept like a password file.
- **The inspector keeps nothing.** Connections are held in memory, bounded (1,500 entries, closed ones for two minutes) and cleared on disconnect. Quality history stores server keys, times and latencies only.

### What happens if PerRoute stops unexpectedly

| Situation | Result |
|---|---|
| Normal exit, Windows sign-out, restart or shutdown | Windows proxy settings are restored and the core is stopped. |
| Forced close in System proxy mode | A small guard process restores the previous Windows proxy settings within seconds. Traffic returns to the direct route. |
| Power loss or sudden restart in System proxy mode | Windows restores the previous proxy settings at the next sign-in, before PerRoute is opened. |
| Unexpected stop in Smart per-app mode | Other applications are unaffected. With the kill switch on, selected applications stay blocked until PerRoute reconnects; with it off, they are released the next time PerRoute starts. With the traffic gateway on, the gateway stops together with PerRoute, so selected applications have no connection (they are not sent direct) until PerRoute starts again. |
| One route's server stops answering | Only the applications on that route are affected; they wait instead of falling back to the direct route. With route monitoring on, the route is retried with growing delays, rebuilt in place, and re-checked every two minutes until it answers; Auto apps move to another working server. |

## Build from source

**Prerequisites**

- Windows 10 or 11 x64
- JDK 21
- .NET Framework 4.x (only for building the installer; it ships with Windows)

**1. Get the Xray core.** The core binaries are not stored in this repository. Download the Windows 64-bit package from the [Xray-core releases](https://github.com/XTLS/Xray-core/releases) and place `xray.exe`, `geoip.dat`, `geosite.dat` and `wintun.dll` in `tools/xray/`.

**2. Run the tests.**

```powershell
.\test.ps1
```

The suite includes the connection logic of the controller, run against a fake machine so that no Xray process, driver or proxy setting is touched. When the Xray core is present in `tools/xray/`, generated configs are also validated with `xray run -test`, and an end-to-end test runs the real core and the traffic gateway on loopback against local VLESS servers: per-app routing, an in-place main switch that keeps another app's open connection, Auto failover when a server stops, an end-to-end bandwidth limit, and inspector observations from the core's own log. All of its ports are shifted (`-Dperroute.portOffset=20000`), so it can run next to an installed PerRoute; it never touches the per-app driver or Windows proxy settings.

To review the interface without a display, render every screen off-screen with demonstration data (baseline size, narrow size, 150% scaling, both themes) into `build/ui-snapshots/`:

```powershell
.\gradlew.bat renderUiSnapshots
```

Checks that need the installed driver and real servers are listed in [docs/v3-verification.md](docs/v3-verification.md) and [docs/advanced-routing-verification.md](docs/advanced-routing-verification.md).

**3. Run from source.**

```powershell
.\gradlew.bat run
```

**4. Build the release packages.**

```powershell
.\package.ps1
```

This produces the following in `dist/`:

| Output | Description |
|---|---|
| `PerRoute-Setup-3.5.1.exe` | Offline installer with the per-app engine |
| `PerRoute-Portable-3.5.1.zip` | Self-contained portable build |
| `PerRoute-3.5.1/` | Unpacked application image |

The installer dependencies are downloaded on first use and verified against pinned SHA-256 digests.

### Project layout

```
src/main/kotlin/app/perroute/compose   User interface (Compose Desktop) and application controller
src/main/kotlin/app/perroute/runtime   Quality measurements and per-route recovery scheduling
src/main/java/app/perroute/model       Servers, rules, profiles, bandwidth policies, application state
src/main/java/app/perroute/parser      Share-link and subscription parsers
src/main/java/app/perroute/service     Xray, per-app engine, system proxy, probes, updates
src/main/java/app/perroute/quality     Network quality scoring and history
src/main/java/app/perroute/routing     Route modes, Auto server selection, route health
src/main/java/app/perroute/gateway     Traffic gateway and rate limiting
src/main/java/app/perroute/inspector   Core log parsing and the in-memory connection table
src/main/java/app/perroute/backup      Backup format, encryption, preview and restore planning
src/main/java/app/perroute/netstate    Network snapshots, comparison and safe restore plans
src/main/java/app/perroute/update      Startup health and the installation kept for a rollback
src/main/java/app/perroute/subscription Subscription usage, expiry and change history
src/main/java/app/perroute/gaming      Gaming probe and score
src/main/java/app/perroute/shadow      Side-by-side comparison of a route and a candidate
src/main/java/app/perroute/recipe      Shareable recipes: format, validation and import plans
src/main/java/app/perroute/workspace   Browser workspaces
src/main/java/app/perroute/localnet    Local networks, devices and the trust policy
installer/WindowsRecovery.cs           PerRoute Recovery, the standalone network restore and rollback tool
src/main/java/app/perroute/persistence Encrypted state storage
src/test/java/app/perroute             Test suite
installer/                             Windows installer and uninstaller (C#)
tools/update/                          Update manifest signing tool
docs/screenshots/                      Images used in this document
```

### Publishing an update

Updates are distributed as a signed manifest next to the installer. Keep the private key outside the repository.

```powershell
java tools\update\UpdateManifestTool.java sign 3.5.1 dist\PerRoute-Setup-3.5.1.exe https://github.com/AliMirzad/PerRoute/releases/download/v3.5.1/PerRoute-Setup-3.5.1.exe release-secrets\perroute-update-private.pk8 dist\update.json
```

Attach `update.json` to the release. Users enable updates by entering this feed once in *Settings > Updates*:

```
https://github.com/AliMirzad/PerRoute/releases/latest/download/update.json
```

The public verification key is pinned in the application. Replacing the private key requires shipping a new build.

### Diagnostics

Setting the environment variable `PERROUTE_PERF=1` writes start-up and frame timing marks to `perf.log` in `%LOCALAPPDATA%\PerRoute`. It is off by default.

## Troubleshooting

| Problem | What to check |
|---|---|
| Smart per-app is disabled in Settings | The per-app engine is missing. Install with the Full Installer and approve its single Administrator prompt. |
| A subscription adds no servers | Open *Settings > Activity log* for the parser warnings. Supported formats are VLESS, VMess, Trojan and Shadowsocks share links (plain or Base64) and Xray JSON. |
| A selected application keeps its old route after connecting | Existing TCP connections may stay on their previous route until the application opens new ones. Restarting the application forces it. |
| Browsers have no internet after a crash | Open PerRoute once; it restores the Windows proxy settings on start. |
| A bandwidth limit shows *Not enforced* | Limits need Smart per-app mode and the traffic gateway (*Settings > Routing*, switched while disconnected). The status line says which one is missing. |
| An Auto app stays on the main server | No server in its pool has enough quality data yet. Run a ping or a real tunnel test in Servers, or turn on background quality checks. |
| A route shows *Recovering* | Only that route's server stopped answering; other routes are unaffected. Its apps wait (they never fall back to the direct route). Use **Retry now**, choose another server, or wait: a failed route is re-checked every two minutes and comes back by itself. |

## Third-party components

| Component | License | Use |
|---|---|---|
| [Xray-core](https://github.com/XTLS/Xray-core) | MPL-2.0 | Connection and protocol engine |
| [Wintun](https://www.wintun.net/) | WireGuard LLC license | Bundled with the core |
| [ProxiFyre](https://github.com/wiresock/proxifyre) | AGPL-3.0 | Per-application redirection; bundled unmodified in the Full Installer |
| [Windows Packet Filter](https://github.com/wiresock/ndisapi) | See project | Packet filter driver; bundled unmodified in the Full Installer |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) | Apache-2.0 | User interface |
| [Java Native Access](https://github.com/java-native-access/jna) | Apache-2.0 | Windows DPAPI binding |
| [ZXing](https://github.com/zxing/zxing) | Apache-2.0 | QR decoding |
| [Inter](https://rsms.me/inter/) | SIL OFL 1.1 | Interface typeface |

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details.

## License

PerRoute is free software, released under the [GNU General Public License v3.0](LICENSE). You may use, study, share and modify it; distributed modifications must remain under the same license and include their source.

Copyright © 2026 Ali Mirzad.

The bundled third-party components listed above are separate works under their own licenses.

## Disclaimer

PerRoute is a network client. You are responsible for using it in accordance with the laws and regulations that apply to you and with the terms of the services you connect to. The software is provided as is, without warranty of any kind.

## Author

Created by **Ali Mirzad** · [github.com/AliMirzad](https://github.com/AliMirzad)
