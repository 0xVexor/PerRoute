package app.perroute.compose

import app.perroute.compose.controller.*
import app.perroute.compose.ui.components.*
import app.perroute.compose.inspector.*
import app.perroute.compose.diagnostics.*
import app.perroute.backend.api.IsolatedTunnel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SimpleTrafficPage(shell: Shell) {
    val model = trafficModel(shell)
    TrafficEffects(model)
    val report = model.report
    val rows = model.appRows()
    val download = Cyan
    val upload = Violet2
    SimplePage("Traffic", "Understand usage. Keep limits in control.", actions = {
        MenuButton(if (model.appFilter.isBlank()) "All apps" else appName(model.appFilter), model.appEntries(), Modifier.width(210.dp),
            accessibleLabel = "Show totals for an application", height = 46.dp)
        MenuIcon(Icons.Rounded.MoreHoriz, "More traffic actions", model.moreMenu(), size = 46.dp, tint = White)
    }) {
        UnderlineTabs(TrafficView.entries, model.view, { it.label }, Modifier.fillMaxWidth()) { model.view = it }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TotalBlock(Icons.Rounded.ArrowDownward, download, "Today download", formatBytes(report.todayDownloadBytes()))
            Divider(Modifier.height(48.dp), vertical = true)
            TotalBlock(Icons.Rounded.ArrowUpward, upload, "Today upload", formatBytes(report.todayUploadBytes()))
            Divider(Modifier.height(48.dp), vertical = true)
            Column(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                Text("Month", color = Muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = download, modifier = Modifier.size(18.dp))
                    Text(" ${formatBytes(report.monthDownloadBytes())}", color = White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("  ·  ", color = Muted, fontSize = 15.sp)
                    Icon(Icons.Rounded.ArrowUpward, null, tint = upload, modifier = Modifier.size(18.dp))
                    Text(" ${formatBytes(report.monthUploadBytes())}", color = White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (model.appFilter.isNotBlank()) Text("Totals for ${appName(model.appFilter)} only.", color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        SimpleCard(padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live speed", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("  ·  Last 90 seconds", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                ChartLegend(download, upload)
            }
            Spacer(Modifier.height(10.dp))
            LiveSpeedChart(model.points, shell.connected, download, upload, legendBelow = false, modifier = Modifier.fillMaxWidth().height(200.dp))
        }
        Spacer(Modifier.height(16.dp))
        SimpleCard(padding = 20.dp) {
            val throttled = rows.count { it.status.state == BandwidthState.THROTTLING }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bandwidth by app", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${rows.count { it.active }} active", color = Cyan, fontSize = 14.sp)
                if (throttled > 0) Text("  ·  $throttled throttling", color = Amber, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (rows.isEmpty()) EmptyState(Icons.Rounded.BarChart, "No apps selected", "Choose apps in Apps to see their live traffic and set bandwidth limits.", Modifier.fillMaxWidth()) {
                SecondaryButton("Manage apps") { shell.navigate(Tab.Apps) }
            } else {
                SimpleTableHeader {
                    HeaderText("Application", Modifier.weight(1.2f))
                    HeaderText("Live ↓ / ↑", Modifier.weight(.9f))
                    HeaderText("Download limit", Modifier.weight(1.3f))
                    HeaderText("Status", Modifier.weight(.8f))
                    Box(Modifier.width(36.dp))
                }
                rows.forEach { row ->
                    SimpleTableRow {
                        Row(Modifier.weight(1.2f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppAvatar(fileName(row.path), row.path, 34.dp)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                EllipsisText(appName(row.path), White, 14.5.sp, fontWeight = FontWeight.Medium)
                                Text("${formatBytes(model.usageOf(row.path))} ${if (model.view == TrafficView.Month) "this month" else "today"}", color = Muted, fontSize = 12.5.sp)
                            }
                        }
                        Text("${mbpsNumber(row.usage?.downloadBytesPerSecond ?: 0)} / ${formatMbps(row.usage?.uploadBytesPerSecond ?: 0)}", color = White, fontSize = 14.sp,
                            modifier = Modifier.weight(.9f))
                        Column(Modifier.weight(1.3f).padding(end = 18.dp)) {
                            val limit = row.policy.downloadBitsPerSecond()
                            if (limit <= 0) Text("Unlimited", color = White.copy(.85f), fontSize = 14.sp)
                            else {
                                Text("${mbpsNumber(row.usage?.downloadBytesPerSecond ?: 0)} of ${formatMbps(limit / 8)} (${shareText(row.downShare)})", color = White, fontSize = 13.5.sp)
                                Spacer(Modifier.height(5.dp))
                                ProgressBar(row.downShare ?: 0f, if ((row.downShare ?: 0f) >= .9f) Amber else Cyan, Modifier.fillMaxWidth())
                            }
                            if (row.policy.uploadBitsPerSecond() > 0) Text("Upload limit ${formatMbps(row.policy.uploadBytesPerSecond())} (${shareText(row.upShare)})", color = Muted, fontSize = 12.sp)
                        }
                        Box(Modifier.weight(.8f)) { SimpleStateLabel(row) }
                        MenuIcon(Icons.Rounded.MoreHoriz, "Limits for ${appName(row.path)}", model.rowMenu(row.path, row.policy) { model.editLimits(row.path) },
                            size = 34.dp, tint = White.copy(.8f), bordered = false)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Edit limits from an app's menu.", color = White.copy(.8f), fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                rows.firstOrNull { it.status.state == BandwidthState.NOT_ENFORCED }?.let {
                    Text(it.status.detail, color = Red, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SimpleCard(padding = 20.dp) { UsageSection(model, download, upload) }
        Spacer(Modifier.height(10.dp))
        Text("Only usage totals are stored, never destinations.", color = Muted, fontSize = 13.sp)
    }
}

private fun mbpsNumber(bytesPerSecond: Long): String = formatMbps(bytesPerSecond).removeSuffix(" Mbps")

@Composable
private fun RowScope.TotalBlock(icon: ImageVector, color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(Modifier.weight(1f).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(value, color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun SimpleStateLabel(row: AppBandwidthRow) {
    val (label, color) = bandwidthStateText(row.status.state, idle = !row.active)
    val icon = when (row.status.state) {
        BandwidthState.ENFORCED -> Icons.Rounded.CheckCircle
        BandwidthState.THROTTLING, BandwidthState.PENDING -> Icons.Rounded.Schedule
        BandwidthState.NOT_ENFORCED -> Icons.Rounded.WarningAmber
        BandwidthState.UNLIMITED -> Icons.Rounded.RemoveCircleOutline
    }
    Tooltip(row.status.detail) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            Text(label, color = color, fontSize = 14.sp)
        }
    }
}
