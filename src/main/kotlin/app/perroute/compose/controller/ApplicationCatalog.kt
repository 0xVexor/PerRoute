package app.perroute.compose.controller

import app.perroute.backend.api.ApplicationDescriptor
import app.perroute.backend.api.ConnectionBackend
import app.perroute.model.ApplicationIdentity
import app.perroute.service.StoreAppPaths
import java.util.Locale

/**
 * The applications the user can route: what is installed and what is running now. It only asks the platform backend,
 * hides entries a person would never choose (system folders, helpers, updaters) and caches the answers briefly.
 */
class ApplicationCatalog(
    private val backend: ConnectionBackend,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val runningLock = Any()
    private val installedLock = Any()
    @Volatile private var running = Cached()
    @Volatile private var installed = Cached()

    private class Cached(val apps: List<ApplicationDescriptor> = emptyList(), val at: Long = 0L)

    fun running(forceRefresh: Boolean = false): List<ApplicationDescriptor> =
        cached(runningLock, { running }, { running = it }, RUNNING_TTL_MILLIS, forceRefresh) { backend.runningApplications() }

    fun installed(forceRefresh: Boolean = false): List<ApplicationDescriptor> =
        cached(installedLock, { installed }, { installed = it }, INSTALLED_TTL_MILLIS, forceRefresh) { backend.installedApplications() }

    /** Forget both lists so the next request asks the platform again. */
    fun invalidate() {
        running = Cached()
        installed = Cached()
    }

    private fun cached(
        lock: Any, read: () -> Cached, write: (Cached) -> Unit, ttl: Long, force: Boolean, load: () -> List<ApplicationDescriptor>
    ): List<ApplicationDescriptor> {
        fun fresh(entry: Cached) = !force && entry.apps.isNotEmpty() && clock() - entry.at < ttl
        read().takeIf(::fresh)?.let { return it.apps }
        return synchronized(lock) {
            read().takeIf(::fresh)?.apps ?: run {
                val at = clock()
                val apps = (if (backend.capabilities().applicationDiscovery()) load() else emptyList()).filter(::isUserFacing)
                if (!ApplicationIdentity.isMac(apps.firstOrNull()?.path())) StoreAppPaths.remember(apps.map { it.path() })
                write(Cached(apps, at))
                apps
            }
        }
    }

    companion object {
        private const val RUNNING_TTL_MILLIS = 3_000L
        private const val INSTALLED_TTL_MILLIS = 300_000L
        private val HIDDEN_NAMES = setOf("perroute.exe", "xray.exe", "proxifyre.exe", "java.exe", "javaw.exe")
        private val HIDDEN_FRAGMENTS = listOf("crashpad", "updater", "update.exe", "service.exe", "helper.exe", "notify.exe")

        fun isUserFacing(app: ApplicationDescriptor): Boolean = isUserFacing(app.name(), app.path())

        fun isUserFacing(rawName: String, rawPath: String): Boolean {
            val name = rawName.lowercase(Locale.ROOT)
            val path = rawPath.lowercase(Locale.ROOT)
            val windows = System.getenv("SystemRoot")?.lowercase(Locale.ROOT)
            if (windows != null && path.startsWith("$windows\\")) return false
            if ("\\common files\\" in path) return false
            if (name in HIDDEN_NAMES) return false
            return HIDDEN_FRAGMENTS.none { it in name }
        }
    }
}
