package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.RouteRule

@Composable
internal fun NewRulesPage(shell: Shell) {
    val model = rulesModel(shell)
    val locked = shell.locked
    NewSplitPage(panel = { NewRulePanel(shell, model) }) {
        Column(Modifier.fillMaxSize()) {
            NewHeading(listOf("Tools", "Rules"), "Rules", "The first match decides where traffic goes.")
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuButton(model.filterLabel(), model.filterEntries(), Modifier.width(190.dp), icon = Icons.Rounded.Apps, accessibleLabel = "Filter rules by application")
                TextInput(model.query, { model.query = it }, "Search rules", Modifier.weight(1f), Icons.Rounded.Search)
                PrimaryButton("Add rule", icon = Icons.Rounded.Add, enabled = !locked) { model.startNew() }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (locked) NewLockNote("Rules are read-only while connected. Disconnect to add, edit, reorder or switch rules.", Modifier.weight(1f))
                else Note("Rules run from top to bottom." + if (model.filtering) " Clear the filter to reorder rules." else "", Modifier.weight(1f))
                LinkAction("Test a route", fontSize = 12.5.sp) { model.openTester() }
            }
            Spacer(Modifier.height(10.dp))
            NewTableHeader {
                HeaderText("#", Modifier.width(30.dp))
                HeaderText("Rule", Modifier.weight(1.5f))
                HeaderText("Applies to", Modifier.weight(1f))
                HeaderText("Action", Modifier.width(92.dp))
                Box(Modifier.width(126.dp))
            }
            val visible = model.visible()
            val listState = rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(model.query, model.appFilter) { listState.scrollToItem(0) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    model.rules.isEmpty() -> EmptyState(Icons.Rounded.Description, "No routing rules yet",
                        "Without rules, every connection of a routed app uses its server. Add a rule to keep something direct or to block it, or apply a preset in Profiles.",
                        Modifier.fillMaxSize()) { if (!locked) PrimaryButton("Add rule", icon = Icons.Rounded.Add) { model.startNew() } }
                    visible.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No rules match", "Nothing fits the current search and filter.", Modifier.fillMaxSize()) {
                        SecondaryButton("Clear search and filter") { model.query = ""; model.appFilter = null }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(visible, key = { it.value.id() }) { item -> NewRuleRow(shell, model, item.index, item.value) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (shell.plan.advancedEffective()) "Proxy uses each app's assigned server: its own server, Auto's choice, or the main server."
                    else "Proxy uses each app's assigned server. In standard mode, this is the main server.", color = Muted, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                Text("${plural(model.rules.size, "rule")}  ·  ${model.rules.count { it.enabled() }} enabled", color = Muted, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun NewRuleRow(shell: Shell, model: RulesModel, index: Int, rule: RouteRule) {
    val preset = model.isPreset(rule)
    val locked = shell.locked
    val name = PerRouteController.ruleDisplayName(rule)
    val editing = model.editor?.id == rule.id()
    NewTableRow(editing, Modifier.alpha(if (rule.enabled()) 1f else .62f),
        onClick = if (!locked && !preset) ({ model.edit(rule) }) else null, label = "Edit $name") {
        Text("${index + 1}", color = Muted, fontSize = 13.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1.5f)) {
            EllipsisText(name, White, 13.5.sp, fontWeight = FontWeight.SemiBold)
            EllipsisText(ruleMatchLine(rule), Muted, 11.5.sp)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (rule.applicationPath().isNotBlank()) { AppAvatar(fileName(rule.applicationPath()), rule.applicationPath(), 24.dp); Spacer(Modifier.width(8.dp)) }
            EllipsisText(ruleTarget(rule), White, 12.5.sp)
        }
        Row(Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(actionIcon(rule.action()), null, tint = actionColor(rule.action()), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(actionLabel(rule.action()), color = actionColor(rule.action()), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.width(126.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (preset) Tooltip("Preset rule. It comes from a preset in Profiles and cannot be edited here.") {
                Icon(Icons.Rounded.Lock, "Preset rule", tint = Muted, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            ToggleSwitch(rule.enabled(), "Rule $name", !locked, showState = false) { model.setEnabled(rule, it) }
            Spacer(Modifier.width(6.dp))
            MenuIcon(Icons.Rounded.MoreHoriz, "More actions for $name", model.menu(rule, index), size = 30.dp, tint = White.copy(.7f), bordered = false)
        }
    }
}

@Composable
private fun ColumnScope.NewRulePanel(shell: Shell, model: RulesModel) {
    val editor = model.editor
    val locked = shell.locked
    if (editor == null) {
        NewPanelHeading("Rule editor", "What a rule does")
        RouteRule.Action.values().forEach { action ->
            NewPanelSection(divider = action != RouteRule.Action.PROXY) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(actionIcon(action), null, tint = actionColor(action), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(actionLabel(action), color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(actionExplanation(action), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Only routed apps are affected. Applications you did not select never reach these rules, and private and LAN addresses always stay direct.",
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(18.dp))
        if (!locked) PrimaryButton("Add rule", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) { model.startNew() }
        else NewLockNote("Disconnect to add or edit rules.")
        return
    }
    NewPanelHeading("Rule editor", if (editor.isNew) "New rule" else "Edit rule")
    Spacer(Modifier.height(18.dp))
    RuleEditorFields(editor, model.appChoices(), locked)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Cancel", Modifier.weight(1f)) { model.cancelEdit() }
        PrimaryButton("Save rule", Modifier.weight(1.3f), enabled = !locked && editor.value.isNotBlank()) { model.save() }
    }
    if (locked) { Spacer(Modifier.height(12.dp)); NewLockNote("Disconnect to save this rule.") }
}

@Composable
internal fun NewProfilesPage(shell: Shell) {
    val model = profilesModel(shell)
    val locked = shell.locked
    NewSplitPage(panel = { NewPresetPanel(shell, model) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NewHeading(listOf("Tools", "Profiles"), "Profiles", "A familiar setup for whatever comes next.") {
                PrimaryButton("Save current setup", icon = Icons.Rounded.Save) { model.saveCurrent() }
            }
            if (locked) { Spacer(Modifier.height(14.dp)); NewLockNote("Disconnect to apply a profile or a preset. Saving the current setup is always possible.") }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Presets", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("They add automation and rules to your setup. Review changes before applying.", color = Muted, fontSize = 12.5.sp)
                }
                if (model.activeScenario.isNotBlank()) SecondaryButton("Remove preset rules", icon = Icons.Rounded.DeleteOutline, enabled = !locked) { model.clearPreset() }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PerRouteController.SCENARIOS.forEach { scenario ->
                    val active = model.activeScenario == scenario.id
                    val previewing = model.previewing?.scenario?.id == scenario.id
                    val shape = RoundedCornerShape(8.dp)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().background(Surface, shape)
                            .border(if (previewing) 2.dp else 1.dp, if (previewing) NewColors.Lime else Line, shape).padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(scenarioIcon(scenario.id), null, tint = White, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.weight(1f))
                            if (active) Badge("Active", Cyan, icon = Icons.Rounded.CheckCircle)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(scenario.title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(scenario.summary, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.height(12.dp))
                        LinkAction("Preview changes", fontSize = 12.5.sp) { model.preview(scenario) }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved profiles", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(plural(model.profiles.size, "profile"), color = Muted, fontSize = 12.5.sp)
            }
            Text("Complete setups of your own. Applying one replaces the current apps, routes, mode and rules.", color = Muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))
            if (model.profiles.isEmpty()) {
                EmptyState(Icons.Rounded.Folder, "No saved profiles yet",
                    "Set up your apps, servers and rules the way you like, then save them as a profile to switch back with one click.", Modifier.fillMaxWidth()) {
                    SecondaryButton("Save current setup", icon = Icons.Rounded.Save) { model.saveCurrent() }
                }
            } else {
                Divider()
                model.profiles.forEach { profile ->
                    val active = model.activeProfile == profile.id()
                    val (summary, missing) = profileSummary(shell, profile)
                    NewTableRow(false) {
                        Icon(Icons.Rounded.Folder, null, tint = White.copy(.8f), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            EllipsisText(profile.name(), White, 14.sp, fontWeight = FontWeight.SemiBold)
                            EllipsisText(summary, Muted, 11.5.sp)
                            if (missing > 0) Text("${plural(missing, "server")} of this profile ${if (missing == 1) "is" else "are"} no longer in your list",
                                color = Amber, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (active) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Active", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(10.dp))
                        }
                        SecondaryButton(if (active) "Apply again" else "Apply", Modifier.width(110.dp), height = 36.dp, enabled = !locked) { model.apply(profile) }
                        Spacer(Modifier.width(6.dp))
                        MenuIcon(Icons.Rounded.MoreHoriz, "More actions for ${profile.name()}", model.menu(profile), size = 30.dp, tint = White.copy(.7f), bordered = false)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Note("Profiles include apps, routes, rules and connection settings.")
            Spacer(Modifier.height(18.dp))
            RecipesSection(shell)
        }
    }
}

@Composable
private fun ColumnScope.NewPresetPanel(shell: Shell, model: ProfilesModel) {
    val preview = model.previewing
    if (preview == null) {
        NewPanelEmpty("Preset preview", "Choose a preset", "Select Preview changes on a preset to see exactly what it would change before you apply it. Nothing changes until you confirm.")
        return
    }
    NewPanelHeading("Preset preview", "${preview.scenario.title} preset", preview.scenario.summary)
    Spacer(Modifier.height(18.dp))
    Divider()
    Spacer(Modifier.height(16.dp))
    PresetChanges(shell, preview)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Cancel", Modifier.weight(1f)) { model.closePreview() }
        PrimaryButton(presetActionLabel(preview), Modifier.weight(1.3f), enabled = !shell.locked) { model.applyPreview() }
    }
    if (shell.locked) { Spacer(Modifier.height(12.dp)); Note("Disconnect to apply a preset.", icon = Icons.Rounded.WarningAmber, color = Amber) }
}
