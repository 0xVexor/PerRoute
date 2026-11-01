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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.RouteRule

@Composable
internal fun SimpleRulesPage(shell: Shell) {
    val model = rulesModel(shell)
    val locked = shell.locked
    val editor = model.editor
    LaunchedEffect(editor) {
        if (editor != null) shell.showDialog(onDismiss = { model.cancelEdit(); shell.closeDialog() }) { SimpleRuleDialog(shell, model) }
    }
    SimplePage("Rules", "The first match decides where traffic goes.", scroll = false, actions = {
        PrimaryButton("Add rule", icon = Icons.Rounded.Add, enabled = !locked, height = 48.dp) { model.startNew() }
    }) {
        SimpleCard(Modifier.weight(1f), padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuButton(model.filterLabel(), model.filterEntries(), Modifier.width(if (LocalCompact.current) 170.dp else 240.dp), accessibleLabel = "Filter rules by application", height = 44.dp)
                TextInput(model.query, { model.query = it }, "Search rules…", Modifier.weight(1f), Icons.Rounded.Search, height = 44.dp)
                LinkAction("Test a route", fontSize = 14.sp) { model.openTester() }
            }
            Spacer(Modifier.height(14.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            if (locked) { SimpleLockNote("Rules are read-only while connected. Disconnect to add, edit, reorder or switch rules."); Spacer(Modifier.height(8.dp)) }
            Text("Rules run from top to bottom." + if (model.filtering) " Clear the filter to reorder rules." else "", color = White.copy(.8f), fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            SimpleTableHeader {
                HeaderText("#", Modifier.width(36.dp))
                HeaderText("Rule", Modifier.weight(1.5f))
                HeaderText("Applies to", Modifier.weight(1f))
                HeaderText("Action", Modifier.width(110.dp))
                Box(Modifier.width(190.dp))
            }
            val visible = model.visible()
            val listState = rememberLazyListState()
            LaunchedEffect(model.query, model.appFilter) { listState.scrollToItem(0) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    model.rules.isEmpty() -> EmptyState(Icons.Rounded.Description, "No routing rules yet",
                        "Without rules, every connection of a routed app uses its server. Add a rule to keep something direct or to block it, or apply a preset in Profiles.",
                        Modifier.fillMaxSize()) { if (!locked) PrimaryButton("Add rule", icon = Icons.Rounded.Add) { model.startNew() } }
                    visible.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "No rules match", "Nothing fits the current search and filter.", Modifier.fillMaxSize()) {
                        SecondaryButton("Clear search and filter") { model.query = ""; model.appFilter = null }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(visible, key = { it.value.id() }) { item -> SimpleRuleRow(shell, model, item.index, item.value) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use a rule's menu to edit or change its order.", color = White.copy(.8f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${plural(model.rules.size, "rule")}  ·  ${model.rules.count { it.enabled() }} enabled", color = White.copy(.8f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SimpleRuleRow(shell: Shell, model: RulesModel, index: Int, rule: RouteRule) {
    val preset = model.isPreset(rule)
    val locked = shell.locked
    val name = PerRouteController.ruleDisplayName(rule)
    SimpleTableRow(modifier = Modifier.alpha(if (rule.enabled()) 1f else .6f)) {
        Text("${index + 1}", color = White.copy(.85f), fontSize = 15.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1.5f)) {
            EllipsisText(name, White, 15.sp, fontWeight = FontWeight.SemiBold)
            EllipsisText(ruleMatchLine(rule), Muted, 13.sp)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (rule.applicationPath().isNotBlank()) { AppAvatar(fileName(rule.applicationPath()), rule.applicationPath(), 30.dp); Spacer(Modifier.width(10.dp)) }
            EllipsisText(ruleTarget(rule), White, 14.sp)
        }
        Row(Modifier.width(110.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(actionIcon(rule.action()), null, tint = if (rule.action() == RouteRule.Action.BLOCK) Red else Violet2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Text(actionLabel(rule.action()), color = White, fontSize = 14.sp)
        }
        Row(Modifier.width(190.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (preset) Tooltip("Preset rule. It comes from a preset in Profiles and cannot be edited here.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = Muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                    Text("Preset", color = Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            ToggleSwitch(rule.enabled(), "Rule $name", !locked, showState = !rule.enabled()) { model.setEnabled(rule, it) }
            Spacer(Modifier.width(8.dp))
            MenuIcon(Icons.Rounded.MoreHoriz, "More actions for $name", model.menu(rule, index), size = 34.dp, tint = White.copy(.8f), bordered = false)
        }
    }
}

@Composable
private fun SimpleRuleDialog(shell: Shell, model: RulesModel) {
    val editor = model.editor ?: return
    val close = { model.cancelEdit(); shell.closeDialog() }
    DialogCard(if (editor.isNew) "New rule" else "Edit rule", "Rules apply only to routed apps. Private and LAN addresses always stay direct.", close, width = 560.dp, actions = {
        SecondaryButton("Cancel", onClick = close)
        PrimaryButton("Save rule", enabled = !shell.locked && editor.value.isNotBlank()) { if (model.save()) shell.closeDialog() }
    }) {
        if (shell.locked) { SimpleLockNote("Disconnect to save this rule."); Spacer(Modifier.height(12.dp)) }
        RuleEditorFields(editor, model.appChoices(), shell.locked)
    }
}

@Composable
internal fun SimpleProfilesPage(shell: Shell) {
    val model = profilesModel(shell)
    val locked = shell.locked
    SimplePage("Profiles", "A familiar setup for whatever comes next.", actions = {
        PrimaryButton("Save current setup", icon = Icons.Rounded.Save, height = 50.dp) { model.saveCurrent() }
    }) {
        if (locked) { SimpleLockNote("Disconnect to apply a profile or a preset. Saving the current setup is always possible."); Spacer(Modifier.height(14.dp)) }
        SimpleSectionTitle("Presets", "Review changes before applying.") {
            if (model.activeScenario.isNotBlank()) Row(Modifier.pressable(RoundedCornerShape(8.dp), enabled = !locked, label = "Remove preset rules") { model.clearPreset() }
                .alpha(if (locked) .5f else 1f).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = White.copy(.75f), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text("Remove preset rules", color = White.copy(.85f), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PerRouteController.SCENARIOS.forEach { scenario ->
                val active = model.activeScenario == scenario.id
                val shape = RoundedCornerShape(14.dp)
                Column(Modifier.weight(1f).fillMaxHeight().background(Surface, shape).border(1.dp, if (active) Violet2.copy(.6f) else Line, shape).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(52.dp).background(Selection, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(scenarioIcon(scenario.id), null, tint = White.copy(.85f), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (active) Badge("Active", Cyan, icon = Icons.Rounded.CheckCircle)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(scenario.title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(scenario.summary, color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(14.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))
                    LinkAction("Preview changes", fontSize = 14.sp) { showPresetDialog(shell, model, scenario) }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        SimpleSectionTitle("Saved profiles") { Text(plural(model.profiles.size, "profile"), color = Muted, fontSize = 14.sp) }
        Text("Complete setups of your own. Applying one replaces the current apps, routes, mode and rules.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        SimpleCard(padding = 14.dp) {
            if (model.profiles.isEmpty()) EmptyState(Icons.Rounded.Folder, "No saved profiles yet",
                "Set up your apps, servers and rules the way you like, then save them as a profile to switch back with one click.", Modifier.fillMaxWidth()) {
                SecondaryButton("Save current setup", icon = Icons.Rounded.Save) { model.saveCurrent() }
            }
            model.profiles.forEachIndexed { index, profile ->
                val active = model.activeProfile == profile.id()
                val (summary, missing) = profileSummary(shell, profile)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).background(Selection, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Folder, null, tint = White.copy(.8f), modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        EllipsisText(profile.name(), White, 15.sp, fontWeight = FontWeight.SemiBold)
                        EllipsisText(summary, Muted, 13.5.sp)
                        if (missing > 0) Text("${plural(missing, "server")} of this profile ${if (missing == 1) "is" else "are"} no longer in your list",
                            color = Amber, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (active) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Cyan, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                        Text("Active", color = Cyan, fontSize = 15.sp); Spacer(Modifier.width(16.dp))
                    }
                    SecondaryButton(if (active) "Apply again" else "Apply", Modifier.width(130.dp), enabled = !locked, height = 44.dp) { model.apply(profile) }
                    Spacer(Modifier.width(10.dp))
                    MenuIcon(Icons.Rounded.MoreHoriz, "More actions for ${profile.name()}", model.menu(profile), size = 34.dp, tint = White.copy(.8f), bordered = false)
                }
                if (index < model.profiles.lastIndex) Divider(Modifier.padding(horizontal = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Profiles include apps, routes, rules and connection settings.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        SimpleCard { RecipesSection(shell, first = true) }
    }
}

