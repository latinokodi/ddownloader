package com.ddownloader.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.DividerLine
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.GlassCard
import com.ddownloader.android.ui.components.MetricRow
import com.ddownloader.android.ui.components.SectionLabel
import com.ddownloader.android.ui.components.StatusPill
import com.ddownloader.android.ui.components.TdTextField
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor

@Composable
fun LogsScreen(vm: AppViewModel) {
    val entries by vm.logs.collectAsStateWithLifecycle()
    val errors by vm.logErrors.collectAsStateWithLifecycle()
    val filters = listOf("ALL", "INFO", "WARN", "ERROR")
    val filtered = remember(entries, vm.logFilter) {
        if (vm.logFilter == "ALL") entries else entries.filter { it.level.name == vm.logFilter }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Logs", style = MaterialTheme.typography.headlineMedium, color = TdColor.TextHi)
                    Text(
                        "${entries.size} events • $errors errors • secrets redacted",
                        style = MaterialTheme.typography.labelSmall,
                        color = TdColor.TextLow,
                    )
                }
                GhostButton("Clear", icon = AppIcons.Delete) { vm.clearLogs() }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    val active = vm.logFilter == f
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) TdColor.Ink3 else Color.Transparent)
                            .clickable { vm.logFilter = f }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            f,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) TdColor.Cyan else TdColor.TextLow,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        item { DividerLine() }
        if (filtered.isEmpty()) {
            item {
                Text(
                    "Nothing logged yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextLow,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(filtered.size) { index ->
            val e = filtered[filtered.size - 1 - index] // newest first
            val tint = when (e.level.name) {
                "ERROR" -> TdColor.Rose
                "WARN" -> TdColor.Amber
                "DEBUG" -> TdColor.TextLow
                else -> TdColor.TextMid
            }
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(e.time(), style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        e.level.name.take(1),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(e.tag, style = MaterialTheme.typography.labelSmall, color = TdColor.Violet)
                }
                Text(
                    e.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextHi,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            GlassCard {
                SectionLabel("Diagnostics")
                Spacer(Modifier.height(8.dp))
                MetricRow("Version", com.ddownloader.android.BuildConfig.VERSION_NAME)
                MetricRow("Build", if (com.ddownloader.android.BuildConfig.DEBUG) "debug" else "release")
                MetricRow("Transport", "HTTPS only")
                val sup = androidx.compose.ui.platform.LocalContext.current.applicationInfo.nativeLibraryDir
                MetricRow("Native libs", sup.substringAfterLast('/'))
                Spacer(Modifier.height(8.dp))
                StatusPill("Logs never contain tokens", TdColor.Mint, AppIcons.VerifiedUser)
            }
        }
    }
}

