package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as DrawPath
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.BandwidthPolicy
import app.perroute.service.TrafficHistoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal enum class TrafficView(val label: String) { Today("Today"), Month("This month"), Daily("Daily totals") }
internal const val ChartWindowMillis = 90_000L

internal class TrafficModel(private val shell: Shell) {
    private val controller = shell.controller
    var view by mutableStateOf(TrafficView.Today)
    var appFilter by mutableStateOf("")
    var report by mutableStateOf(controller.trafficReport(""))
    var points by mutableStateOf(controller.trafficPoints())
    var selectedApp by mutableStateOf<String?>(null)

    fun reload() { report = controller.trafficReport(appFilter); points = controller.trafficPoints() }

    fun knownApps(): List<String> = (report.monthApplications().map { it.applicationPath() } + shell.apps).distinctBy { it.lowercase() }

    fun appEntries(): List<MenuEntry> = listOf(MenuEntry("All apps", selected = appFilter.isBlank()) { appFilter = "" }) +
        knownApps().map { path -> MenuEntry(appName(path), selected = appFilter.equals(path, true)) { appFilter = path } }

    fun clearHistory() {
        shell.showDialog {
            ConfirmDialog("Clear traffic history?", "All daily, monthly and per-app totals stored on this device are deleted. This cannot be undone.",
                "Clear history", shell::closeDialog, danger = true) {
                shell.closeDialog(); controller.clearTrafficHistory(); reload()
            }
        }
    }

    fun moreMenu(): List<MenuEntry> = listOf(MenuEntry("Clear traffic history…", Icons.Rounded.DeleteSweep, danger = true) { clearHistory() })

    fun editLimits(path: String) {
        shell.showDialog {
            DialogCard("Bandwidth limits for ${appName(path)}", "The limit is the app's total across all of its connections, enforced by the traffic gateway.",
                shell::closeDialog, width = 460.dp, actions = { PrimaryButton("Done") { shell.closeDialog() } }) {
                BandwidthEditor(shell, path, showTitle = false)
            }
        }
    }

    fun setUnlimited(path: String) = shell.run { controller.setBandwidthPolicy(path, BandwidthPolicy.UNLIMITED) }

    fun rowMenu(path: String, policy: BandwidthPolicy, onEdit: () -> Unit): List<MenuEntry> = listOf(
        MenuEntry("Edit limits…", Icons.Rounded.Speed, onClick = onEdit),
        MenuEntry("Set unlimited", Icons.Rounded.AllInclusive, enabled = !policy.unlimited()) { setUnlimited(path) }
    )

    fun appRows(): List<AppBandwidthRow> = shell.apps.map { path ->
        val usage = shell.traffic.applications.firstOrNull { it.path.equals(path, true) }
        val status = controller.bandwidthStatus(path)
        val policy = status.policy
        AppBandwidthRow(path, usage, status, policy,
            policy.downloadBytesPerSecond().takeIf { it > 0 }?.let { (usage?.downloadBytesPerSecond ?: 0).toFloat() / it },
            policy.uploadBytesPerSecond().takeIf { it > 0 }?.let { (usage?.uploadBytesPerSecond ?: 0).toFloat() / it })
    }

    fun usageOf(path: String): Long {
        val entries = if (view == TrafficView.Month) report.monthApplications() else report.todayApplications()
        return entries.firstOrNull { it.applicationPath().equals(path, true) }?.let { it.downloadBytes() + it.uploadBytes() } ?: 0L
    }
}

internal class AppBandwidthRow(
    val path: String,
    val usage: AppTraffic?,
    val status: BandwidthStatus,
    val policy: BandwidthPolicy,
    val downShare: Float?,
    val upShare: Float?
) {
    val active: Boolean get() = usage?.active == true
    val utilization: Float? get() = listOfNotNull(downShare, upShare).maxOrNull()
}

internal fun trafficModel(shell: Shell): TrafficModel = shell.screen("${Tab.Traffic.name}:traffic") { TrafficModel(shell) }

@Composable
internal fun TrafficEffects(model: TrafficModel) {
    LaunchedEffect(model.appFilter) {
        while (isActive) { model.reload(); delay(1_000) }
    }
}

internal fun limitText(bitsPerSecond: Long): String = if (bitsPerSecond > 0) formatMbps(bitsPerSecond / 8) else "Unlimited"

internal fun shareText(share: Float?): String = share?.let { "${(it * 100).toInt().coerceAtMost(999)}%" } ?: "—"

@Composable
internal fun UsageList(title: String, entries: List<TrafficHistoryStore.Entry>, filter: String, empty: String, download: Color, upload: Color) {
    val shown = entries.filter { filter.isBlank() || it.applicationPath().equals(filter, true) }
    Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    if (shown.isEmpty()) { Text(empty, color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp); return }
    val maxDown = entries.maxOf { it.downloadBytes() }.coerceAtLeast(1L)
    val maxUp = entries.maxOf { it.uploadBytes() }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        HeaderText("App", Modifier.width(170.dp))
        HeaderText("Download", Modifier.weight(1f))
        HeaderText("Upload", Modifier.weight(1f))
    }
    Divider()
    shown.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(170.dp), verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(fileName(item.applicationPath()), item.applicationPath(), 24.dp)
                Spacer(Modifier.width(9.dp))
                EllipsisText(appName(item.applicationPath()), White, 12.sp, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Download ${formatBytes(item.downloadBytes())}" },
                verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(item.downloadBytes().toFloat() / maxDown, download, Modifier.weight(1f))
                Text(formatBytes(item.downloadBytes()), color = White.copy(.9f), fontSize = 11.sp, modifier = Modifier.width(80.dp).padding(start = 9.dp), maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Upload ${formatBytes(item.uploadBytes())}" },
                verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(item.uploadBytes().toFloat() / maxUp, upload, Modifier.weight(1f))
                Text(formatBytes(item.uploadBytes()), color = White.copy(.9f), fontSize = 11.sp, modifier = Modifier.width(80.dp).padding(start = 9.dp), maxLines = 1)
            }
        }
        Divider()
    }
}

@Composable
internal fun DailyList(days: List<TrafficHistoryStore.Day>, filter: String, download: Color, upload: Color) {
    Text("Daily totals this month" + if (filter.isBlank()) "" else " for ${appName(filter)}", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    if (days.isEmpty()) { Text("No day of this month has recorded traffic yet.", color = Muted, fontSize = 11.5.sp); return }
    val maxDown = days.maxOf { it.downloadBytes() }.coerceAtLeast(1L)
    val maxUp = days.maxOf { it.uploadBytes() }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        HeaderText("Day", Modifier.width(130.dp))
        HeaderText("Download", Modifier.weight(1f))
        HeaderText("Upload", Modifier.weight(1f))
    }
    Divider()
    days.forEach { day ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(day.date(), color = White, fontSize = 12.sp, modifier = Modifier.width(130.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(day.downloadBytes().toFloat() / maxDown, download, Modifier.weight(1f))
                Text(formatBytes(day.downloadBytes()), color = White.copy(.9f), fontSize = 11.sp, modifier = Modifier.width(80.dp).padding(start = 9.dp), maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(day.uploadBytes().toFloat() / maxUp, upload, Modifier.weight(1f))
                Text(formatBytes(day.uploadBytes()), color = White.copy(.9f), fontSize = 11.sp, modifier = Modifier.width(80.dp).padding(start = 9.dp), maxLines = 1)
            }
        }
        Divider()
    }
}

@Composable
internal fun UsageSection(model: TrafficModel, download: Color, upload: Color) {
    val report = model.report
    when (model.view) {
        TrafficView.Today -> UsageList("Today by application", report.todayApplications(), model.appFilter,
            "Nothing recorded today. Totals appear while selected apps send traffic in Smart per-app mode.", download, upload)
        TrafficView.Month -> UsageList("This month by application", report.monthApplications(), model.appFilter,
            "Nothing recorded this month yet. Totals appear while selected apps send traffic in Smart per-app mode.", download, upload)
        TrafficView.Daily -> DailyList(report.days(), model.appFilter, download, upload)
    }
}

private fun niceMbps(value: Double): Double {
    for (step in doubleArrayOf(1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 20.0, 30.0, 50.0, 75.0, 100.0, 150.0, 200.0, 300.0, 500.0, 1000.0)) if (step >= value) return step
    return Math.ceil(value / 1000.0) * 1000.0
}

private fun mbps(bytesPerSecond: Long): Double = bytesPerSecond.coerceAtLeast(0) * 8 / 1_000_000.0

private fun axisLabel(value: Double): String = if (value == Math.floor(value)) value.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", value)

@Composable
internal fun LiveSpeedChart(points: List<TrafficPoint>, connected: Boolean, download: Color, upload: Color, legendBelow: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (isActive) { now = System.currentTimeMillis(); delay(1_000) } }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var chartWidth by remember { mutableStateOf(0) }
    val visible = points.filter { now - it.atMillis in 0..ChartWindowMillis }
    val scaleMax = niceMbps(visible.maxOfOrNull { maxOf(mbps(it.downloadBytesPerSecond), mbps(it.uploadBytesPerSecond)) }?.coerceAtLeast(1.0) ?: 30.0)
    fun fraction(point: TrafficPoint) = 1f - (now - point.atMillis).toFloat() / ChartWindowMillis
    val hovered = hoverX?.takeIf { chartWidth > 0 }?.let { x ->
        visible.minByOrNull { kotlin.math.abs(fraction(it) * chartWidth - x) }
            ?.takeIf { kotlin.math.abs(fraction(it) * chartWidth - x) <= chartWidth / 30f }
    }
    val latest = visible.lastOrNull()
    val grid = Line
    val muted = Muted
    val surface = SurfaceHigh
    val newStyle = Tok.isNew
    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(34.dp).fillMaxHeight().padding(bottom = 0.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(scaleMax, scaleMax * 2 / 3, scaleMax / 3, 0.0).forEach { value ->
                    Text(axisLabel(value), color = muted, fontSize = 10.5.sp, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                Canvas(
                    Modifier.fillMaxSize().padding(end = 70.dp, top = 7.dp, bottom = 7.dp)
                        .semantics { contentDescription = "Live download and upload speed over the last 90 seconds" }
                        .onSizeChanged { chartWidth = it.width }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    hoverX = if (event.type == PointerEventType.Exit) null else event.changes.firstOrNull()?.position?.x
                                }
                            }
                        }
                ) {
                    val plotHeight = size.height
                    fun x(point: TrafficPoint) = size.width * fraction(point)
                    fun y(value: Double) = plotHeight * (1f - (value / scaleMax).toFloat().coerceIn(0f, 1f))
                    for (line in 0..3) {
                        val gridY = plotHeight * line / 3f
                        drawLine(grid, Offset(0f, gridY), Offset(size.width, gridY), 1.dp.toPx())
                    }
                    for (column in 0..6) {
                        val gridX = size.width * column / 6f
                        drawLine(grid, Offset(gridX, 0f), Offset(gridX, plotHeight), 1.dp.toPx(),
                            pathEffect = if (newStyle) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    if (visible.isEmpty()) return@Canvas
                    val runs = ArrayList<MutableList<TrafficPoint>>()
                    visible.forEach { point ->
                        val last = runs.lastOrNull()?.lastOrNull()
                        if (last == null || point.atMillis - last.atMillis > 3_500) runs += mutableListOf(point) else runs.last() += point
                    }
                    fun series(color: Color, value: (TrafficPoint) -> Double) {
                        runs.forEach { run ->
                            if (run.size == 1) { drawCircle(color, 2.dp.toPx(), Offset(x(run[0]), y(value(run[0])))); return@forEach }
                            val line = DrawPath().apply {
                                moveTo(x(run[0]), y(value(run[0])))
                                for (index in 1 until run.size) lineTo(x(run[index]), y(value(run[index])))
                            }
                            drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        visible.last().let { point -> drawCircle(color, 4.5.dp.toPx(), Offset(x(point), y(value(point)))) }
                    }
                    series(download) { mbps(it.downloadBytesPerSecond) }
                    series(upload) { mbps(it.uploadBytesPerSecond) }
                    hovered?.let { point ->
                        drawLine(muted.copy(.6f), Offset(x(point), 0f), Offset(x(point), size.height), 1.dp.toPx())
                        drawCircle(download, 4.dp.toPx(), Offset(x(point), y(mbps(point.downloadBytesPerSecond))))
                        drawCircle(upload, 4.dp.toPx(), Offset(x(point), y(mbps(point.uploadBytesPerSecond))))
                    }
                }
                if (latest != null && chartWidth > 0) {
                    val fractionDown = 1f - (mbps(latest.downloadBytesPerSecond) / scaleMax).toFloat().coerceIn(0f, 1f)
                    val fractionUp = 1f - (mbps(latest.uploadBytesPerSecond) / scaleMax).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxSize().padding(top = 7.dp, bottom = 7.dp)) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            val labelDown = (if (newStyle) "" else "↓ ") + formatMbps(latest.downloadBytesPerSecond)
                            val labelUp = (if (newStyle) "" else "↑ ") + formatMbps(latest.uploadBytesPerSecond)
                            val downY = (maxHeight * fractionDown - 9.dp)
                            var upY = (maxHeight * fractionUp - 9.dp)
                            if (kotlin.math.abs((upY - downY).value) < 18f) upY = downY + 18.dp
                            Text(labelDown, color = download, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.align(Alignment.TopEnd).offset(y = downY).width(66.dp))
                            Text(labelUp, color = upload, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.align(Alignment.TopEnd).offset(y = upY).width(66.dp))
                        }
                    }
                }
                if (visible.isEmpty()) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BarChart, null, tint = muted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(when {
                            !connected -> "Connect to start recording speed"
                            points.isEmpty() -> "Waiting for the first samples…"
                            else -> "No traffic samples in the last 90 seconds"
                        }, color = muted, fontSize = 11.5.sp)
                    }
                }
                if (hovered != null && chartWidth > 0) {
                    val tooltipWidth = 150.dp
                    Column(
                        Modifier.offset {
                            val pointX = chartWidth * fraction(hovered)
                            val width = tooltipWidth.roundToPx()
                            val gap = 10.dp.roundToPx()
                            val left = if (pointX + gap + width <= chartWidth) pointX + gap else pointX - gap - width
                            IntOffset(left.toInt().coerceAtLeast(0), 0)
                        }.width(tooltipWidth).background(surface, RoundedCornerShape(8.dp))
                            .border(1.dp, grid, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        val secondsAgo = ((now - hovered.atMillis) / 1_000).coerceAtLeast(0)
                        Text(if (secondsAgo == 0L) "now" else "$secondsAgo s ago", color = muted, fontSize = 10.sp)
                        Text("Download  ${formatMbps(hovered.downloadBytesPerSecond)}", color = download, fontSize = 11.sp, maxLines = 1)
                        Text("Upload  ${formatMbps(hovered.uploadBytesPerSecond)}", color = upload, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 70.dp, top = 4.dp)) {
            val labels = if (newStyle) listOf("90s", "75s", "60s", "45s", "30s", "15s", "0s") else listOf("-90s", "", "-60s", "", "-30s", "", "Now")
            labels.forEachIndexed { index, label ->
                Text(label, color = muted, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.weight(1f),
                    textAlign = when (index) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; labels.lastIndex -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Center })
            }
        }
        if (legendBelow) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                StatusDot(download, 10.dp); Spacer(Modifier.width(7.dp)); Text("Download", color = White.copy(.8f), fontSize = 12.sp)
                Spacer(Modifier.width(26.dp))
                StatusDot(upload, 10.dp); Spacer(Modifier.width(7.dp)); Text("Upload", color = White.copy(.8f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun ChartLegend(download: Color, upload: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(download, 10.dp); Spacer(Modifier.width(7.dp)); Text("Download", color = White.copy(.8f), fontSize = 12.sp)
        Spacer(Modifier.width(20.dp))
        StatusDot(upload, 10.dp); Spacer(Modifier.width(7.dp)); Text("Upload", color = White.copy(.8f), fontSize = 12.sp)
    }
}
