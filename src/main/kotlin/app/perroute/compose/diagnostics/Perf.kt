package app.perroute.compose.diagnostics

import app.perroute.compose.*
import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import app.perroute.service.AppDirectories
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal object Perf {
    val enabled = System.getenv("PERROUTE_PERF") == "1"
    val bench = enabled && System.getenv("PERROUTE_BENCH") == "1"
    val command = mutableStateOf("")

    private val origin = System.getenv("PERROUTE_T0")?.toLongOrNull()
        ?: ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(System.currentTimeMillis())
    private val file by lazy { AppDirectories.dataDirectory().resolve("perf.log") }

    fun mark(name: String) {
        if (!enabled) return
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        write("$name=${System.currentTimeMillis() - origin} heapMb=$heapMb threads=${Thread.activeCount()}")
    }

    fun value(name: String, value: Any) {
        if (enabled) write("$name=$value")
    }

    @Synchronized
    private fun write(line: String) {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    suspend fun timeToFrame(action: () -> Unit): Double {
        val started = System.nanoTime()
        action()
        withFrameNanos { }
        withFrameNanos { }
        return (System.nanoTime() - started) / 1_000_000.0
    }

    suspend fun frameGaps(name: String, millis: Long) {
        val gaps = ArrayList<Double>()
        val started = System.nanoTime()
        var previous = started
        while (System.nanoTime() - started < millis * 1_000_000) {
            withFrameNanos { }
            val now = System.nanoTime()
            gaps += (now - previous) / 1_000_000.0
            previous = now
        }
        gaps.sort()
        fun format(value: Double) = String.format(java.util.Locale.ROOT, "%.1f", value)
        value(name, "frames=${gaps.size} medianMs=${format(gaps[gaps.size / 2])} " +
            "p95Ms=${format(gaps[(gaps.size * 95 / 100).coerceAtMost(gaps.size - 1)])} maxMs=${format(gaps.last())} " +
            "over50ms=${gaps.count { it > 50 }}")
    }
}
