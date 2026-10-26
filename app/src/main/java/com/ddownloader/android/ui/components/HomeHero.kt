package com.ddownloader.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ddownloader.android.ui.i18n.LocalStrings
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.pressEffect

/**
 * Page header for the Search tab.
 *
 * A tool's home screen should answer "what do I do here" in one line, not shout a slogan. So: the
 * tab title, one sentence of orientation, and three flat shortcut cards with live counts. The
 * wordmark lives in the top bar; repeating it here was the least professional part of the old
 * design.
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
    val str = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(6.dp))
        Text(
            str.search,
            style = MaterialTheme.typography.displayMedium,
            color = TdColor.TextHi,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            str.heroSub,
            style = MaterialTheme.typography.bodySmall,
            color = TdColor.TextMid,
        )

        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$completed ${str.statDone}",
                style = MaterialTheme.typography.labelSmall,
                color = TdColor.TextLow,
            )
            Dot()
            Text(
                if (hasDebrid) str.statLinked else str.statNone,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasDebrid) TdColor.Mint else TdColor.Amber,
            )
            Dot()
            Text(
                if (hasTmdb) "TMDB" else str.needsTmdbShort,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasTmdb) TdColor.Mint else TdColor.Amber,
            )
        }
    }
}

@Composable
private fun Dot() {
    Text(
        "·",
        style = MaterialTheme.typography.labelSmall,
        color = TdColor.Line,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun Shortcut(
    icon: ImageVector,
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    accent: Color = TdColor.TextMid,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressEffect(interaction, pressedScale = 0.97f)
            .clip(RoundedCornerShape(10.dp))
            .background(TdColor.Ink1)
            .border(BorderStroke(1.dp, TdColor.Line), RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TdColor.TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
