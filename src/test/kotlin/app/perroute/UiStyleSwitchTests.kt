package app.perroute

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.perroute.compose.Appearance
import app.perroute.compose.controller.PerRouteController
import app.perroute.compose.PerRouteWindow
import app.perroute.compose.SettingsCategory
import app.perroute.compose.Shell
import app.perroute.compose.Tab
import app.perroute.compose.UiStyle
import app.perroute.compose.backupModel
import app.perroute.compose.rulesModel
import app.perroute.model.AppState
import app.perroute.model.ConnectionMode
import app.perroute.model.Protocol
import app.perroute.model.ProxyNode
import app.perroute.persistence.AppStateStore
import kotlinx.coroutines.CoroutineDispatcher
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

object UiStyleSwitchTests {
    private var assertions = 0
    @JvmStatic fun assertions() = assertions

    private class Pump : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }

    @JvmStatic fun runAll() {
        println("[test] UI: switching the design keeps the connection, routes and drafts")
        test()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun test() {
        val directory = Files.createTempDirectory("perroute-ui-switch-")
        System.setProperty("perroute.data", directory.resolve("data").toString())
        val apps = listOf("Browser.exe", "Chat.exe", "Editor.exe").map { name ->
            directory.resolve(name).also { Files.writeString(it, "test") }.toAbsolutePath().normalize().toString()
        }
        val state = AppState().apply {
            nodes().addAll(listOf(ProxyNode("main", "Main", Protocol.VLESS, "main.example", 443, "id-main", mapOf("security" to "tls"), ""),
                ProxyNode("other", "Other", Protocol.VLESS, "other.example", 443, "id-other", mapOf("security" to "tls"), "")))
            setSelectedNodeKey("main")
            setConnectionMode(ConnectionMode.NATIVE_PER_APP)
            appPaths().addAll(apps.take(2))
        }
        val backend = ControllerTests.FakeBackend()
        val preferences = ControllerTests.MemoryPreferences().apply { putBoolean("autoFailover", false); putBoolean("autoReconnect", false) }
        val controller = PerRouteController(state, AppStateStore(directory.resolve("data").resolve("state.bin")), backend, preferences)
        try {
            check(controller.connect().ok, "Connect")
            val starts = backend.startCount
            val stops = backend.stopCount
            val config = backend.lastConfig
            val routes = controller.activeRoutes().map { it.id to it.node.key() }
            val applications = controller.applications()
            controller.setUiStyle(UiStyle.NEW.key)
            Appearance.load(controller)
            val savedPreferences = preferences.keys().associateWith { preferences.get(it, null) }

            val shells = mutableListOf<Shell>()
            val pump = Pump()
            val scene = ImageComposeScene(1180, 790, Density(1f), pump) {
                PerRouteWindow(controller, null, exitApp = {}, onShell = { shells += it }, initialTab = Tab.Apps)
            }
            fun settle() { repeat(25) { pump.drain(); scene.render(System.nanoTime()); Thread.sleep(20) } }
            try {
                settle()
                val shell = shells.single()
                equal(true, shell.connected)

                shell.draft.selected += apps[2]
                shell.draft.focused = apps[0]
                check(shell.draft.dirty(shell), "The Apps draft has unsaved changes")
                for (style in listOf(UiStyle.SIMPLE, UiStyle.NEW, UiStyle.SIMPLE)) {
                    shell.setUiStyle(style); settle()
                    equal(style, Appearance.style)
                }
                check(shell.draft.dirty(shell), "The Apps draft survives switching")
                check(shell.draft.isSelected(apps[2]), "The added app is still in the draft")
                equal(applications, controller.applications())

                shell.closeDialog(); shell.draft.focused = null; shell.discardDraft()
                shell.navigate(Tab.Settings); shell.selectSettings(SettingsCategory.Backup); settle()
                val backup = backupModel(shell)
                backup.password = "draft password"
                backup.encrypt = true
                shell.setUiStyle(UiStyle.NEW); settle()
                shell.setUiStyle(UiStyle.SIMPLE); settle()
                check(backupModel(shell) === backup, "The backup form is the same one after switching")
                equal("draft password", backupModel(shell).password)

                shell.navigate(Tab.Rules); settle()
                val rules = rulesModel(shell)
                rules.startNew()
                equal(false, rules.editor != null)
                shell.setUiStyle(UiStyle.NEW); settle()

                equal(1, shells.size)
                check(controller.isConnected(), "Still connected")
                equal(starts, backend.startCount)
                equal(stops, backend.stopCount)
                equal(config, backend.lastConfig)
                equal(routes, controller.activeRoutes().map { it.id to it.node.key() })
                val now = preferences.keys().associateWith { preferences.get(it, null) }
                equal(savedPreferences - PerRouteController.PREF_UI_STYLE, now - PerRouteController.PREF_UI_STYLE)
                equal(UiStyle.NEW.key, controller.uiStyle())
            } finally {
                scene.close()
            }
        } finally {
            runCatching { controller.disconnect() }
            controller.close()
            System.clearProperty("perroute.data")
            Appearance.style = UiStyle.NEW
        }
    }

    private fun equal(expected: Any?, actual: Any?) {
        assertions++
        if (expected != actual) throw AssertionError("Expected <$expected> but got <$actual>")
    }

    private fun check(condition: Boolean, message: String) {
        assertions++
        if (!condition) throw AssertionError(message)
    }
}
