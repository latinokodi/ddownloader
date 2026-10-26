package com.ddownloader.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.CardRadius
import com.ddownloader.android.ui.theme.Motion
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.pressEffect

/**
 * The first thing you see. Deliberately loud: a two-line gradient wordmark, then the three things
 * a user does on a downloader (paste, browse, queue) as large tiles rather than buried cards.
 *
 * Everything animates in once (`riseIn`) and reacts to touch, so the screen has a pulse without
 * costing a frame budget.
 */
@Composable
fun HomeHero(
    active: Int,
    queued: Int,
    completed: Int,
    hasDebrid: Boolean,
    hasTmdb: Boolean,
    onOpenDownloads: () -> Unit,
    onOpenDiscover: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))

        Spacer(Modifier.height(22.dp))

        Text(
            "DOWNLOAD",
            style = MaterialTheme.typography.displayMedium.copy(
                brush = Brush.linearGradient(listOf(TdColor.TextHi, TdColor.TextHi.copy(alpha = 0.72f))),
            ),
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )
        Text(
            "ANYTHING",
            style = MaterialTheme.typography.displayMedium.copy(
                brush = Brush.linearGradient(listOf(TdColor.Cyan, TdColor.Violet)),
            ),
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Search a title, paste a magnet, or pull a cached file through your debrid account.",
            style = MaterialTheme.typography.bodyMedium,
            color = TdColor.TextMid,
        )

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(
                icon = AppIcons.CloudDownload,
                label = "Paste",
                hint = "link or magnet",
                accent = TdColor.Cyan,
                value = null,
                modifier = Modifier.weight(1f),
                onClick = onPaste,
            )
            ActionTile(
                icon = AppIcons.Download,
                label = "Transfers",
                hint = if (active > 0) "$active running" else "queue",
                accent = TdColor.Violet,
                value = if (queued + active > 0) "${queued + active}" else null,
                modifier = Modifier.weight(1f),
                onClick = onOpenDownloads,
            )
            ActionTile(
                icon = AppIcons.Explore,
                label = "Discover",
                hint = if (hasTmdb) "categories" else "needs TMDB",
                accent = if (hasTmdb) TdColor.Mint else TdColor.Amber,
                value = null,
                modifier = Modifier.weight(1f),
                onClick = onOpenDiscover,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniStat("$completed", "done", TdColor.Mint)
            MiniStat(if (hasDebrid) "linked" else "none", "debrid", if (hasDebrid) TdColor.Mint else TdColor.Amber)
            MiniStat(if (hasTmdb) "set" else "missing", "tmdb", if (hasTmdb) TdColor.Mint else TdColor.Amber)
            MiniStat("keystore", "tokens", TdColor.TextMid)
        }
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    hint: String,
    accent: Color,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressEffect(interaction)
            .clip(RoundedCornerShape(CardRadius))
            .background(Brush.verticalGradient(listOf(TdColor.Ink1, TdColor.Ink2)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // accent wash in the corner so each tile reads at a glance
        Box(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    ),
                ),
        )
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.weight(1f))
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = TdColor.TextHi,
                fontWeight = FontWeight.SemiBold,
            )
            Text(hint, style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, accent: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TdColor.Ink1.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
    }
}

/** Fade+rise entrance used by the hero so the screen assembles instead of appearing. */
@Composable
fun riseIn(enabled: Boolean): Float {
    val progress by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(Motion.SLOW),
        label = "rise",
    )
    return progress
}
