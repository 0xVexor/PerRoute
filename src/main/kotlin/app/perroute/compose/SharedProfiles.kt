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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ConnectionMode
import app.perroute.model.ConnectionProfile

internal fun scenarioIcon(id: String): ImageVector = when (id) {
    "work" -> Icons.Rounded.WorkOutline
    "gaming" -> Icons.Rounded.SportsEsports
    else -> Icons.Rounded.PlayCircleOutline
}

internal fun profileSummary(shell: Shell, profile: ConnectionProfile): Pair<String, Int> {
    val apps = profile.applicationPaths()
    val keys = shell.nodes.mapTo(hashSetOf()) { it.key() }
    val perApp = profile.mode() == ConnectionMode.NATIVE_PER_APP
    val assigned = if (profile.advancedRouting() && perApp)
        apps.mapNotNull { profile.appRoutes()[app.perroute.model.AppRoutes.normalize(it)] }.filter { it != profile.nodeKey() }.distinct()
    else emptyList()
    val missing = assigned.count { it !in keys } + if (profile.nodeKey().isNotBlank() && profile.nodeKey() !in keys) 1 else 0
    val main = shell.nodes.firstOrNull { it.key() == profile.nodeKey() }?.let(::serverName)
    val parts = buildList {
        if (perApp) add(plural(apps.size, "app"))
        add(main ?: if (profile.nodeKey().isBlank()) "No main server" else "Missing server")
        add(modeLabel(profile.mode()))
        if (profile.advancedRouting() && perApp) add("Advanced routing · ${plural(assigned.size + 1, "server")}")
        if (profile.rules().isNotEmpty()) add(plural(profile.rules().size, "rule"))
    }
    return parts.joinToString("  ·  ") to missing
}

internal fun onOff(value: Boolean) = if (value) "On" else "Off"

internal class ProfilesModel(private val shell: Shell) {
    private val controller = shell.controller
    val profiles = mutableStateListOf<ConnectionProfile>().also { it.addAll(controller.profiles()) }
    var activeScenario by mutableStateOf(controller.activeScenarioId())
    var activeProfile by mutableStateOf(controller.activeProfileId())
    var previewing by mutableStateOf<ScenarioPreview?>(null)

    fun refresh() {
        profiles.clear(); profiles.addAll(controller.profiles())
        activeScenario = controller.activeScenarioId()
        activeProfile = controller.activeProfileId()
        shell.refresh()
    }

    fun preview(scenario: Scenario) { previewing = controller.previewScenario(scenario.id) }
    fun closePreview() { previewing = null }

    fun applyPreview() {
        val preview = previewing ?: return
        previewing = null
        shell.notify(controller.applyScenario(preview.scenario.id))
        refresh()
    }

    fun clearPreset() { shell.notify(controller.clearScenario()); refresh() }
    fun apply(profile: ConnectionProfile) { shell.notify(controller.applyProfile(profile.id())); refresh() }
    fun replace(profile: ConnectionProfile) { shell.notify(controller.saveProfile(profile.name())); refresh() }

    fun delete(profile: ConnectionProfile) {
        shell.showDialog {
            ConfirmDialog("Delete ${profile.name()}?", "The saved profile is removed. Your current setup is not changed.",
                "Delete profile", shell::closeDialog, danger = true) {
                shell.closeDialog(); controller.removeProfile(profile.id()); refresh()
            }
        }
    }

    fun menu(profile: ConnectionProfile): List<MenuEntry> = listOf(
        MenuEntry("Replace with current setup", Icons.Rounded.Save) { replace(profile) },
        MenuEntry("Delete profile", Icons.Rounded.DeleteOutline, danger = true) { delete(profile) }
    )

    fun saveCurrent() {
        val name = mutableStateOf(profiles.firstOrNull { it.id() == activeProfile }?.name().orEmpty())
        shell.showDialog {
            val existing = profiles.any { it.name().equals(name.value.trim(), true) }
            DialogCard("Save current setup", "A profile stores your apps, main server, connection mode, Advanced routing with its app routes, and rules.",
                shell::closeDialog, width = 480.dp, actions = {
                    SecondaryButton("Cancel") { shell.closeDialog() }
                    PrimaryButton(if (existing) "Replace profile" else "Save profile", icon = Icons.Rounded.Save, enabled = name.value.isNotBlank()) {
                        shell.closeDialog()
                        shell.notify(controller.saveProfile(name.value))
                        refresh()
                    }
                }) {
                FieldLabel("Profile name")
                TextInput(name.value, { name.value = it.take(40) }, "For example: Office", icon = Icons.Rounded.Folder)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (existing) "A profile with this name exists and will be replaced."
                    else "Right now: ${modeLabel(shell.mode)}, ${plural(shell.apps.size, "app")}, " +
                        if (shell.advanced) "Advanced routing on with ${plural(shell.plan.routes().size, "server")}." else "main server only.",
                    color = if (existing) Amber else Muted, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
        }
    }
}

internal fun profilesModel(shell: Shell): ProfilesModel = shell.screen("${Tab.Profiles.name}:profiles") { ProfilesModel(shell) }

@Composable
internal fun PresetChanges(shell: Shell, preview: ScenarioPreview) {
    val locked = shell.locked
    Column {
        if (locked) {
            InfoBanner("Disconnect to apply a preset. You can still review what it would change.", Modifier.fillMaxWidth(), Amber, Icons.Rounded.Lock)
            Spacer(Modifier.height(12.dp))
        }
        Text("Automation", color = White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        preview.settings.forEach { change ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(change.label, color = White.copy(.9f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (change.from == change.to) Text("stays ${onOff(change.to)}", color = Muted, fontSize = 11.5.sp)
                else {
                    Text(onOff(change.from), color = Muted, fontSize = 11.5.sp)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "changes to", tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(onOff(change.to), color = if (Tok.isNew) Violet2 else Violet2, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        Text("Rules added (${preview.addedRules.size})", color = White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        preview.addedRules.forEach { rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(actionIcon(rule.action), null, tint = actionColor(rule.action), modifier = Modifier.size(15.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, color = White.copy(.92f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("${matchLabel(rule.type)}  ·  ${rule.value}", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(actionLabel(rule.action), color = actionColor(rule.action), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        Text("Rules removed (${preview.removedRules.size})", color = White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (preview.removedRules.isEmpty()) Text("No rules of yours are removed.", color = Muted, fontSize = 11.5.sp)
        else preview.removedRules.forEach { rule ->
            Text("•  ${PerRouteController.ruleDisplayName(rule)} (from the current preset)", color = White.copy(.85f), fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        Note("Your apps, servers, app routes, connection mode and own rules are kept exactly as they are.")
    }
}

internal fun presetActionLabel(preview: ScenarioPreview): String = if (preview.alreadyActive) "Apply again" else "Apply preset"

internal fun showPresetDialog(shell: Shell, model: ProfilesModel, scenario: Scenario) {
    model.preview(scenario)
    val preview = model.previewing ?: return
    shell.showDialog(onDismiss = { model.closePreview(); shell.closeDialog() }) {
        DialogCard("${scenario.title} preset", scenario.summary, { model.closePreview(); shell.closeDialog() }, width = 560.dp, actions = {
            SecondaryButton("Cancel") { model.closePreview(); shell.closeDialog() }
            PrimaryButton(presetActionLabel(preview), enabled = !shell.locked) { shell.closeDialog(); model.applyPreview() }
        }) { PresetChanges(shell, preview) }
    }
}

