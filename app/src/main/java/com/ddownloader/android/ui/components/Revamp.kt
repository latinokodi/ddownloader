package com.ddownloader.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.CardRadius
import com.ddownloader.android.ui.theme.Motion
import com.ddownloader.android.ui.theme.PillRadius
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.pressEffect
import com.ddownloader.android.ui.theme.shimmerBrush

/**
 * Surfaces and micro-components for the revamped UI.
 *
 * Everything here is built from the same three ingredients — a hairline gradient border, a
 * near-black fill and one accent — so screens look like one product rather than a collection
 * of screens.
 */

/** Card with a gradient hairline border; the accent gradient is what makes it feel lit. */
@Composable
fun AuroraCard(
    modifier: Modifier = Modifier,
    accent: Color = TdColor.Cyan,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(CardRadius))
            .background(
                Brush.verticalGradient(
                    listOf(TdColor.Ink1, TdColor.Ink2),
                ),
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.42f),
                            TdColor.Line.copy(alpha = 0.35f),
                            accent.copy(alpha = 0.12f),
                        ),
                    ),
                ),
                RoundedCornerShape(CardRadius),
            ),
    ) {
        // ColumnScope + padding mirror the old GlassCard contract: callers stack children
        // inside the card, so dropping the scope silently destroyed every replaced screen.
        androidx.compose.foundation.layout.Column(
            Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/** Same card, but it reacts to touch: scales down and lifts its accent. */
@Composable
fun PressableCard(
    modifier: Modifier = Modifier,
    accent: Color = TdColor.Cyan,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressEffect(interaction)
            .clip(RoundedCornerShape(CardRadius))
            .background(Brush.verticalGradient(listOf(TdColor.Ink1, TdColor.Ink2)))
            .border(
                BorderStroke(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), TdColor.Line))),
                RoundedCornerShape(CardRadius),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        content()
    }
}

/** Section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TdColor.TextHi,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
            }
        }
        action?.invoke()
    }
}

/** Compact metric used in the header strips. */
@Composable
fun StatTile(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TdColor.Ink1.copy(alpha = 0.85f))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.22f)), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TdColor.TextLow)
    }
}

/** Animated percentage ring; the sweep animates whenever the value changes. */
@Composable
fun AnimatedRing(
    percent: Int,
    accent: Color,
    size: androidx.compose.ui.unit.Dp = 54.dp,
    stroke: androidx.compose.ui.unit.Dp = 5.dp,
) {
    val animated by animateFloatAsState(
        targetValue = percent.coerceIn(0, 100) / 100f,
        animationSpec = tween(Motion.SLOW),
        label = "ring",
    )
    Box(contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            drawCircle(
                color = TdColor.Line,
                radius = (this.size.minDimension - stroke.toPx()) / 2f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke.toPx()),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = TdColor.TextHi,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Skeleton block with a moving highlight. */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 10.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(shimmerBrush(enabled = enabled)),
    )
}

/** Pill used for states, providers and levels. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(PillRadius))
            .background(color.copy(alpha = 0.14f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.38f)), RoundedCornerShape(PillRadius))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Animated integer counter (used by the badge and stat tiles). */
@Composable
fun AnimatedCount(value: Int, color: Color, style: androidx.compose.ui.text.TextStyle) {
    val animated by animateIntAsState(targetValue = value, animationSpec = tween(Motion.MEDIUM), label = "count")
    Text("$animated", style = style, color = color, fontWeight = FontWeight.Bold)
}


/**
 * Section heading inside a settings card: a tinted icon tile plus an uppercase label.
 * Seven flat labels in a row made the screen read as a list of paragraphs.
 */
@Composable
fun SettingsSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    accent: androidx.compose.ui.graphics.Color = TdColor.TextLow,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = androidx.compose.ui.Modifier.size(15.dp),
            )
        }
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TdColor.TextMid,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


/**
 * Inline result of a field validation (e.g. the TMDB key).
 *
 * Deliberately not a toast: a toast vanishes after ~3 s, cannot be re-read, and reads as a
 * transient error rather than a field-level result. Colour carries the verdict, text the reason.
 */
@Composable
fun ValidationLine(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
