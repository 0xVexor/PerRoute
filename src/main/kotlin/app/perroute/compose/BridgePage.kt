package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.bridge.BridgeLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BridgeModel(private val shell: Shell) {
    private val controller = shell.controller
    var view by mutableStateOf(controller.bridgeView())
    var addresses by mutableStateOf<List<String>>(emptyList())
    var address by mutableStateOf("")
    var minutes by mutableStateOf(60)
    var selected by mutableStateOf<String?>(null)

    fun refresh() { view = controller.bridgeView() }

    suspend fun loadAddresses() {
        addresses = withContext(Dispatchers.IO) { controller.bridgeAddresses() }
        if (address !in addresses) address = addresses.firstOrNull().orEmpty()
    }

    fun hostFor(): String = view.address.ifBlank { address }

    fun start() {
        val (chosen, length) = address to minutes
        shell.scope.launch {
            val ready = withContext(Dispatchers.IO) { controller.bridgeFirewallReady() }
            if (ready) { shell.run(done = { refresh() }) { controller.startBridge(chosen, length, allowFirewall = false) }; return@launch }
            shell.showDialog {
                DialogCard("Let your devices in?", "Windows Firewall blocks devices on your network until you allow it once.", shell::closeDialog, width = 500.dp, actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    PrimaryButton("Allow and share") {
                        shell.closeDialog()
                        shell.run(done = { refresh() }) { controller.startBridge(chosen, length, allowFirewall = true) }
                    }
                }) {
                    DialogText("Windows asks for Administrator approval once to add a firewall rule for PerRoute's sharing ports " +
                        "(${PerRouteController.BRIDGE_PORT_BASE}–${PerRouteController.BRIDGE_PORT_BASE + app.perroute.bridge.BridgeDevice.SLOTS - 1}).")
                    Spacer(Modifier.height(8.dp))
                    DialogText("The rule only applies to private networks, and only the copy of Xray that comes with PerRoute may use it. Nothing listens on those ports while sharing is off.", Muted)
                }
            }
        }
    }

    fun stop() = shell.run(done = { refresh() }) { controller.stopBridge() }

    fun add() {
        val name = mutableStateOf("")
        shell.showDialog {
            DialogCard("Add a device", "Each device gets its own port and password, so you can remove one without the others.", shell::closeDialog, width = 480.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton("Add", enabled = name.value.isNotBlank() && !shell.busy) {
                    shell.closeDialog()
                    val chosen = name.value
                    shell.run(done = { result ->
                        refresh()
                        view.devices.firstOrNull { it.device.name().equals(chosen.trim(), true) }?.let { added ->
                            selected = added.device.id()
                            if (result.ok && Appearance.style != UiStyle.NEW) setup(added)
                        }
                    }) { controller.addBridgeDevice(chosen) }
                }
            }) {
                FieldLabel("Name")
                TextInput(name.value, { name.value = it.take(32) }, "My phone, Tablet, TV…")
            }
        }
    }

    fun rename(device: BridgeDeviceView) {
        val name = mutableStateOf(device.device.name())
        shell.showDialog {
            DialogCard("Rename ${device.device.name()}", "The name is only shown here. The device keeps its password.", shell::closeDialog, width = 460.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton("Save", enabled = name.value.isNotBlank()) {
                    shell.closeDialog()
                    val chosen = name.value
                    shell.run(silent = true, done = { refresh() }) { controller.renameBridgeDevice(device.device.id(), chosen) }
                }
            }) {
                FieldLabel("Name")
                TextInput(name.value, { name.value = it.take(32) }, "Name")
            }
        }
    }

    fun remove(device: BridgeDeviceView) {
        shell.showDialog {
            DialogCard("Remove ${device.device.name()}?", "Its password stops working at once.", shell::closeDialog, width = 460.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                PrimaryButton("Remove", color = Red) {
                    shell.closeDialog()
                    shell.run(done = { refresh(); if (selected == device.device.id()) selected = null }) { controller.removeBridgeDevice(device.device.id()) }
                }
            }) {
                DialogText(if (view.running) "Sharing restarts without it, so other devices reconnect within a moment." else "To use it again, add it again and set it up from the start.", Muted)
            }
        }
    }

    fun setup(device: BridgeDeviceView) {
        shell.showDialog {
            DialogCard("Set up ${device.device.name()}", "Join the same Wi-Fi as this computer, then use one of these.", shell::closeDialog, width = 640.dp, actions = {
                PrimaryButton("Done") { shell.closeDialog() }
            }) { BridgeSetup(this@BridgeModel, device) }
        }
    }
}

internal fun bridgeModel(shell: Shell): BridgeModel = shell.screen("${Tab.Bridge.name}:model") { BridgeModel(shell) }

@Composable
internal fun BridgePage(shell: Shell) {
    if (!shell.controller.lanBridgeSupported()) {
        UnavailableFeature("LAN Bridge", "Device sharing and its firewall setup have not been ported.")
        return
    }
    val model = bridgeModel(shell)
    LaunchedEffect(Unit) {
        model.loadAddresses()
        var tick = 0
        while (isActive) {
            model.refresh()
            if (++tick % 10 == 0) model.loadAddresses()
            delay(2_000)
        }
    }
    val view = model.view
    if (model.selected == null || view.devices.none { it.device.id() == model.selected }) model.selected = view.devices.firstOrNull()?.device?.id()
    FeaturePage(listOf("Tools", "LAN Bridge"), "LAN Bridge", "Let your phone or another device on your Wi-Fi use this computer's connection, for as long as you choose.",
        actions = { PrimaryButton("Add device", icon = Icons.Rounded.Add, enabled = !shell.busy) { model.add() } },
        panel = if (Tok.isNew) ({ BridgePanel(shell, model) }) else null) {
        SharingBlock(shell, model)
        FeatureBlock("Devices", if (view.devices.isEmpty()) "" else "Each device has its own port and password.") {
            Spacer(Modifier.height(8.dp))
            if (view.devices.isEmpty()) {
                EmptyState(Icons.Rounded.Devices, "No devices yet", "Add your phone, tablet or TV. PerRoute gives it a password of its own and a code to scan.", Modifier.fillMaxWidth()) {
                    PrimaryButton("Add device", icon = Icons.Rounded.Add) { model.add() }
                }
            }
            val now = System.currentTimeMillis()
            view.devices.forEach { device -> DeviceRow(shell, model, device, now) }
        }
        Note("Shared devices go through your main server and cannot reach this computer or other devices on your network. Sharing stops when PerRoute closes.")
    }
}

@Composable
private fun SharingBlock(shell: Shell, model: BridgeModel) {
    val view = model.view
    if (view.running) {
        FeatureBlock("Sharing on ${view.address}", "Through ${view.server.ifBlank { "the main server" }}  ·  " +
            (if (view.until > 0) "until ${formatTimeOfDay(view.until)}" else "until you stop it"), first = true, trailing = {
            Badge("On", Cyan, filled = true)
            SecondaryButton("Stop sharing", icon = Icons.Rounded.Stop, enabled = !shell.busy, tint = Red) { model.stop() }
        })
        return
    }
    FeatureBlock("Sharing is off", "Choose the network your device is on and how long to share.", first = true) {
        Spacer(Modifier.height(12.dp))
        if (model.addresses.isEmpty()) {
            Text("This computer is not on a local network right now.", color = Amber, fontSize = 12.5.sp)
            return@FeatureBlock
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Network")
                val networks = shell.controller.localNetworks()
                MenuButton(model.address.ifBlank { "Choose" }, model.addresses.map { address ->
                    MenuEntry(address, detail = networks.firstOrNull { it.address() == address }?.adapter().orEmpty(), selected = address == model.address) { model.address = address }
                }, Modifier.fillMaxWidth(), accessibleLabel = "Network to share on")
            }
            Column(Modifier.weight(1.3f)) {
                FieldLabel("For")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("1 hour", model.minutes == 60) { model.minutes = 60 }
                    FilterChip("4 hours", model.minutes == 240) { model.minutes = 240 }
                    FilterChip("Until I stop", model.minutes == 0) { model.minutes = 0 }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Start sharing", icon = Icons.Rounded.PlayArrow, enabled = !shell.busy && model.address.isNotBlank() && model.view.devices.isNotEmpty() && shell.nodes.isNotEmpty()) { model.start() }
            Spacer(Modifier.width(12.dp))
            val hint = when {
                shell.nodes.isEmpty() -> "Add a server first."
                model.view.devices.isEmpty() -> "Add a device first."
                else -> "Uses the main server${shell.mainNode?.let { ", " + serverName(it) } ?: ""}."
            }
            Text(hint, color = Muted, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun DeviceRow(shell: Shell, model: BridgeModel, device: BridgeDeviceView, now: Long) {
    FeatureRow(model.selected == device.device.id() && Tok.isNew, onClick = { model.selected = device.device.id() }, label = device.device.name()) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(NeutralOverlay.copy(.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Smartphone, null, tint = White.copy(.85f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EllipsisText(device.device.name(), White, 14.sp, Modifier.weight(1f, fill = false), FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                when {
                    model.view.running && device.activity.activeAt(now) -> Badge("Active", Cyan)
                    device.activity.lastSeen() > 0 -> Badge("Seen ${formatAgo(device.activity.lastSeen(), now)}", Muted)
                    else -> Badge("Not connected yet", Muted)
                }
            }
            Text(activityLine(device), color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        SecondaryButton("Set up", icon = Icons.Rounded.QrCode2, height = 34.dp) { model.setup(device) }
        if (!Tok.isNew) {
            Spacer(Modifier.width(4.dp))
            MenuIcon(Icons.Rounded.Edit, "More for ${device.device.name()}", listOf(
                MenuEntry("Rename") { model.rename(device) },
                MenuEntry("Remove", danger = true) { model.remove(device) }))
        }
    }
}

private fun activityLine(device: BridgeDeviceView): String {
    val activity = device.activity
    val parts = mutableListOf("Port ${device.port}")
    if (activity.address().isNotBlank()) parts += "from ${activity.address()}"
    if (activity.recent().isNotEmpty()) parts += activity.recent().take(3).joinToString(", ")
    return parts.joinToString("  ·  ")
}

@Composable
private fun ColumnScope.BridgePanel(shell: Shell, model: BridgeModel) {
    val device = model.view.devices.firstOrNull { it.device.id() == model.selected }
    if (device == null) { NewPanelEmpty("Device", "No device selected", "Add a device to share this computer's connection with it."); return }
    PanelHeading("Device", device.device.name(), if (model.view.running && device.activity.activeAt(System.currentTimeMillis())) "Active now" else "Port ${device.port}")
    PanelSection { BridgeSetup(model, device, stacked = true) }
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Rename", Modifier.weight(1f), icon = Icons.Rounded.Edit, enabled = !shell.busy) { model.rename(device) }
        SecondaryButton("Remove", Modifier.weight(1f), tint = Red, enabled = !shell.busy) { model.remove(device) }
    }
}

@Composable
private fun BridgeSetup(model: BridgeModel, device: BridgeDeviceView, stacked: Boolean = false) {
    val host = model.hostFor()
    if (host.isBlank()) {
        Text("Connect this computer to your Wi-Fi to see the address.", color = Amber, fontSize = 12.5.sp)
        return
    }
    val link = remember(host, device.port, device.device) { BridgeLinks.socks(host, device.port, device.device) }
    val code = remember(link) { runCatching { qrBitmap(link) }.getOrNull() }
    val qr: @Composable () -> Unit = {
        if (code != null) {
            Box(Modifier.size(156.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp)) {
                Image(code, "Setup code for ${device.device.name()}", Modifier.size(144.dp))
            }
        }
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Text("Scan with a proxy app", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Most proxy apps on Android and iPhone import this code or the socks link below.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(10.dp))
            CopyLine("Server", host)
            CopyLine("Port", device.port.toString())
            CopyLine("User name", device.device.user())
            CopyLine("Password", device.device.password())
            CopyLine("Link", link)
        }
    }
    if (stacked) {
        qr()
        Spacer(Modifier.height(14.dp))
        details(Modifier.fillMaxWidth())
    } else Row(verticalAlignment = Alignment.Top) {
        if (code != null) { qr(); Spacer(Modifier.width(16.dp)) }
        details(Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Text("iPhone or iPad without an app", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(3.dp))
    Text("Settings > Wi-Fi > ⓘ next to your network > Configure Proxy > Manual. Enter the server and port, turn on Authentication, and enter the user name and password.",
        color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    Spacer(Modifier.height(8.dp))
    Text("Android without an app", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(3.dp))
    Text("Android's own Wi-Fi proxy setting cannot send a password, so use a proxy app and scan the code.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    if (!model.view.running) {
        Spacer(Modifier.height(10.dp))
        Text("Start sharing so the device can connect.", color = Amber, fontSize = 12.5.sp)
    }
}

@Composable
private fun CopyLine(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        EllipsisText(value, White, 12.5.sp, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
        IconAction(Icons.Rounded.ContentCopy, "Copy $label", size = 26.dp, bordered = false) { clipboard.setText(AnnotatedString(value)) }
    }
}

internal fun qrBitmap(text: String, size: Int = 288): ImageBitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    return com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix).toComposeImageBitmap()
}
