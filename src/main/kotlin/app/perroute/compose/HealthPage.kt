package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HealthTab(val label: String) { Doctor("Connection doctor"), Safety("Safety center") }

internal class HealthModel(private val shell: Shell) {
    private val controller = shell.controller
    var tab by mutableStateOf(HealthTab.Doctor)
    var app by mutableStateOf<String?>(null)
    var destination by mutableStateOf("")
    var doctor by mutableStateOf<HealthReport?>(null)
    var safety by mutableStateOf<HealthReport?>(null)
    var running by mutableStateOf(false)
    var selected by mutableStateOf<String?>(null)

    fun run() {
        if (running) return
        val which = tab
        val chosenApp = app
        val site = destination
        shell.scope.launch {
            running = true
            try {
                val report = withContext(Dispatchers.IO) { if (which == HealthTab.Doctor) controller.diagnose(chosenApp, site) else controller.safetyCheck() }
                if (which == HealthTab.Doctor) doctor = report else safety = report
                selected = report.cause?.id ?: report.findings.firstOrNull()?.id
                shell.routeChecks = controller.routeChecks()
            } finally { running = false }
        }
    }

    val report: HealthReport? get() = if (tab == HealthTab.Doctor) doctor else safety

    fun repair(repair: Repair) {
        when (repair) {
            Repair.OPEN_COMPONENTS -> { shell.openSettings(SettingsCategory.Components); return }
            Repair.OPEN_APPS -> { shell.navigate(Tab.Apps); app?.let { if (shell.tab == Tab.Apps) shell.draft.focused = it }; return }
            else -> {}
        }
        shell.showDialog {
            ConfirmDialog(repair.label + "?", repair.detail, repair.label, shell::closeDialog) {
                shell.closeDialog()
                shell.run(done = { run() }) { controller.repair(repair) }
            }
        }
    }
}

internal fun healthModel(shell: Shell): HealthModel = shell.screen("${Tab.Health.name}:model") { HealthModel(shell) }

@Composable
internal fun HealthPage(shell: Shell) {
    if (!shell.controller.capabilities().networkRecovery()) {
        UnavailableFeature("Connection doctor and safety checks", "Use connection status and route checks. These reports do not verify macOS protection yet.")
        return
    }
    val model = healthModel(shell)
    FeaturePage(listOf("Tools", "Health"), "Health", "Find out why something does not connect, and check that protection works.",
        actions = { PrimaryButton(if (model.running) "Checking…" else if (model.tab == HealthTab.Doctor) "Diagnose" else "Run safety checks",
            icon = Icons.Rounded.PlayArrow, busy = model.running, enabled = !shell.busy) { model.run() } },
        panel = if (Tok.isNew) ({ HealthPanel(model) }) else null) {
        UnderlineTabs(HealthTab.entries, model.tab, { it.label }, Modifier.fillMaxWidth()) { model.tab = it; model.selected = model.report?.cause?.id }
        Spacer(Modifier.height(16.dp))
        when (model.tab) {
            HealthTab.Doctor -> DoctorInputs(shell, model)
            HealthTab.Safety -> Text("Tests every route's exit address against your own, where name lookups go, the kill switch, the per-app engine and the rules in the running core. " +
                "What can only be predicted is said to be so.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        val report = model.report
        when {
            model.running && report == null -> LoadingState(if (model.tab == HealthTab.Doctor) "Looking for the cause…" else "Testing protection…", Modifier.fillMaxWidth())
            report == null -> EmptyState(if (model.tab == HealthTab.Doctor) Icons.Rounded.HealthAndSafety else Icons.Rounded.Shield,
                if (model.tab == HealthTab.Doctor) "Nothing checked yet" else "Not tested yet",
                if (model.tab == HealthTab.Doctor) "Choose an app if one does not connect, then Diagnose. Nothing is changed unless you choose a repair."
                else "Run the safety checks while connected to test the routes themselves.", Modifier.fillMaxWidth())
            else -> HealthFindings(model, report)
        }
    }
}

@Composable
private fun DoctorInputs(shell: Shell, model: HealthModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            FieldLabel("What does not work?")
            MenuButton(model.app?.let { appName(it) } ?: "My whole connection",
                listOf(MenuEntry("My whole connection", selected = model.app == null) { model.app = null }) +
                    shell.apps.map { path -> MenuEntry(appName(path), selected = model.app.equals(path, true)) { model.app = path } },
                Modifier.fillMaxWidth(), accessibleLabel = "App to diagnose", menuWidth = 300.dp)
        }
        Column(Modifier.weight(1f)) {
            FieldLabel("Website (optional)")
            TextInput(model.destination, { model.destination = it.take(200) }, "example.com", icon = Icons.Rounded.Language)
        }
    }
}

@Composable
private fun HealthFindings(model: HealthModel, report: HealthReport) {
    val cause = report.cause
    if (cause != null) {
        val color = statusColor(cause.status)
        InfoBanner((if (cause.status == CheckStatus.FAIL) "Most likely cause: " else "Worth a look: ") + cause.title, Modifier.fillMaxWidth(), color, statusIcon(cause.status),
            action = cause.repair?.let { repair -> { LinkAction(repair.label, arrow = false) { model.repair(repair) } } })
    } else InfoBanner(if (report.subject == "Routing safety") "Everything that could be tested passed." else "No problem was found.", Modifier.fillMaxWidth(), Cyan, statusIcon(CheckStatus.PASS))
    Spacer(Modifier.height(6.dp))
    Text("${plural(report.passed, "check")} passed  ·  ${plural(report.problems, "need", "need")} attention  ·  ${formatTimeOfDay(report.atMillis)}",
        color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    var area = ""
    report.findings.forEach { finding ->
        if (finding.area != area) {
            area = finding.area
            Spacer(Modifier.height(8.dp))
            SectionLabel(area)
            Spacer(Modifier.height(4.dp))
        }
        FeatureRow(model.selected == finding.id && Tok.isNew, onClick = { model.selected = finding.id }, label = finding.title) {
            StatusMark(finding.status, 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(finding.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (!Tok.isNew && finding.detail.isNotBlank()) Text(finding.detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (finding.repair != null && (!Tok.isNew || model.selected != finding.id)) {
                Spacer(Modifier.width(10.dp))
                LinkAction(finding.repair.label, arrow = false) { model.repair(finding.repair) }
            }
        }
    }
}

@Composable
private fun ColumnScope.HealthPanel(model: HealthModel) {
    val report = model.report
    val finding = report?.findings?.firstOrNull { it.id == model.selected } ?: report?.cause
    if (report == null || finding == null) {
        NewPanelEmpty(model.tab.label, if (model.tab == HealthTab.Doctor) "Diagnose a problem" else "Check protection",
            if (model.tab == HealthTab.Doctor) "The Doctor checks from the outside in: your own connection, DNS and clock, PerRoute's parts, the server, the tunnel, then the app and the site. It changes nothing by itself."
            else "Each route is tested for real where that is possible. Results that are only predictions say so.")
        return
    }
    PanelHeading(finding.area, finding.title, statusWord(finding.status), leading = { StatusMark(finding.status, 46.dp) })
    if (finding.detail.isNotBlank()) PanelSection { Text(finding.detail, color = White.copy(.88f), fontSize = 13.sp, lineHeight = 19.sp) }
    finding.repair?.let { repair ->
        PanelSection("Repair") {
            Text(repair.detail, color = White.copy(.8f), fontSize = 12.5.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(repair.label, Modifier.fillMaxWidth(), icon = Icons.Rounded.Build) { model.repair(repair) }
            Spacer(Modifier.height(6.dp))
            Note("You confirm before anything changes.")
        }
    }
    PanelSection("Report") {
        FactLine("Checked", report.subject)
        FactLine("Passed", report.passed.toString(), Cyan)
        FactLine("Need attention", report.problems.toString(), if (report.problems > 0) Amber else White)
        FactLine("At", formatTimeOfDay(report.atMillis))
    }
    Spacer(Modifier.padding(bottom = 8.dp))
}
