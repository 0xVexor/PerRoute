# Third-party notices

PerRoute starts or manages the following independent programs and libraries:

- Xray-core — Mozilla Public License 2.0 — https://github.com/XTLS/Xray-core
- Wintun — license terms published by WireGuard LLC — https://www.wintun.net/
- FlatLaf — Apache License 2.0 — https://github.com/JFormDesigner/FlatLaf
- ProxiFyre — GNU Affero General Public License 3.0 — https://github.com/wiresock/proxifyre
- Windows Packet Filter / NDISAPI 3.6.2.1 - https://github.com/wiresock/ndisapi
- Microsoft Visual C++ 2015-2022 Redistributable 14.44.35211.0 - https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist

The Full Installer embeds unmodified official x64 installers for ProxiFyre,
Windows Packet Filter, and the Visual C++ Runtime. Their pinned SHA-256 digests
are verified while packaging and again before execution. ProxiFyre remains a
separate product and Windows service. Its corresponding source code and license
information are available from the repository above. Redistributors are
responsible for satisfying the AGPL-3.0 source-offer obligations and the license
terms that apply to Windows Packet Filter and the Microsoft runtime.

## Java Native Access (JNA)

PerRoute bundles the unmodified JNA 5.17.0 library and its `jnidispatch.dll`
stub to call Windows DPAPI in-process. JNA is dual-licensed under the Apache
License 2.0 and the LGPL 2.1 or later; PerRoute uses it under Apache-2.0.
Source: https://github.com/java-native-access/jna

## Browser extension and runtime

The browser runtime bundles the unmodified official Xray-core release (pinned by SHA-256)
and statically links the Rust crates listed in browser/licenses/THIRD-PARTY.md. The
extension bundles jsQR (Apache License 2.0) and the Inter typeface (SIL OFL 1.1). The
license texts are installed with the runtime in its `licenses` folder.

## Optional macOS per-app development checker

The explicit `checkMacosPerAppRoutingCandidate` task downloads and runs the
unmodified standalone sing-box 1.14.3 configuration checker. Official macOS
archive SHA-256 digests are pinned in `scripts/verify_macos_per_app.py`. The
engine stays in temporary development staging and is not bundled with PerRoute
or started as a TUN service by this task.

Source and exact license: https://github.com/SagerNet/sing-box/tree/v1.14.3
and https://github.com/SagerNet/sing-box/blob/v1.14.3/LICENSE. Upstream specifies
GPL-3.0-or-later and an additional restriction on derivative-work naming or
implied association. Any future distribution must retain the applicable
license/source obligations and review those terms before bundling. PerRoute
does not claim upstream affiliation.

## Optional local macOS routing bundle

The explicit `stageMacosRoutingHelper` task stages the same checksum-pinned
sing-box 1.14.3 as a separate executable in a local development app. It retains
the upstream license, includes the GPL version 3 text, and records the exact
upstream revision, archive/binary hashes and source locations in the bundle.
The local code signature uses a PerRoute identifier and does not imply upstream
affiliation. The ordinary local helper and preview tasks retain their existing
scope.

Local bundle staging does not validate release redistribution or corresponding
source delivery. Before distributing a release, supply and verify the complete
corresponding source and applicable build/dependency materials under the terms
of the included license. The provenance explicitly records that this release
check is pending. The routing engine is managed as an independent process.
