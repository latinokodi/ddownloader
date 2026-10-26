package com.ddownloader.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddownloader.android.R

/**
 * "Studio" design system.
 *
 * Near-black, warm-neutral surfaces with a single amber accent and oversized type. Almost no
 * chrome: no borders around everything, no gradient fills, no glow. Structure comes from spacing,
 * hairlines and scale — the way a print layout works — instead of stacked rounded cards.
 *
 * Deliberately unlike the earlier blue-grey "app" look: this is a dark studio workspace built for a
 * landscape window, with a left rail and two-pane screens.
 */

// ── Palette ────────────────────────────────────────────────────────────────
object TdColor {
    val Ink0 = Color(0xFF080808)      // page
    val Ink1 = Color(0xFF0F0F0F)      // panel
    val Ink2 = Color(0xFF151515)      // raised panel
    val Ink3 = Color(0xFF1C1C1C)      // input / pressed
    val Line = Color(0xFF262626)      // hairline
    val LineSoft = Color(0xFF191919)

    val TextHi = Color(0xFFF5F3EE)    // warm white
    val TextMid = Color(0xFFA8A49C)
    val TextLow = Color(0xFF7C776E)   // ~4.6:1 on Ink1

    /** The one accent. Actions, live state, focus. */
    val Primary = Color(0xFFF5A524)   // amber
    val PrimaryDim = Color(0xFFB87A12)
    /** Secondary signal: lime, used for success and "cached/ready". */
    val Secondary = Color(0xFFB8D96B)

    // Legacy names so the codebase keeps compiling against one palette.
    val Violet = Secondary
    val VioletDim = Color(0xFF7E9648)
    val Cyan = Primary
    val Mint = Color(0xFF9BD45B)
    val Amber = Color(0xFFF5A524)
    val Rose = Color(0xFFF06A5F)
}

@Immutable
data class TdGradients(
    // In a studio palette a gradient is noise: brand marks are flat amber, buttons are flat fill.
    val brand: Brush = Brush.linearGradient(listOf(TdColor.Primary, TdColor.Primary)),
    val brandSoft: Brush = Brush.linearGradient(
        listOf(TdColor.Primary.copy(alpha = 0.16f), Color.Transparent),
    ),
    val heroGlow: Brush = Brush.radialGradient(
        listOf(TdColor.Primary.copy(alpha = 0.10f), Color.Transparent),
    ),
    val success: Brush = Brush.linearGradient(listOf(TdColor.Mint, TdColor.Secondary)),
    // Amber fill with near-black text: 9.3:1, so 14 sp labels are comfortably AA.
    val brandButton: Brush = Brush.linearGradient(listOf(TdColor.Primary, TdColor.Primary)),
)

val LocalTdGradients = staticCompositionLocalOf { TdGradients() }

// ── Typography ─────────────────────────────────────────────────────────────
// Oversized headings, tight tracking, small mono eyebrows. One family, strong weight contrast.
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val JBMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private val TdTypography = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.6).sp),
    displayMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 1.1.sp),
)

/** Metric text (speeds, percentages) — mono so digits don't dance. */
val Typography.monoNumber: TextStyle
    get() = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp)

// Studio shapes: near-square. Rounded-heavy surfaces were the "app" look.
private val TdShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
)

@Composable
fun TdTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = TdColor.Primary,
        onPrimary = Color(0xFF0A0A0A),
        primaryContainer = TdColor.PrimaryDim,
        onPrimaryContainer = TdColor.TextHi,
        secondary = TdColor.Secondary,
        onSecondary = Color(0xFF0A0A0A),
        tertiary = TdColor.Mint,
        background = TdColor.Ink0,
        onBackground = TdColor.TextHi,
        surface = TdColor.Ink1,
        onSurface = TdColor.TextHi,
        surfaceVariant = TdColor.Ink2,
        onSurfaceVariant = TdColor.TextMid,
        outline = TdColor.Line,
        outlineVariant = TdColor.LineSoft,
        error = TdColor.Rose,
        onError = Color(0xFF0A0A0A),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalTdGradients provides TdGradients()) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TdTypography,
            shapes = TdShapes,
            content = content,
        )
    }
}
