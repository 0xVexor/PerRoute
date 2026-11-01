package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.RouteRule

internal fun actionLabel(action: RouteRule.Action): String = when (action) {
    RouteRule.Action.PROXY -> "Proxy"
    RouteRule.Action.DIRECT -> "Direct"
    RouteRule.Action.BLOCK -> "Block"
}

internal fun actionIcon(action: RouteRule.Action): ImageVector = when (action) {
    RouteRule.Action.PROXY -> Icons.AutoMirrored.Rounded.CallSplit
    RouteRule.Action.DIRECT -> Icons.AutoMirrored.Rounded.ArrowForward
    RouteRule.Action.BLOCK -> Icons.Rounded.Block
}

@Composable
internal fun actionColor(action: RouteRule.Action): Color = when (action) {
    RouteRule.Action.PROXY -> if (Tok.isNew) White else Violet2
    RouteRule.Action.DIRECT -> White.copy(.85f)
    RouteRule.Action.BLOCK -> Red
}

internal fun matchLabel(type: RouteRule.MatchType): String = when (type) {
    RouteRule.MatchType.DOMAIN -> "Domain"
    RouteRule.MatchType.IP -> "IP"
    RouteRule.MatchType.PORT -> "Port"
}

internal fun actionExplanation(action: RouteRule.Action): String = when (action) {
    RouteRule.Action.PROXY -> "Matching connections use the app's assigned server."
    RouteRule.Action.DIRECT -> "Matching connections use your regular connection."
    RouteRule.Action.BLOCK -> "Matching connections of the routed app are dropped."
}

internal class RuleEditor(rule: RouteRule?) {
    val id = rule?.id().orEmpty()
    var name by mutableStateOf(rule?.name().orEmpty())
    var app by mutableStateOf(rule?.applicationPath().orEmpty())
    var type by mutableStateOf(rule?.matchType() ?: RouteRule.MatchType.DOMAIN)
    var value by mutableStateOf(rule?.value().orEmpty())
    var action by mutableStateOf(rule?.action() ?: RouteRule.Action.DIRECT)
    val isNew: Boolean get() = id.isBlank()
}

internal class RulesModel(private val shell: Shell) {
    private val controller = shell.controller
    val rules = mutableStateListOf<RouteRule>().also { it.addAll(controller.rules()) }
    var query by mutableStateOf("")
    var appFilter by mutableStateOf<String?>(null)
    var editor by mutableStateOf<RuleEditor?>(null)

    fun refresh() { rules.clear(); rules.addAll(controller.rules()) }

    private fun apply(result: ActionResult, quiet: Boolean = true) {
        if (!result.ok || !quiet) shell.notify(result)
        refresh()
    }

    val filtering: Boolean get() = query.isNotBlank() || appFilter != null

    fun visible(): List<IndexedValue<RouteRule>> = rules.withIndex().filter { (_, rule) ->
        (appFilter == null || (appFilter == "" && rule.applicationPath().isBlank()) || rule.applicationPath().equals(appFilter, true)) &&
            (query.isBlank() || rule.name().contains(query, true) || rule.value().contains(query, true))
    }

    fun appChoices(): List<String> = (shell.apps + rules.map { it.applicationPath() }.filter { it.isNotBlank() }).distinctBy { it.lowercase() }

    fun filterLabel(): String = when (val filter = appFilter) { null -> "All applications"; "" -> "All routed apps"; else -> appName(filter) }

    fun filterEntries(): List<MenuEntry> = listOf(
        MenuEntry("All applications", selected = appFilter == null) { appFilter = null },
        MenuEntry("All routed apps", detail = "Rules that are not tied to one app", selected = appFilter == "") { appFilter = "" }
    ) + appChoices().map { path -> MenuEntry(appName(path), selected = appFilter.equals(path, true)) { appFilter = path } }

    fun isPreset(rule: RouteRule) = PerRouteController.isScenarioRule(rule)

    fun canMoveUp(index: Int): Boolean {
        val rule = rules.getOrNull(index) ?: return false
        return !shell.locked && !filtering && !isPreset(rule) && index > 0 && !PerRouteController.isScenarioRule(rules[index - 1])
    }

    fun canMoveDown(index: Int): Boolean {
        val rule = rules.getOrNull(index) ?: return false
        return !shell.locked && !filtering && !isPreset(rule) && index < rules.lastIndex
    }

    fun move(rule: RouteRule, offset: Int) = apply(controller.moveRule(rule.id(), offset))
    fun setEnabled(rule: RouteRule, enabled: Boolean) = apply(controller.setRuleEnabled(rule.id(), enabled))
    fun startNew() { if (!shell.locked) editor = RuleEditor(null) }
    fun edit(rule: RouteRule) { if (!shell.locked && !isPreset(rule)) editor = RuleEditor(rule) }
    fun cancelEdit() { editor = null }

    fun delete(rule: RouteRule) {
        if (editor?.id == rule.id()) editor = null
        apply(controller.removeRule(rule.id()), quiet = false)
    }

    fun save(): Boolean {
        val current = editor ?: return false
        val result = if (current.isNew) controller.addRule(current.name, current.app, current.type, current.value, current.action)
            else controller.updateRule(current.id, current.name, current.app, current.type, current.value, current.action)
        apply(result, quiet = false)
        if (result.ok) editor = null
        return result.ok
    }

    fun menu(rule: RouteRule, index: Int): List<MenuEntry> {
        val name = PerRouteController.ruleDisplayName(rule)
        val preset = isPreset(rule)
        return listOf(
            MenuEntry("Edit rule", Icons.Rounded.Edit, if (preset) "Preset rules cannot be edited" else "", enabled = !shell.locked && !preset) { edit(rule) },
            MenuEntry("Move up", Icons.Rounded.KeyboardArrowUp, enabled = canMoveUp(index)) { move(rule, -1) },
            MenuEntry("Move down", Icons.Rounded.KeyboardArrowDown, enabled = canMoveDown(index)) { move(rule, 1) },
            MenuEntry("Delete rule", Icons.Rounded.DeleteOutline, "Removes \"$name\"", enabled = !shell.locked, danger = true) { delete(rule) }
        )
    }

    fun openTester() { shell.diagnosticsTab = DiagnosticsTab.Tester; shell.navigate(Tab.Diagnostics) }
}

internal fun rulesModel(shell: Shell): RulesModel = shell.screen("${Tab.Rules.name}:rules") { RulesModel(shell) }

internal fun ruleTarget(rule: RouteRule): String = if (rule.applicationPath().isBlank()) "All routed apps" else appName(rule.applicationPath())

internal fun ruleMatchLine(rule: RouteRule): String = "${matchLabel(rule.matchType())}  ·  ${rule.value()}"

@Composable
internal fun RuleEditorFields(editor: RuleEditor, apps: List<String>, locked: Boolean) {
    Column {
        FieldLabel("Name")
        TextInput(editor.name, { editor.name = it.take(60) }, "For example: Local banking", enabled = !locked)
        Spacer(Modifier.height(12.dp))
        FieldLabel("Application")
        MenuButton(
            if (editor.app.isBlank()) "All routed apps" else appName(editor.app),
            listOf(MenuEntry("All routed apps", selected = editor.app.isBlank()) { editor.app = "" }) +
                apps.map { path -> MenuEntry(appName(path), selected = editor.app.equals(path, true)) { editor.app = path } },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.Apps, accessibleLabel = "Application this rule applies to", enabled = !locked
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel("Match type")
        Segmented(RouteRule.MatchType.values().toList(), editor.type, ::matchLabel, Modifier.fillMaxWidth(), enabled = !locked) { editor.type = it }
        Spacer(Modifier.height(12.dp))
        FieldLabel("Values")
        TextArea(editor.value, { editor.value = it }, when (editor.type) {
            RouteRule.MatchType.DOMAIN -> "snapp.ir, digikala.com"
            RouteRule.MatchType.IP -> "203.0.113.0/24, geoip:ir"
            RouteRule.MatchType.PORT -> "80, 443, 1000-2000"
        }, Modifier.fillMaxWidth().height(64.dp), enabled = !locked)
        Spacer(Modifier.height(5.dp))
        Text(when (editor.type) {
            RouteRule.MatchType.DOMAIN -> "Type the site once, for example snapp.ir. It covers www, every subdomain and every page; " +
                "https://, paths and ports are removed for you. Use full: for one exact host, or keyword:, geosite:. Separate sites with commas."
            RouteRule.MatchType.IP -> "An address, a CIDR range or geoip:code. Comma-separated."
            RouteRule.MatchType.PORT -> "Single ports and ranges. Comma-separated."
        }, color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
        if (editor.type == RouteRule.MatchType.DOMAIN && editor.value.isNotBlank()) {
            val sites = app.perroute.model.DomainEntries.entries(app.perroute.model.DomainEntries.normalizeList(editor.value))
            Spacer(Modifier.height(4.dp))
            Text(if (sites.isEmpty()) "No site recognized yet."
                else "Will match: " + sites.joinToString(", ") { if (it.contains(':')) it else "$it and all its subdomains" },
                color = if (sites.isEmpty()) Amber else Cyan, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        FieldLabel("Action")
        Segmented(RouteRule.Action.values().toList(), editor.action, ::actionLabel, Modifier.fillMaxWidth(), icon = ::actionIcon, enabled = !locked) { editor.action = it }
        Spacer(Modifier.height(6.dp))
        Text(actionExplanation(editor.action), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}
