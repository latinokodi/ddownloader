package com.ddownloader.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddownloader.android.ui.AppViewModel
import com.ddownloader.android.ui.components.GhostButton
import com.ddownloader.android.ui.components.Pill
import com.ddownloader.android.ui.components.SectionHeader
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor

/**
 * Logs — a terminal-looking, filterable event stream.
 *
 * Level is encoded twice (a colour stripe and the initial letter) so it survives colour-blind
 * viewing, and entries are monospaced because log text is scanned, not read. Secrets are already
 * redacted upstream by [com.ddownloader.android.core.AppLog].
 */
@Composable
fun LogsScreen(vm: AppViewModel) {
    val str = com.ddownloader.android.ui.i18n.LocalStrings.current
    val entries by vm.logs.collectAsStateWithLifecycle()
    val errors by vm.logErrors.collectAsStateWithLifecycle()
    val filters = listOf("ALL", "INFO", "WARN", "ERROR")
    val filtered = remember(entries, vm.logFilter) {
        if (vm.logFilter == "ALL") entries else entries.filter { it.level.name == vm.logFilter }
    }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            SectionHeader(
                title = str.logs,
                subtitle = str.logsSummary.format(entries.size, errors),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(str.copyLabel, icon = AppIcons.Link) {
                            val text = filtered.joinToString("\n") {
                                "${it.time()} ${it.level.name.first()}/${it.tag}: ${it.text}"
                            }
                            clipboard.setText(AnnotatedString(text))
                        }
                        GhostButton(str.clear, icon = AppIcons.Delete) { vm.clearLogs() }
                    }
                },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    val active = vm.logFilter == f
                    val tint = when (f) {
                        "ERROR" -> TdColor.Rose
                        "WARN" -> TdColor.Amber
                        "INFO" -> TdColor.Cyan
                        else -> TdColor.TextHi
                    }
                    val bg by animateColorAsState(
                        targetValue = if (active) tint.copy(alpha = 0.16f) else TdColor.Ink1,
                        animationSpec = tween(180),
                        label = "filter-bg",
                    )
                    val fg by animateColorAsState(
                        targetValue = if (active) tint else TdColor.TextLow,
                        animationSpec = tween(180),
                        label = "filter-fg",
                    )
                    Text(
                        f,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(bg)
                            .border(
                                BorderStroke(1.dp, if (active) tint.copy(alpha = 0.4f) else TdColor.Line),
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { vm.logFilter = f }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    str.logsEmpty,
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TdColor.Ink1.copy(alpha = 0.75f))
                    .padding(10.dp),
            ) {
                // level stripe: colour is not the only signal, the letter is there too
                Box(
                    Modifier
                        .width(3.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            e.time(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TdColor.TextLow,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            e.tag.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        e.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColor.TextMid,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
