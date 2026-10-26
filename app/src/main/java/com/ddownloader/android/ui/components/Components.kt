package com.ddownloader.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddownloader.android.ui.icons.AppIcons
import com.ddownloader.android.ui.theme.LocalTdGradients
import com.ddownloader.android.ui.theme.TdColor
import com.ddownloader.android.ui.theme.aurora
import com.ddownloader.android.ui.theme.TdGradients

/** App background: obsidian plane with a soft violet glow behind the header. */
@Composable
fun TdBackground(content: @Composable BoxScope.() -> Unit) {
    // Ambient aurora behind every screen; disabled when the system asks for reduced motion.
    val still = rememberReduceMotion()
    Box(
        Modifier
            .fillMaxSize()
            .aurora(intensity = 0.9f, enabled = !still),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(LocalTdGradients.current.heroGlow)
        )
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = TdColor.Line,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = TdColor.Ink2,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = TdColor.TextLow,
    )
}

/** Small status chip with an optional glyph. */
@Composable
fun StatusPill(
    text: String,
    tint: Color,
    icon: ImageVector? = null,
    filled: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (filled) tint else tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = if (filled) 1f else 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (filled) TdColor.Ink0 else tint,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            color = if (filled) TdColor.Ink0 else tint,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Circular progress with a gradient sweep; children are drawn centred. */
@Composable
fun ProgressRing(
    percent: Int,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    stroke: Dp = 5.dp,
    tint: Brush? = null,
    track: Color = TdColor.Line,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = percent.coerceIn(0, 100).toFloat(),
        animationSpec = tween(600),
        label = "ring",
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2f
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    brush = tint ?: LocalBrandBrush,
                    startAngle = -90f,
                    sweepAngle = 360f * (animated / 100f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

private val LocalBrandBrush: Brush
    get() = Brush.linearGradient(listOf(TdColor.Violet, TdColor.Cyan))

/**
 * Session hero: the status centrepiece. It answers the three questions that
 * matter in this app — is an account linked, is work running, and where does the
 * output land — using the same concentric-ring language as the rest of the theme.
 */
@Composable
fun SessionHero(
    activeTransfers: Int,
    queuedTransfers: Int,
    completedTransfers: Int,
    hasDebrid: Boolean,
    onOpenDownloads: () -> Unit,
) {
    val gradients = LocalTdGradients.current
    val running = activeTransfers > 0
    val accent = when {
        running -> TdColor.Cyan
        hasDebrid -> TdColor.Mint
        else -> TdColor.Amber
    }

    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "hero")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "pulse",
    )

    GlassCard(borderColor = accent.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = if (running && !reduceMotion) rotation else 0f
                            scaleX = if (running && !reduceMotion) pulse else 1f
                            scaleY = if (running && !reduceMotion) pulse else 1f
                        }
                ) {
                    val sw = 3.dp.toPx()
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(accent.copy(alpha = 0.05f), accent, accent.copy(alpha = 0.05f))
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(sw, cap = StrokeCap.Round),
                        topLeft = Offset(sw / 2, sw / 2),
                        size = Size(size.width - sw, size.height - sw),
                    )
                    drawCircle(brush = gradients.heroGlow, radius = size.minDimension / 2.4f)
                }
                Icon(
                    imageVector = if (running) AppIcons.CloudDownload else AppIcons.VerifiedUser,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        running -> "TRANSFERRING"
                        hasDebrid -> "SECURE SESSION"
                        else -> "READY TO SET UP"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        running -> "$activeTransfers running" + if (queuedTransfers > 0) " • $queuedTransfers queued" else ""
                        hasDebrid -> "TorBox / Real-Debrid linked"
                        else -> "Link a debrid account in Settings"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = TdColor.TextHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill("HTTPS only", TdColor.Cyan, AppIcons.Shield)
                    StatusPill("$completedTransfers done", TdColor.TextMid, AppIcons.CheckCircle)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Downloads",
                icon = AppIcons.Download,
                onClick = onOpenDownloads,
            )
            Spacer(Modifier.weight(1f))
            StatusPill("tokens in Keystore", TdColor.Mint, AppIcons.VerifiedUser, filled = true)
        }
    }
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val gradients = LocalTdGradients.current
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) gradients.brandButton else Brush.linearGradient(listOf(TdColor.Ink3, TdColor.Ink3)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) Color.White else TdColor.TextLow,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(
                text = text,
                color = if (enabled) Color.White else TdColor.TextLow,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(TdColor.Ink3)
            .border(1.dp, TdColor.Line, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) TdColor.TextHi else TdColor.TextLow,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            color = if (enabled) TdColor.TextHi else TdColor.TextLow,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun TdTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium) },
        placeholder = placeholder?.let {
            { Text(it, color = TdColor.TextLow, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TdColor.TextHi,
            unfocusedTextColor = TdColor.TextHi,
            focusedBorderColor = TdColor.Violet,
            unfocusedBorderColor = TdColor.Line,
            focusedLabelColor = TdColor.Violet,
            unfocusedLabelColor = TdColor.TextLow,
            cursorColor = TdColor.Cyan,
            focusedContainerColor = TdColor.Ink1,
            unfocusedContainerColor = TdColor.Ink1,
        ),
    )
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = TdColor.TextHi)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = TdColor.TextLow)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = TdColor.Violet,
                uncheckedThumbColor = TdColor.TextMid,
                uncheckedTrackColor = TdColor.Ink3,
                uncheckedBorderColor = TdColor.Line,
            ),
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(TdColor.Ink2)
                .border(1.dp, TdColor.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TdColor.Violet, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = TdColor.TextHi)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = TdColor.TextMid,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** Key/value telemetry row with a mono value. */
@Composable
fun MetricRow(label: String, value: String, valueColor: Color = TdColor.TextHi) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = TdColor.TextLow)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = valueColor,
        )
    }
}

/** Small dot used for live/status indicators. */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun DividerLine(color: Color = TdColor.LineSoft) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * Honours the system "remove animations" accessibility setting
 * (Settings.Global.ANIMATOR_DURATION_SCALE == 0). WCAG 2.2 / platform guidance:
 * decorative motion must be suppressible; the hero otherwise animates forever.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}