package app.perroute.compose.ui.components

import app.perroute.compose.*
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.quality.QualityLabel
import app.perroute.quality.QualityScore

@Composable
internal fun qualityColor(score: QualityScore?): Color = when (score?.label()) {
    QualityLabel.EXCELLENT, QualityLabel.GOOD -> Cyan
    QualityLabel.FAIR -> Amber
    QualityLabel.POOR, QualityLabel.UNAVAILABLE -> Red
    else -> Muted
}

@Composable
internal fun QualityPill(score: QualityScore?, modifier: Modifier = Modifier, compact: Boolean = false, fontSize: TextUnit = if (compact) 11.sp else 12.sp) {
    val color = qualityColor(score)
    val label = score?.label() ?: QualityLabel.UNKNOWN
    val unknown = label == QualityLabel.UNKNOWN
    val stale = score?.stale() == true && !unknown
    val tooltip = when {
        score == null || unknown -> score?.headline() ?: "Not measured yet."
        else -> "${score.headline()} Confidence ${score.confidencePercent()}%." +
            (if (score.measuredAt() > 0) " Last measured ${formatAgo(score.measuredAt())}." else "") +
            (if (stale) " The result is older than 30 minutes and may be outdated." else "")
    }
    Tooltip(tooltip, modifier) {
        Row(
            Modifier.semantics { contentDescription = "Network quality ${label.text()}${if (unknown) "" else score?.score()?.let { ", $it of 100" } ?: ""}${if (stale) ", outdated" else ""}" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                unknown -> Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = Muted, modifier = Modifier.size(if (compact) 15.dp else 17.dp))
                Tok.isNew -> Icon(Icons.Rounded.SignalCellularAlt, null, tint = color, modifier = Modifier.size(if (compact) 15.dp else 17.dp))
                else -> StatusDot(color, if (compact) 9.dp else 10.dp)
            }
            Spacer(Modifier.width(6.dp))
            if (!unknown) {
                Text(score?.score()?.toString() ?: "", color = if (Tok.isNew) White else color, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(5.dp))
            }
            Text(label.text(), color = if (unknown) Muted else color, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
            if (stale) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.Schedule, "Outdated", tint = Amber, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
internal fun ConfidenceBadge(score: QualityScore?) {
    val percent = score?.takeIf { it.label() != QualityLabel.UNKNOWN }?.confidencePercent()
    val color = when {
        percent == null -> Muted
        percent >= 70 -> Cyan
        percent >= 40 -> Amber
        else -> Red
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Confidence ${percent?.let { "$it percent" } ?: "unknown"}" }) {
        Icon(Icons.Rounded.SignalCellularAlt, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(percent?.let { "$it%" } ?: "—", color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun qualityMetrics(score: QualityScore?, speed: String?): List<Pair<String, String>> {
    val parts = score?.components()
    val measured = score != null && score.label() != QualityLabel.UNKNOWN
    return listOfNotNull(
        "Latency" to (parts?.latencyMillis()?.let { "$it ms" } ?: "—"),
        "Jitter" to (parts?.jitterMillis()?.let { "$it ms" } ?: "—"),
        "Success" to (parts?.successRate()?.let { "${Math.round(it * 100)}%" } ?: "—"),
        "Stability" to (parts?.stabilityScore()?.let { "$it%" } ?: "—"),
        "Tunnel setup" to (parts?.setupMillis()?.let { "$it ms" } ?: "Not verified"),
        speed?.let { "Speed test" to it },
        "Confidence" to (if (measured) "${score!!.confidencePercent()}%" else "—"),
        "Measurements" to (score?.sampleCount()?.toString() ?: "0"),
        "Measured" to (score?.measuredAt()?.takeIf { it > 0 }?.let { formatAgo(it) } ?: "Never")
    )
}

@Composable
internal fun QualityMetricList(score: QualityScore?, speed: String?, columns: Int = 1, labelWidth: Dp = 120.dp) {
    val metrics = qualityMetrics(score, speed)
    if (columns <= 1) Column {
        metrics.forEach { (label, value) -> DetailLine(label, value, labelWidth) }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        metrics.chunked((metrics.size + columns - 1) / columns).forEach { chunk ->
            Column(Modifier.weight(1f)) { chunk.forEach { (label, value) -> DetailLine(label, value, labelWidth) } }
        }
    }
}

@Composable
internal fun QualityExplanation(score: QualityScore?) {
    if (score == null || score.label() == QualityLabel.UNKNOWN) {
        Text(unmeasuredExplanation(score), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        return
    }
    Column(Modifier.padding(top = 2.dp)) {
        Text(score.headline(), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(score.explanation(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        val penalty = score.components().recoveryPenalty()
        if (penalty > 0) Text("Recent recovery: -$penalty.", color = Muted, fontSize = 10.5.sp)
    }
}

internal fun qualitySummary(score: QualityScore?): String = when {
    score == null || score.label() == QualityLabel.UNKNOWN -> "Not measured yet"
    score.label() == QualityLabel.UNAVAILABLE -> "Unavailable"
    else -> "${score.label().text()} · ${score.score()}" + if (score.stale()) " (outdated)" else ""
}
