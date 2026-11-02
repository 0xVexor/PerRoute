package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.model.ApplicationIdentity
import app.perroute.model.ApplicationSetupMigration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ApplicationGroupingControls(shell: Shell, model: AppsModel) {
    val error = if (model.source == AppListSource.INSTALLED) model.installedError else model.runningError
    if (error.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        InfoBanner(error, Modifier.fillMaxWidth(), Amber)
    }
    if (!shell.controller.applicationGroupingSupported()) return
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Review saved app selections", enabled = !shell.locked && !shell.busy && !shell.draft.dirty(shell)) { model.reviewGrouping() }
        Spacer(Modifier.weight(1f))
        Text("Show executable selections", color = Muted, fontSize = 11.sp)
        ToggleSwitch(model.showExecutables, "Show executable selections", showState = false) { model.showExecutables = it }
    }
    Text("Each app choice includes its verified helpers. Review older selections before combining them. Save or cancel unsaved edits before reviewing.",
        color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
}

internal fun showApplicationMigration(shell: Shell, model: AppsModel) {
    if (shell.locked || shell.draft.dirty(shell)) {
        shell.notify(ActionResult(false, "Disconnect and save or cancel app edits before reviewing saved selections.")); return
    }
    var preview: PerRouteController.ApplicationMigrationPreview? = null
    shell.run(done = { result ->
        if (result.ok) preview?.let { captured -> shell.showDialog { ApplicationMigrationDialog(shell, model, captured) } }
    }) {
        preview = shell.controller.previewApplicationMigration()
        ActionResult(true, if (preview!!.plan.entries().isEmpty()) "Saved application selections are already grouped." else "Review the saved application selections.")
    }
}

private fun variantSummary(variant: ApplicationSetupMigration.Variant): String {
    val settings = variant.settings()
    val route = when {
        settings.auto() -> "Auto (${settings.pool()})"
        settings.server().isNotBlank() -> "Server: ${settings.server()}"
        else -> "Main server"
    }
    val limit = settings.limit()
    val limits = if (limit.unlimited()) "Unlimited" else "Down ${limit.downloadBitsPerSecond()} / up ${limit.uploadBitsPerSecond()} bit/s"
    return "$route · $limits"
}

private fun variantTitle(variant: ApplicationSetupMigration.Variant): String =
    PerRouteController.displayName(variant.identity()) + if (ApplicationIdentity.isMacFamily(variant.identity())) " (app and helpers)" else " (exact executable)"

@Composable
internal fun ApplicationMigrationDialog(shell: Shell, model: AppsModel, preview: PerRouteController.ApplicationMigrationPreview) {
    var choices by remember(preview) { mutableStateOf(mapOf<ApplicationSetupMigration.Key, String>()) }
    val entries = preview.plan.entries()
    val sharedKeptWhileCombining = entries.any { entry -> entry.key().scope() == "shared" &&
        choices[entry.key()] == ApplicationSetupMigration.KEEP_EXACT && entries.any { other ->
            other.key().scope() != "shared" && other.key().target() == entry.key().target() &&
                choices[other.key()] != ApplicationSetupMigration.KEEP_EXACT
        }
    }
    val ready = !sharedKeptWhileCombining && entries.all { !it.requiresChoice() || choices.containsKey(it.key()) }
    DialogCard("Combine saved app selections", "Current settings and saved profiles are reviewed separately. No routing changes until you apply and reconnect.",
        shell::closeDialog, width = 700.dp, actions = {
            SecondaryButton("Cancel", enabled = !shell.busy) { shell.closeDialog() }
            PrimaryButton("Apply reviewed choices", enabled = ready && entries.isNotEmpty() && !shell.locked, busy = shell.busy) {
                shell.run(done = { result -> if (result.ok) {
                    shell.draft.load(shell); model.manual.clear(); model.refresh(true); shell.closeDialog()
                } }) { shell.controller.applyApplicationMigration(preview, choices) }
            }
        }) {
        if (entries.isEmpty()) Text("No saved selections need migration.", color = White, fontSize = 13.sp)
        if (sharedKeptWhileCombining) Text("Choose shared pools and limits for combined apps, or keep their exact selections in every scope.", color = Amber, fontSize = 12.sp)
        entries.forEach { entry ->
            Text("${entry.applicationName()} · ${entry.scopeName()}", color = White, fontSize = 14.sp)
            if (entry.expandsSelection()) Text("Combining includes the main app and its verified helpers. Choose settings to include them, or keep the exact selections.",
                color = Amber, fontSize = 12.sp, lineHeight = 16.sp)
            if (entry.scopedRules() > 0) Text("${entry.scopedRules()} app-specific rules will apply to the combined app. Rules keep their order and identifiers.",
                color = Amber, fontSize = 12.sp, lineHeight = 16.sp)
            val selected = choices[entry.key()]
            val selectedVariant = entry.variants().firstOrNull { it.identity() == selected }
            val label = when {
                selected == ApplicationSetupMigration.KEEP_EXACT -> "Keep exact selections"
                selectedVariant != null -> "Use ${variantTitle(selectedVariant)}: ${variantSummary(selectedVariant)}"
                entry.requiresChoice() -> "Choose settings or keep exact selections"
                else -> "Combine equivalent settings"
            }
            val menu = entry.variants().map { variant ->
                MenuEntry("Use ${variantTitle(variant)}", detail = variantSummary(variant) + "\n" + ApplicationIdentity.path(variant.identity()),
                    selected = selected == variant.identity()) { choices = choices + (entry.key() to variant.identity()) }
            } + MenuEntry("Keep exact selections", detail = "Preserve the existing routes and executable scope.", selected = selected == ApplicationSetupMigration.KEEP_EXACT) {
                choices = choices + (entry.key() to ApplicationSetupMigration.KEEP_EXACT)
            }
            MenuButton(label, menu, Modifier.fillMaxWidth(), enabled = !shell.busy)
            Spacer(Modifier.height(15.dp))
        }
    }
}
