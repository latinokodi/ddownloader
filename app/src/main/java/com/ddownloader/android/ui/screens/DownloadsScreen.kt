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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.AnimatedRing
import com.ddownloader.android.ui.components.AuroraCard
import com.ddownloader.android.ui.components.EmptyState
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.Pill
import com.ddownloader.android.ui.components.SectionHeader
import com.ddownloader.android.ui.components.StatTile
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor

/**
 * Transfers — the operational screen.
 *
 * Design intent: a control room, not a list. Each transfer is a card whose ring animates as bytes
 * land, whose accent colour encodes state, and which shows what an operator wants (done/total,
 * speed, ETA, provider). Destructive actions stay out of the way until a transfer cannot progress
 * on its own.
 */
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
                body = "Queue a direct link or a magnet from Search, or pick a title in Discover and " +
                    "download a stream through TorBox or Real-Debrid.",
            )
        }
        return
    }

    val done = records.count { it.state == TransferState.COMPLETED }
    val queued = records.count { it.state == TransferState.QUEUED || it.state == TransferState.RESOLVING }
    val failed = records.count { it.state == TransferState.FAILED }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                SectionHeader(
                    title = "Transfers",
                    subtitle = "${records.size} total · $active running",
                    action = {
                        GhostButton(
                            text = "Clear finished",
                            icon = AppIcons.Delete,
                            enabled = records.any { it.state.isTerminal },
                            onClick = { vm.clearCompleted() },
                        )
                    },
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatTile("$active", "ACTIVE", TdColor.Cyan, Modifier.weight(1f))
                    StatTile("$queued", "QUEUED", TdColor.Violet, Modifier.weight(1f))
                    StatTile("$done", "DONE", TdColor.Mint, Modifier.weight(1f))
                    StatTile(
                        "$failed",
                        "FAILED",
                        if (failed > 0) TdColor.Rose else TdColor.TextLow,
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        items(records, key = { it.id }) { record ->
            Box(Modifier.animateItem()) {
                TransferCard(record = record, live = live[record.id], vm = vm)
            }
        }
    }
}

@Composable
private fun TransferCard(
    record: DownloadRecord,
    live: DownloadManager.Live?,
    vm: AppViewModel,
) {
    val state = live?.state ?: record.state
    val bytes = live?.bytes ?: record.bytesDone
    val total = (live?.total ?: record.expectedSize).coerceAtLeast(0)
    val percent = if (total > 0) {
        ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100)
    } else {
        (live?.percent ?: 0).coerceIn(0, 100)
    }
    val accent = stateColor(state)

    AuroraCard(accent = accent, contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedRing(percent = percent, accent = accent)
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
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(providerLabel(record.service), providerColor(record.service))
                        Pill(stateLabel(state), accent)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TdColor.Line),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (total > 0) "${Fmt.bytes(bytes)} / ${Fmt.bytes(total)}" else Fmt.bytes(bytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = TdColor.TextMid,
                    )
                    val detail = when {
                        state == TransferState.DOWNLOADING && live != null ->
                            "${Fmt.speed(live.bytesPerSecond)} · ${live.eta} left"
                        state == TransferState.FAILED -> record.error ?: live?.error ?: "Transfer failed"
                        state == TransferState.COMPLETED -> record.destDisplay.ifBlank { "Saved" }
                        else -> stateHint(state)
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state == TransferState.FAILED) TdColor.Rose else TdColor.TextLow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    state == TransferState.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Retry", icon = AppIcons.Refresh) { vm.retry(record.id) }
                        GhostButton("Remove", icon = AppIcons.Delete) { vm.remove(record.id) }
                    }
                    state.isActive -> GhostButton("Stop", icon = AppIcons.Close) { vm.cancel(record.id) }
                    else -> GhostButton("Remove", icon = AppIcons.Delete) { vm.remove(record.id) }
                }
            }
        }
    }
}

private fun stateColor(state: TransferState) = when (state) {
    TransferState.COMPLETED -> TdColor.Mint
    TransferState.FAILED -> TdColor.Rose
    TransferState.PAUSED -> TdColor.Amber
    TransferState.CANCELLED -> TdColor.TextLow
    TransferState.DOWNLOADING, TransferState.RESOLVING -> TdColor.Cyan
    TransferState.QUEUED -> TdColor.Violet
}

private fun stateLabel(state: TransferState) = when (state) {
    TransferState.QUEUED -> "queued"
    TransferState.RESOLVING -> "resolving"
    TransferState.DOWNLOADING -> "downloading"
    TransferState.PAUSED -> "paused"
    TransferState.COMPLETED -> "completed"
    TransferState.FAILED -> "failed"
    TransferState.CANCELLED -> "cancelled"
}

private fun stateHint(state: TransferState) = when (state) {
    TransferState.QUEUED -> "Waiting for a free slot"
    TransferState.RESOLVING -> "Resolving the debrid link"
    TransferState.PAUSED -> "Paused — resumes automatically"
    else -> ""
}

private fun providerLabel(service: String) = when (service) {
    "torbox" -> "TorBox"
    "realdebrid" -> "Real-Debrid"
    else -> "Direct"
}

private fun providerColor(service: String) = when (service) {
    "torbox" -> TdColor.Violet
    "realdebrid" -> TdColor.Cyan
    else -> TdColor.TextMid
}
