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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.recipe.Recipe
import app.perroute.recipe.RecipeCodec
import app.perroute.recipe.RecipePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path

@Composable
internal fun RecipesSection(shell: Shell, first: Boolean = false) {
    if (!shell.controller.recipeImportSupported()) {
        UnavailableFeature("Recipe sharing and import", "Application mappings are not portable to this platform yet.")
        return
    }
    FeatureBlock("Recipes", "Share how you route apps without sharing your servers. A recipe holds rules and app routes; servers become slots that the person importing it fills with their own.", first = first) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Share a recipe", icon = Icons.Rounded.IosShare, enabled = !shell.busy) { showRecipeExport(shell) }
            SecondaryButton("Import a recipe", icon = Icons.Rounded.FileOpen, enabled = !shell.busy) { showRecipeImport(shell) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (shell.controller.hasRecipeSnapshot()) LinkAction("Undo the last recipe", arrow = false, enabled = !shell.locked && !shell.busy) {
                shell.showDialog {
                    ConfirmDialog("Undo the last recipe?", "Your apps, routes, rules and settings go back to how they were before the recipe.", "Undo recipe", shell::closeDialog) {
                        shell.closeDialog(); shell.run(done = { shell.draft.load(shell) }) { shell.controller.undoRecipe() }
                    }
                }
            }
            if (shell.controller.rules().any { it.name().startsWith(RecipePlanner.RULE_PREFIX) }) LinkAction("Remove recipe rules", arrow = false, enabled = !shell.locked && !shell.busy) {
                shell.run { shell.controller.removeRecipeRules() }
            }
        }
        if (shell.locked) { Spacer(Modifier.height(8.dp)); Note("Disconnect to import a recipe. Sharing works at any time.") }
    }
}

internal fun showRecipeExport(shell: Shell) {
    val draft = shell.controller.recipeDraft()
    val name = mutableStateOf("My routing recipe")
    val description = mutableStateOf("")
    val includeApps = mutableStateOf(draft.apps.isNotEmpty())
    val includeSettings = mutableStateOf(false)
    val hints = mutableStateMapOf<String, String>()
    val skipped = mutableStateMapOf<String, Boolean>()
    fun recipe(): Recipe = shell.controller.buildRecipe(name.value, description.value, "", draft, includeApps.value, includeSettings.value,
        HashMap(hints), skipped.filterValues { it }.keys)
    shell.showDialog {
        val clipboard = LocalClipboardManager.current
        DialogCard("Share a recipe", "No server, address, subscription link, password or folder is included. Apps are named by their file name.", shell::closeDialog, width = 600.dp,
            actions = {
                SecondaryButton("Copy as text", icon = Icons.Rounded.ContentCopy) {
                    clipboard.setText(AnnotatedString(RecipeCodec.toText(recipe())))
                    shell.notify(ActionResult(true, "Recipe copied. Paste it in a message; it contains no servers or passwords."))
                }
                PrimaryButton("Save file", icon = Icons.Rounded.Save) {
                    val owner = shell.hostWindow
                    if (owner == null) { shell.notify(ActionResult(false, "Saving needs the main window.")); return@PrimaryButton }
                    val dialog = FileDialog(owner, "Save recipe", FileDialog.SAVE).apply {
                        file = name.value.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "recipe" } + RecipeCodec.FILE_EXTENSION
                        isVisible = true
                    }
                    val chosen = dialog.file ?: return@PrimaryButton
                    val target = Path.of(dialog.directory, if (chosen.endsWith(RecipeCodec.FILE_EXTENSION, true)) chosen else chosen + RecipeCodec.FILE_EXTENSION)
                    val built = recipe()
                    shell.closeDialog()
                    shell.run { shell.controller.saveRecipe(built, target) }
                }
            }) {
            FieldLabel("Name")
            TextInput(name.value, { name.value = it.take(60) }, "Developer routing")
            Spacer(Modifier.height(8.dp))
            FieldLabel("Description (optional)")
            TextInput(description.value, { description.value = it.take(400) }, "What it is good for")
            Spacer(Modifier.height(12.dp))
            CheckLine("Include app routes (${plural(draft.apps.size, "app")})", includeApps.value, draft.apps.isNotEmpty()) { includeApps.value = it }
            if (includeApps.value) Column(Modifier.padding(start = 30.dp)) {
                draft.apps.forEach { app ->
                    Text("${app.label()}: " + when (app.route()) {
                        Recipe.Route.MAIN -> "main server"
                        Recipe.Route.ROLE -> draft.roles.firstOrNull { it.id() == app.role() }?.let { role -> hints[role.id()]?.ifBlank { null } ?: role.label() } ?: "a server slot"
                        Recipe.Route.AUTO -> "Auto (" + app.pool() + ")"
                    }, color = Muted, fontSize = 11.5.sp)
                }
                draft.roles.forEach { role ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(role.label(), color = White.copy(.85f), fontSize = 12.sp, modifier = Modifier.width(80.dp))
                        TextInput(hints[role.id()] ?: "", { hints[role.id()] = it.take(40) }, "Describe this slot, e.g. Low latency", height = 34.dp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            CheckLine("Include automation settings (kill switch, reconnect, failover)", includeSettings.value) { includeSettings.value = it }
            Spacer(Modifier.height(10.dp))
            Text("Rules (${draft.rules.size})", color = White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            if (draft.rules.isEmpty()) Text("No rules.", color = Muted, fontSize = 11.5.sp)
            draft.rules.forEach { rule ->
                CheckLine("${PerRouteController.ruleDisplayName(rule)}: ${rule.action().name.lowercase()} ${rule.value().take(60)}", skipped[rule.id()] != true) { skipped[rule.id()] = !it }
            }
        }
    }
}

@Composable
private fun CheckLine(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckMark(checked)
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (enabled) White else Muted, fontSize = 12.sp, maxLines = 2)
    }
}

internal fun showRecipeImport(shell: Shell) {
    if (!shell.controller.recipeImportSupported()) {
        shell.notify(ActionResult(false, "Recipe import is not available on this platform yet."))
        return
    }
    val text = mutableStateOf("")
    shell.showDialog {
        DialogCard("Import a recipe", "Paste a recipe someone shared, or open a recipe file. You see every change before anything is applied.", shell::closeDialog, width = 560.dp,
            actions = {
                SecondaryButton("Open file…", icon = Icons.Rounded.FileOpen) {
                    val owner = shell.hostWindow ?: return@SecondaryButton
                    val dialog = FileDialog(owner, "Open recipe", FileDialog.LOAD).apply {
                        filenameFilter = java.io.FilenameFilter { _, file -> file.endsWith(RecipeCodec.FILE_EXTENSION, true) || file.endsWith(".json", true) }
                        isVisible = true
                    }
                    val chosen = dialog.file ?: return@SecondaryButton
                    val file = Path.of(dialog.directory, chosen)
                    runCatching { if (Files.size(file) > RecipeCodec.MAX_BYTES) error("This file is too large to be a recipe."); Files.readString(file) }
                        .onSuccess { content -> review(shell, content) }
                        .onFailure { shell.notify(ActionResult(false, it.message ?: "The file could not be read.")) }
                }
                PrimaryButton("Review", enabled = text.value.isNotBlank()) { review(shell, text.value) }
            }) {
            TextArea(text.value, { text.value = it.take(RecipeCodec.MAX_BYTES * 2) }, "perroute-recipe:…", Modifier.fillMaxWidth().height(140.dp))
        }
    }
}

private fun review(shell: Shell, content: String) {
    shell.scope.launch {
        val (plan, error) = withContext(Dispatchers.IO) { shell.controller.openRecipe(content) }
        if (plan == null) { shell.notify(ActionResult(false, error ?: "This is not a PerRoute recipe.")); return@launch }
        showRecipeReview(shell, plan)
    }
}

private fun showRecipeReview(shell: Shell, plan: RecipePlanner.Plan) {
    val recipe = plan.recipe()
    val roles = mutableStateMapOf<String, String>().apply { recipe.roles().forEach { role -> shell.selectedNodeKey.takeIf { it.isNotBlank() }?.let { put(role.id(), it) } } }
    val paths = mutableStateMapOf<String, String>().apply { plan.apps().forEach { app -> app.path()?.let { put(app.app().executable(), it) } } }
    val replace = mutableStateMapOf<String, Boolean>()
    val settings = mutableStateOf(plan.settings().isNotEmpty())
    shell.showDialog {
        DialogCard(recipe.name(), listOfNotNull(recipe.description().ifBlank { null }, recipe.author().ifBlank { null }?.let { "By $it" }, recipe.createdWith().ifBlank { null }).joinToString("  ·  "),
            shell::closeDialog, width = 640.dp, actions = {
                SecondaryButton("Cancel") { shell.closeDialog() }
                val missingRole = plan.apps().any { it.app().route() == Recipe.Route.ROLE && paths.containsKey(it.app().executable()) && roles[it.app().role()] == null }
                PrimaryButton("Apply recipe", enabled = !shell.locked && !shell.busy && !missingRole) {
                    shell.closeDialog()
                    val roleMap = HashMap(roles)
                    val pathMap = HashMap(paths)
                    val replaceSet = replace.filterValues { it }.keys.toSet()
                    val applySettings = settings.value
                    shell.run(done = { shell.draft.load(shell) }) { shell.controller.applyRecipe(plan, roleMap, pathMap, replaceSet, applySettings) }
                }
            }) {
            if (shell.locked) { InfoBanner("Disconnect to apply a recipe.", Modifier.fillMaxWidth(), Amber); Spacer(Modifier.height(10.dp)) }
            Text("Rules: ${plan.rulesToAdd()} to add", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("They are added after your own rules, so your rules keep deciding first.", color = Muted, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            plan.rules().forEach { rule ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EllipsisText("${rule.rule().name()}: ${rule.rule().action().name.lowercase()} ${rule.rule().value()}", White, 12.sp)
                        if (rule.rule().executable().isNotBlank()) Text("Only for ${rule.rule().executable()}", color = Muted, fontSize = 11.sp)
                        if (rule.status() == RecipePlanner.RuleStatus.CONFLICT) Text("Your rule \"${rule.conflictsWith()}\" decides these first.", color = Amber, fontSize = 11.sp)
                    }
                    Badge(rule.status().label(), when (rule.status()) { RecipePlanner.RuleStatus.ADD -> Cyan; RecipePlanner.RuleStatus.DUPLICATE -> Muted; else -> Amber })
                }
            }
            if (plan.apps().isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Apps (${plan.apps().size})", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                plan.apps().forEach { app ->
                    val path = paths[app.app().executable()]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${app.app().label().ifBlank { app.app().executable() }} → " + when (app.app().route()) {
                                Recipe.Route.MAIN -> "main server"
                                Recipe.Route.ROLE -> recipe.roles().firstOrNull { it.id() == app.app().role() }?.label() ?: "a server slot"
                                Recipe.Route.AUTO -> "Auto"
                            }, color = White, fontSize = 12.sp)
                            Text(if (path == null) "Not found on this computer" else path, color = if (path == null) Amber else Muted, fontSize = 11.sp, maxLines = 1)
                            if (app.configured() && path != null) Text("Now ${app.currentMode()}; " + if (replace[app.app().executable()] == true) "the recipe's route is used." else "your route is kept.", color = Amber, fontSize = 11.sp)
                        }
                        if (app.configured() && path != null) LinkAction(if (replace[app.app().executable()] == true) "Keep mine" else "Use recipe's", arrow = false) {
                            replace[app.app().executable()] = replace[app.app().executable()] != true
                        }
                        Spacer(Modifier.width(8.dp))
                        LinkAction(if (path == null) "Find…" else "Skip", arrow = false) {
                            if (path != null) paths.remove(app.app().executable())
                            else {
                                val owner = shell.hostWindow ?: return@LinkAction
                                val dialog = FileDialog(owner, "Where is ${app.app().executable()}?", FileDialog.LOAD).apply {
                                    filenameFilter = java.io.FilenameFilter { _, name -> name.equals(app.app().executable(), true) }
                                    file = app.app().executable()
                                    isVisible = true
                                }
                                dialog.file?.let { paths[app.app().executable()] = Path.of(dialog.directory, it).toAbsolutePath().normalize().toString() }
                            }
                        }
                    }
                }
            }
            if (recipe.roles().isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Server slots", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Point each slot at one of your servers.", color = Muted, fontSize = 11.5.sp)
                recipe.roles().forEach { role ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(160.dp)) {
                            Text(role.label(), color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            if (role.hint().isNotBlank()) Text(role.hint(), color = Muted, fontSize = 11.sp)
                        }
                        ServerChooser(shell, roles[role.id()], "Server for ${role.label()}", Modifier.weight(1f)) { roles[role.id()] = it.key() }
                    }
                }
            }
            if (plan.settings().isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CheckLine("Apply its settings: " + plan.settings().joinToString(", ") { "${it.label()} ${if (it.to()) "on" else "off"}" }, settings.value) { settings.value = it }
            }
            Spacer(Modifier.height(10.dp))
            DialogText("A copy of your current setup is kept, so Undo the last recipe puts everything back.", Muted)
        }
    }
}
