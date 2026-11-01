package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.localnet.LocalNetwork
import app.perroute.netstate.NetworkRestorePlan
import app.perroute.netstate.NetworkSnapshot
import app.perroute.netstate.NetworkStateDiff
import app.perroute.netstate.NetworkStateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RecoveryModel(private val shell: Shell) {
    private val controller = shell.controller
    var loading by mutableStateOf(false)
    var current by mutableStateOf<NetworkSnapshot?>(null)
    var plan by mutableStateOf<NetworkRestorePlan?>(null)
    var snapshots by mutableStateOf<List<NetworkSnapshot>>(emptyList())
    var compareWith by mutableStateOf<Long?>(null)
    var showUnchanged by mutableStateOf(false)
    var rollback by mutableStateOf<String?>(null)
    var recoveryTool by mutableStateOf(false)
    var health by mutableStateOf<app.perroute.update.StartupHealth.Status?>(null)

    fun reload() {
        if (loading) return
        shell.scope.launch {
            loading = true
            try {
                withContext(Dispatchers.IO) {
                    current = controller.currentNetworkState()
                    plan = controller.networkRestorePlan()
                    snapshots = controller.networkSnapshots().sortedByDescending { it.id() }
                    rollback = controller.rollbackVersion()
                    recoveryTool = controller.recoveryToolAvailable()
                    health = controller.startupHealth()
                }
            } finally { loading = false }
        }
    }

    fun reference(): NetworkSnapshot? = compareWith?.let { id -> snapshots.firstOrNull { it.id() == id } } ?: controller.networkBaseline() ?: snapshots.lastOrNull()

    fun changes(): List<NetworkStateDiff.Change> {
        val before = reference() ?: return emptyList()
        val now = current ?: return emptyList()
        return NetworkStateDiff.compare(before, now, showUnchanged)
    }

    fun snapshot() {
        shell.scope.launch {
            val taken = withContext(Dispatchers.IO) { controller.takeNetworkSnapshot(NetworkSnapshot.Reason.MANUAL) }
            shell.notify(if (taken != null) ActionResult(true, "Snapshot taken.") else ActionResult(false, "The network settings could not be read."))
            reload()
        }
    }

    fun restore() {
        val steps = plan ?: return
        shell.showDialog {
            DialogCard("Restore network settings?", "Only what PerRoute itself changed is undone.", shell::closeDialog, width = 520.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton("Restore", icon = Icons.Rounded.Restore) {
                    shell.closeDialog()
                    shell.run(done = { reload() }) { controller.restoreNetwork() }
                }
            }) {
                steps.actions().forEach { step ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(step.title() + if (step.needsAdmin()) " (needs Administrator)" else "", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(step.detail(), color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                    }
                }
                steps.leftAlone().forEach { step ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = Muted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(step.title(), color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(step.detail(), color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                    }
                }
                if (steps.actions().any { it.releasesApps() }) {
                    Spacer(Modifier.height(6.dp))
                    InfoBanner("Apps the kill switch was keeping offline will use your direct connection afterwards.", color = Amber)
                }
            }
        }
    }

    fun rollBack() {
        val version = rollback ?: return
        shell.showDialog {
            ConfirmDialog("Go back to PerRoute $version?", "Your servers, apps, rules and settings are kept. Windows asks for Administrator approval; PerRoute closes and $version opens when it is done.",
                "Go back to $version", shell::closeDialog) {
                shell.closeDialog()
                shell.run(done = { result -> if (result.ok) shell.exitApp() }) { controller.rollBack() }
            }
        }
    }

    fun openTool() = shell.run { controller.openRecoveryTool() }
}

internal fun recoveryModel(shell: Shell): RecoveryModel = settingsModel(shell, SettingsCategory.Recovery) { RecoveryModel(shell) }

@Composable
internal fun RecoverySettings(shell: Shell) {
    if (!shell.controller.capabilities().networkRecovery()) {
        UnavailableFeature("Recovery", "macOS restores owned proxy and routing settings on Disconnect and during helper recovery.")
        return
    }
    val model = recoveryModel(shell)
    LaunchedEffect(shell.connected) { model.reload() }
    val plan = model.plan
    FeatureBlock("Network state", "PerRoute changes the Windows proxy, a start-up entry and the per-app engine while it is connected. It saves what was there and puts it back.", first = true, trailing = {
        SecondaryButton(if (model.loading) "Reading…" else "Check again", icon = Icons.Rounded.Refresh, enabled = !model.loading) { model.reload() }
    }) {
        Spacer(Modifier.height(12.dp))
        when {
            plan == null -> LoadingState("Reading the network settings…", Modifier.fillMaxWidth())
            plan.blocked() != null -> InfoBanner(plan.blocked()!!, Modifier.fillMaxWidth())
            !plan.needed() -> InfoBanner("Clean. PerRoute has left nothing behind." + if (plan.leftAlone().isNotEmpty()) " Settings made by other programs are left as they are." else "",
                Modifier.fillMaxWidth(), Cyan, Icons.Rounded.CheckCircle)
            else -> {
                InfoBanner("PerRoute left ${plural(plan.actions().size, "setting")} behind, for example after a crash or a forced shutdown.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.WarningAmber)
                Spacer(Modifier.height(10.dp))
                plan.actions().forEach { step -> Text("•  ${step.title()}", color = White.copy(.85f), fontSize = 12.5.sp, lineHeight = 18.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Restore network settings", icon = Icons.Rounded.Restore, enabled = plan?.needed() == true && !shell.busy) { model.restore() }
            SecondaryButton("Take a snapshot", icon = Icons.Rounded.CameraAlt, enabled = !shell.busy) { model.snapshot() }
        }
    }

    FeatureBlock("Compare", "What changed since a snapshot, and who changed it. PerRoute only ever puts back its own changes.") {
        Spacer(Modifier.height(10.dp))
        val reference = model.reference()
        if (reference == null || model.current == null) Text("No snapshot yet. PerRoute takes one when it starts, when you connect and when you disconnect.", color = Muted, fontSize = 12.5.sp)
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From", color = Muted, fontSize = 12.5.sp)
                Spacer(Modifier.width(10.dp))
                MenuButton("${reference.reason().label()}, ${formatSnapshotTime(reference.takenAt())}", model.snapshots.map { snapshot ->
                    MenuEntry("${snapshot.reason().label()}", detail = formatSnapshotTime(snapshot.takenAt()) + if (snapshot.id() == shell.controller.networkBaseline()?.id()) "  ·  baseline" else "",
                        selected = snapshot.id() == reference.id()) { model.compareWith = snapshot.id() }
                }, Modifier.width(300.dp), icon = Icons.Rounded.History, accessibleLabel = "Snapshot to compare with", menuWidth = 340.dp)
                Spacer(Modifier.width(10.dp))
                Text("to now", color = Muted, fontSize = 12.5.sp)
                Spacer(Modifier.weight(1f))
                Text("Show unchanged", color = White.copy(.85f), fontSize = 12.5.sp)
                Spacer(Modifier.width(10.dp))
                ToggleSwitch(model.showUnchanged, "Show unchanged", showState = false) { model.showUnchanged = it }
            }
            Spacer(Modifier.height(10.dp))
            val changes = model.changes()
            if (changes.isEmpty()) Text("Nothing changed.", color = Muted, fontSize = 12.5.sp)
            else changes.forEach { change -> ChangeRow(change) }
        }
    }

    FeatureBlock("Recovery tool", "A small program of its own that puts the network back even when PerRoute itself does not open. Find it in the Start menu as PerRoute Recovery.", trailing = {
        SecondaryButton("Open recovery tool", icon = Icons.Rounded.SettingsBackupRestore, enabled = model.recoveryTool && !shell.busy) { model.openTool() }
    }) {
        if (!model.recoveryTool) { Spacer(Modifier.height(8.dp)); Note("It comes with the installed PerRoute.") }
    }

    FeatureBlock("Previous version", model.rollback?.let { "PerRoute $it is kept on this computer. Going back keeps your setup." }
        ?: "The installer keeps the version it replaces, so you can go back without a download. None is kept right now.", trailing = {
        if (model.rollback != null) SecondaryButton("Go back to ${model.rollback}", icon = Icons.Rounded.Restore, enabled = !shell.busy) { model.rollBack() }
    }) {
        model.health?.let { status ->
            if (status.version().isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(when {
                    status.struggling() -> "This version did not finish starting ${status.failedStarts()} times in a row."
                    status.updatedFrom().isNotBlank() -> "Updated from ${status.updatedFrom()} on ${formatSnapshotTime(status.updatedAt())}. It starts properly."
                    else -> "This version starts properly."
                }, color = if (status.struggling()) Amber else Muted, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun ChangeRow(change: NetworkStateDiff.Change) {
    val color = when (change.kind()) {
        NetworkStateDiff.Kind.BY_PERROUTE -> Violet2
        NetworkStateDiff.Kind.PUT_BACK -> Cyan
        NetworkStateDiff.Kind.ELSEWHERE -> Amber
        NetworkStateDiff.Kind.UNCHANGED -> Muted
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(change.key().group() + " · " + change.key().label(), color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Badge(change.kind().label(), color)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            EllipsisText(change.beforeDisplay(), Muted, 11.5.sp, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            Text("  →  ", color = Muted, fontSize = 11.5.sp)
            EllipsisText(change.afterDisplay(), White.copy(.9f), 11.5.sp, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
        }
    }
    Divider()
}

internal fun formatSnapshotTime(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ENGLISH))

@Composable
internal fun RollbackNote(shell: Shell) {
    if (!shell.controller.capabilities().updates()) return
    val model = recoveryModel(shell)
    LaunchedEffect(Unit) { if (model.plan == null) model.reload() }
    val version = model.rollback ?: return
    Spacer(Modifier.height(12.dp))
    InfoBanner("PerRoute $version is kept for a rollback. If this version gives you trouble, go back without downloading anything.", Modifier.fillMaxWidth(), action = {
        LinkAction("Go back", arrow = false, enabled = !shell.busy) { model.rollBack() }
    })
}

internal class LocalNetworkModel(private val shell: Shell) {
    private val controller = shell.controller
    var networks by mutableStateOf<List<LocalNetwork>?>(null)
    var loading by mutableStateOf(false)
    var names by mutableStateOf(false)
    var localNames by mutableStateOf(controller.localNamesDirect())
    val open = mutableStateMapOf<String, Boolean>()
    var untrusted by mutableStateOf(controller.untrustedNetworks())

    fun reload() {
        if (loading) return
        shell.scope.launch {
            loading = true
            try { networks = withContext(Dispatchers.IO) { controller.localNetworks(refresh = true, resolveNames = names) } }
            finally { loading = false }
        }
    }

    fun changeLocalNames(value: Boolean) { localNames = value; shell.notify(controller.setLocalNamesDirect(value)) }

    fun setTrusted(network: LocalNetwork, trusted: Boolean) {
        val label = "${network.adapter()} (${network.cidr()})"
        if (!trusted) {
            shell.showDialog {
                ConfirmDialog("Do not trust this network?", "Routed apps will not reach devices on $label, such as printers or a NAS. The router and the network's DNS servers stay reachable, " +
                    "and apps that are not routed are not affected.", "Do not trust", shell::closeDialog) {
                    shell.closeDialog()
                    shell.notify(controller.setNetworkTrusted(network.id(), label, false))
                    untrusted = controller.untrustedNetworks()
                }
            }
        } else {
            shell.notify(controller.setNetworkTrusted(network.id(), label, true))
            untrusted = controller.untrustedNetworks()
        }
    }

    fun forget(id: String, label: String) {
        shell.notify(controller.setNetworkTrusted(id, label, true))
        untrusted = controller.untrustedNetworks()
    }
}

@Composable
internal fun LocalNetworkSettings(shell: Shell) {
    if (!shell.controller.capabilities().networkRecovery()) {
        UnavailableFeature("Local network controls", "Network discovery and trust controls have not been ported. This page does not verify local-network protection.")
        return
    }
    val model = settingsModel(shell, SettingsCategory.LocalNetwork) { LocalNetworkModel(shell) }
    LaunchedEffect(Unit) { if (model.networks == null) model.reload() }
    FeatureBlock("Local devices", "Printers, NAS drives, the router and other devices on your network are always reached directly, never through a server, which could not reach them.", first = true)
    FeatureBlock("Keep local names local", "Names that only exist on local networks, such as printer.local, nas.lan or router.home.arpa, go straight to the local network instead of to the proxy server.",
        trailing = { ToggleSwitch(model.localNames, "Keep local names local") { model.changeLocalNames(it) } })
    FeatureBlock("Networks you are on", "Mark a network as not trusted, for example public Wi-Fi, and routed apps cannot reach its devices. Its router and DNS stay reachable.", trailing = {
        SecondaryButton(if (model.loading) "Looking…" else "Refresh", icon = Icons.Rounded.Refresh, enabled = !model.loading) { model.reload() }
    }) {
        Spacer(Modifier.height(10.dp))
        val networks = model.networks
        when {
            networks == null -> LoadingState("Looking at your networks…", Modifier.fillMaxWidth())
            networks.isEmpty() -> Text("No local network was found.", color = Muted, fontSize = 12.5.sp)
            else -> networks.forEach { network -> NetworkCard(shell, model, network) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show device names (asks your network's DNS)", color = White.copy(.85f), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            ToggleSwitch(model.names, "Show device names") { model.names = it; model.reload() }
        }
        if (shell.connected) { Spacer(Modifier.height(8.dp)); Note("Changes here apply from the next connection.") }
    }
    val known = model.untrusted.filterKeys { id -> model.networks?.none { it.id() == id } != false }
    if (known.isNotEmpty()) FeatureBlock("Other networks you do not trust", "They apply again when you join them.") {
        Spacer(Modifier.height(6.dp))
        known.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label.ifBlank { "Network" }, color = White, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                LinkAction("Trust again", arrow = false) { model.forget(id, label) }
            }
            Divider()
        }
    }
}

@Composable
private fun NetworkCard(shell: Shell, model: LocalNetworkModel, network: LocalNetwork) {
    val trusted = network.id() !in model.untrusted
    val open = model.open[network.id()] == true
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Router, null, tint = if (trusted) Cyan else Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(network.adapter(), color = White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text("${network.cidr()}  ·  this computer ${network.address()}" + (network.gateway().takeIf { it.isNotBlank() }?.let { "  ·  router $it" } ?: ""),
                    color = Muted, fontSize = 11.5.sp)
            }
            Text(if (trusted) "Trusted" else "Not trusted", color = if (trusted) Cyan else Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            ToggleSwitch(trusted, "Trust ${network.adapter()}", showState = false) { model.setTrusted(network, it) }
        }
        Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(4.dp), label = if (open) "Hide devices" else "Show devices") { model.open[network.id()] = !open }
            .padding(start = 34.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = White.copy(.7f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(plural(network.devices().size, "device") + " seen", color = White.copy(.85f), fontSize = 12.sp)
        }
        if (open) Column(Modifier.padding(start = 34.dp)) {
            if (network.devices().isEmpty()) Text("Windows has not talked to other devices here yet.", color = Muted, fontSize = 11.5.sp)
            network.devices().sortedWith(compareByDescending<LocalNetwork.Device> { it.gateway() }.thenBy { it.address().split('.').last().toIntOrNull() ?: 0 }).forEach { device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(device.address(), color = White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(130.dp))
                    EllipsisText(device.name().ifBlank { if (device.gateway()) "Router" else "Device" }, White.copy(.85f), 12.sp, Modifier.weight(1f))
                    if (device.gateway()) { Badge("Router", Cyan); Spacer(Modifier.width(8.dp)) }
                    Text(device.mac(), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(if (trusted) "Routed apps reach these devices directly." else "Routed apps cannot reach these devices, except the router and DNS.", color = Muted, fontSize = 11.5.sp)
        }
        Divider()
    }
}
