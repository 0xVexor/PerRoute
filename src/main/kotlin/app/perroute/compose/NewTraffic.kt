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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun newUpload(): Color = if (IsDark) NewColors.Lime else Color(0xFFB4CF1F)

@Composable
internal fun NewTrafficPage(shell: Shell) {
    val model = trafficModel(shell)
    TrafficEffects(model)
    val report = model.report
    val rows = model.appRows()
    if (model.selectedApp == null || rows.none { it.path.equals(model.selectedApp, true) }) model.selectedApp = rows.firstOrNull()?.path
    val download = Cyan
    val upload = newUpload()
    NewSplitPage(panel = { NewBandwidthPanel(shell, model, rows.firstOrNull { it.path.equals(model.selectedApp, true) }) }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NewHeading(listOf("Tools", "Traffic"), "Traffic", "See usage and control app limits.")
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(TrafficView.entries, model.view, { it.label }, Modifier.width(380.dp)) { model.view = it }
                Spacer(Modifier.weight(1f))
                MenuButton(if (model.appFilter.isBlank()) "All apps" else appName(model.appFilter), model.appEntries(), Modifier.width(190.dp),
                    accessibleLabel = "Show totals for an application")
                MenuIcon(Icons.Rounded.MoreHoriz, "More traffic actions", model.moreMenu(), size = 40.dp, tint = White)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TotalTile(Icons.Rounded.ArrowDownward, download, "Today download", formatBytes(report.todayDownloadBytes()))
                TotalTile(Icons.Rounded.ArrowUpward, upload, "Today upload", formatBytes(report.todayUploadBytes()))
                Divider(Modifier.height(60.dp), vertical = true)
                TotalTile(Icons.Rounded.ArrowDownward, download, "This month download", formatBytes(report.monthDownloadBytes()))
                TotalTile(Icons.Rounded.ArrowUpward, upload, "This month upload", formatBytes(report.monthUploadBytes()))
            }
            if (model.appFilter.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Totals for ${appName(model.appFilter)} only.", color = Muted, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Network usage", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(" (last 90 seconds)", color = White, fontSize = 15.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("Mbps", color = Muted, fontSize = 11.sp)
            LiveSpeedChart(model.points, shell.connected, download, upload, legendBelow = true, modifier = Modifier.fillMaxWidth().height(220.dp))
            Spacer(Modifier.height(18.dp))
            Text("Bandwidth by app", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (rows.isEmpty()) {
                EmptyState(Icons.Rounded.BarChart, "No apps selected", "Choose apps in Apps to see their live traffic and set bandwidth limits.", Modifier.fillMaxWidth()) {
                    SecondaryButton("Manage apps") { shell.navigate(Tab.Apps) }
                }
            } else {
                NewTableHeader {
                    HeaderText("App", Modifier.weight(1.2f))
                    HeaderText("Download", Modifier.weight(1f))
                    HeaderText("Upload", Modifier.weight(1f))
                    HeaderText("Down limit", Modifier.weight(1f))
                    HeaderText("Up limit", Modifier.weight(1f))
                    HeaderText("Utilization", Modifier.width(78.dp))
                    HeaderText("Status", Modifier.width(110.dp))
                    Box(Modifier.width(32.dp))
                }
                rows.forEach { row ->
                    NewTableRow(row.path.equals(model.selectedApp, true), onClick = { model.selectedApp = row.path }, label = "Bandwidth of ${appName(row.path)}") {
                        Row(Modifier.weight(1.2f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppAvatar(fileName(row.path), row.path, 26.dp)
                            Spacer(Modifier.width(10.dp))
                            EllipsisText(appName(row.path), White, 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Cell(formatMbps(row.usage?.downloadBytesPerSecond ?: 0))
                        Cell(formatMbps(row.usage?.uploadBytesPerSecond ?: 0))
                        Cell(limitText(row.policy.downloadBitsPerSecond()))
                        Cell(limitText(row.policy.uploadBitsPerSecond()))
                        Text(shareText(row.utilization), color = White, fontSize = 12.5.sp, modifier = Modifier.width(78.dp))
                        Box(Modifier.width(110.dp)) { NewStateLabel(row) }
                        MenuIcon(Icons.Rounded.MoreHoriz, "Limits for ${appName(row.path)}", model.rowMenu(row.path, row.policy) { model.selectedApp = row.path },
                            size = 30.dp, tint = White.copy(.7f), bordered = false)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val throttled = rows.count { it.status.state == BandwidthState.THROTTLING }
                    Text("${plural(rows.count { it.active }, "active app")}  ·  $throttled throttling  ·  Total: ${formatMbps(shell.traffic.downloadBytesPerSecond)} down  /  ${formatMbps(shell.traffic.uploadBytesPerSecond)} up",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Usage privacy: only totals are stored. Destinations are never stored.", color = Muted, fontSize = 11.sp)
                }
                rows.firstOrNull { it.status.state == BandwidthState.NOT_ENFORCED }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it.status.detail, color = Red, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            UsageSection(model, download, upload)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RowScope.TotalTile(icon: ImageVector, color: Color, label: String, value: String) {
    Row(Modifier.weight(1f).background(Surface, RoundedCornerShape(6.dp)).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 11.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = White.copy(.8f), fontSize = 11.5.sp, maxLines = 1)
            Text(value, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun RowScope.Cell(text: String) {
    Text(text, color = White, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
}

@Composable
private fun NewStateLabel(row: AppBandwidthRow) {
    val (label, color) = bandwidthStateText(row.status.state, idle = !row.active)
    Tooltip(row.status.detail) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 10.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, color = White, fontSize = 12.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.NewBandwidthPanel(shell: Shell, model: TrafficModel, row: AppBandwidthRow?) {
    if (row == null) {
        NewPanelEmpty("App bandwidth", "No app selected", "Select an app in Apps; its live rate, limits and session usage appear here.")
        return
    }
    NewPanelHeading("App bandwidth", appName(row.path), "Control bandwidth for this app.", leading = { AppAvatar(fileName(row.path), row.path, 50.dp, framed = true) })
    NewPanelSection("Current bandwidth") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelRate(Modifier.weight(1f), Icons.Rounded.ArrowDownward, Cyan, formatMbps(row.usage?.downloadBytesPerSecond ?: 0), "Download")
            Divider(Modifier.height(44.dp), vertical = true)
            Spacer(Modifier.width(16.dp))
            PanelRate(Modifier.weight(1f), Icons.Rounded.ArrowUpward, NewColors.Lime, formatMbps(row.usage?.uploadBytesPerSecond ?: 0), "Upload")
        }
    }
    NewPanelSection("Limit settings") { BandwidthEditor(shell, row.path, showTitle = false) }
    NewPanelSection("Current utilization") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressBar(row.utilization ?: 0f, if ((row.utilization ?: 0f) >= .9f) Amber else NewColors.Lime, Modifier.weight(1f), height = 10.dp)
            Spacer(Modifier.width(14.dp))
            Text(shareText(row.utilization), color = White, fontSize = 15.sp)
        }
        if (row.utilization == null) Text("No limit, so there is nothing to measure against.", color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
    }
    NewPanelSection("Session usage") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelRate(Modifier.weight(1f), Icons.Rounded.ArrowDownward, Cyan, formatBytes(row.usage?.downloadBytes ?: 0), "Downloaded")
            Divider(Modifier.height(44.dp), vertical = true)
            Spacer(Modifier.width(16.dp))
            PanelRate(Modifier.weight(1f), Icons.Rounded.ArrowUpward, NewColors.Lime, formatBytes(row.usage?.uploadBytes ?: 0), "Uploaded")
        }
    }
}

@Composable
private fun PanelRate(modifier: Modifier, icon: ImageVector, color: Color, value: String, label: String) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(value, color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, color = White.copy(.8f), fontSize = 12.5.sp)
        }
    }
}
