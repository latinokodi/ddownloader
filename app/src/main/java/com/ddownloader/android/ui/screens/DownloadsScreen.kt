package com.ddownloader.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddownloader.android.core.Fmt
import com.ddownloader.android.data.db.DownloadRecord
import com.ddownloader.android.data.db.TransferState
import com.ddownloader.android.download.DownloadManager
import com.ddownloader.android.storage.MediaLayout
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.DividerLine
import com.ddownloader.android.ui.components.EmptyState
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.GlassCard
import com.ddownloader.android.ui.components.MetricRow
import com.ddownloader.android.ui.components.ProgressRing
import com.ddownloader.android.ui.components.SectionLabel
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.LocalTdGradients
import com.ddownloader.android.ui.theme.TdColor

@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val records by vm.records.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val active by vm.activeCount.collectAsStateWithLifecycle()

    if (records.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyState(
                icon = AppIcons.CloudDownload,
                title = "No transfers yet",
                body = "Queue a direct link from Search, or link a debrid account to pull torrents through Tor.",
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Transfers",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TdColor.TextHi,
                    )
                    Text(
                        "${records.size} total • ${active} running",
                        style = MaterialTheme.typography.labelSmall,
                        color = TdColor.TextLow,
                    )
                }
                GhostButton(
                    text = "Clear finished",
                    icon = AppIcons.Delete,
                    enabled = records.any { it.state.isTerminal },
                    onClick = { vm.clearCompleted() },
                )
            }
        }
        items(records, key = { it.id }) { record ->
            DownloadCard(record = record, live = live[record.id], vm = vm)
        }
    }
}

@Composable
private fun DownloadCard(
    record: DownloadRecord,
    live: DownloadManager.Live?,
    vm: AppViewModel,
) {
    val gradients = LocalTdGradients.current
    val state = live?.state ?: record.state
    val bytes = live?.bytes ?: record.bytesDone
    val total = (live?.total ?: record.expectedSize).coerceAtLeast(0)
    val percent = if (total > 0) ((bytes.toDouble() / total) * 100).toInt() else 0
    val accent = when (state) {
        TransferState.COMPLETED -> TdColor.Mint
        TransferState.FAILED -> TdColor.Rose
        TransferState.PAUSED -> TdColor.Amber
        TransferState.CANCELLED -> TdColor.TextLow
        TransferState.DOWNLOADING, TransferState.RESOLVING -> TdColor.Cyan
        TransferState.QUEUED -> TdColor.Violet
    }

    GlassCard(borderColor = accent.copy(alpha = 0.28f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                percent = if (state == TransferState.COMPLETED) 100 else percent,
                size = 54.dp,
                stroke = 4.dp,
                tint = if (state == TransferState.COMPLETED) gradients.success else gradients.brand,
            ) {
                Icon(
                    imageVector = when (state) {
                        TransferState.COMPLETED -> AppIcons.CheckCircle
                        TransferState.FAILED -> AppIcons.Error
                        TransferState.PAUSED -> AppIcons.Pause
                        else -> AppIcons.Download
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TdColor.TextHi,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(
                        state.name.lowercase().replaceFirstChar { it.uppercase() },
                        accent,
                    )
                    if (record.service != "direct") {
                        StatusPill(record.service, TdColor.Violet)
                    }
                    if (total > 0) {
                        StatusPill("${Fmt.bytes(total)}", TdColor.TextMid)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (total > 0) "$percent%" else "—",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
            )
        }

        if (state == TransferState.DOWNLOADING || state == TransferState.RESOLVING) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TdColor.Ink3)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((percent / 100f).coerceIn(0.01f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(gradients.brand)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        DividerLine()
        Spacer(Modifier.height(6.dp))
        MetricRow("Downloaded", "${Fmt.bytes(bytes)}${if (total > 0) " / ${Fmt.bytes(total)}" else ""}")
        if (live != null && state == TransferState.DOWNLOADING) {
            MetricRow("Speed", Fmt.speed(live.bytesPerSecond), TdColor.Cyan)
            MetricRow("ETA", live.eta, TdColor.TextHi)
            MetricRow("Via", "Tor SOCKS → exit", TdColor.Mint)
        }
        record.destDisplay.takeIf { it.isNotBlank() }?.let {
            MetricRow("Saved to", it, TdColor.TextMid)
        }
        record.error?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = TdColor.Rose, maxLines = 3)
        }

        // Layout preview: shows the Jellyfin destination parity with the original.
        if (record.mediaType == "movie" || record.mediaType == "series") {
            Spacer(Modifier.height(4.dp))
            val kind = if (record.mediaType == "series") MediaLayout.Kind.SERIES else MediaLayout.Kind.MOVIE
            val dest = MediaLayout.computeDestination(kind, record.name, null, record.season)
            MetricRow("Layout", "…/${dest.relativePath}", TdColor.TextLow)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state == TransferState.DOWNLOADING || state == TransferState.QUEUED || state == TransferState.RESOLVING ->
                    GhostButton("Pause", icon = AppIcons.Pause) { vm.pause(record.id) }
                state == TransferState.PAUSED -> {
                    GhostButton("Resume", icon = AppIcons.PlayArrow) { vm.resume(record.id) }
                    GhostButton("Remove", icon = AppIcons.Delete) { vm.cancel(record.id) }
                }
                state == TransferState.FAILED -> {
                    GhostButton("Retry", icon = AppIcons.Refresh) { vm.retry(record.id) }
                    GhostButton("Remove", icon = AppIcons.Delete) { vm.cancel(record.id) }
                }
                else -> GhostButton("Remove", icon = AppIcons.Delete) { vm.cancel(record.id) }
            }
        }
    }
}
