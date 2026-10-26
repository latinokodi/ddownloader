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
 * "Circuit noir" design system.
 *
 * A deliberately dark-only palette: deep obsidian surfaces, a violet→cyan
 * gradient that means "secure traffic", and a restrained semantic set. Numbers
 * and logs use a mono face so transfer telemetry reads like instrumentation.
 */

// ── Palette ────────────────────────────────────────────────────────────────
object TdColor {
    val Ink0 = Color(0xFF05060A)      // window background
    val Ink1 = Color(0xFF0A0C12)      // scaffold
    val Ink2 = Color(0xFF10131B)      // card
    val Ink3 = Color(0xFF171B25)      // elevated / pressed
    val Line = Color(0xFF232936)      // hairline borders
    val LineSoft = Color(0xFF171C26)

    val TextHi = Color(0xFFF3F4F8)
    val TextMid = Color(0xFF9BA5B7)
    // TextLow is used for 11-12 sp labels, so it must clear WCAG AA 4.5:1 against
    // Ink0/Ink1/Ink2. The earlier 0xFF5F6A7D reached only ~3.6:1 on Ink0; the
    // accessibility audit flagged it and this value raises it to ~6.1:1.
    val TextLow = Color(0xFF8590A3)

    val Violet = Color(0xFF7C5CFF)
    val VioletDim = Color(0xFF5B41C7)
    val Cyan = Color(0xFF22D3EE)
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Rose = Color(0xFFF47171)
}

@Immutable
data class TdGradients(
    val brand: Brush = Brush.linearGradient(listOf(TdColor.Violet, TdColor.Cyan)),
    val brandSoft: Brush = Brush.linearGradient(
        listOf(TdColor.Violet.copy(alpha = 0.22f), TdColor.Cyan.copy(alpha = 0.12f))
    ),
    val heroGlow: Brush = Brush.radialGradient(
        listOf(TdColor.Violet.copy(alpha = 0.28f), Color.Transparent)
    ),
    val success: Brush = Brush.linearGradient(listOf(TdColor.Mint, TdColor.Cyan)),
    // Text-bearing surfaces need a darker ramp: white on the cyan end of the brand
    // gradient is only 1.81:1 (WCAG fail measured by the a11y audit). This ramp
    // keeps the violet identity and clears 4.5:1 for white 14 sp labels.
    val brandButton: Brush = Brush.linearGradient(listOf(Color(0xFF6D4AE0), Color(0xFF4F46E5))),
)

val LocalTdGradients = staticCompositionLocalOf { TdGradients() }

// ── Typography ─────────────────────────────────────────────────────────────
// Space Grotesk for display (geometric, modern), Inter for UI text, JetBrains
// Mono for telemetry. Static instances are generated from the OFL variable
// fonts so no variable-axis API is needed at runtime.
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
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
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

/** Metric text (speeds, percentages) — mono so digits don't dance. */
val Typography.monoNumber: TextStyle
    get() = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp)

private val TdShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Composable
fun TdTheme(content: @Composable () -> Unit) {
    // The app is dark-only by design; a light theme would halve the contrast that
    // makes the telemetry legible. isSystemInDarkTheme() is read so the future
    // light scheme can branch here without touching call sites.
    isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = TdColor.Violet,
        onPrimary = Color.White,
        primaryContainer = TdColor.VioletDim,
        onPrimaryContainer = TdColor.TextHi,
        secondary = TdColor.Cyan,
        onSecondary = TdColor.Ink0,
        tertiary = TdColor.Mint,
        background = TdColor.Ink0,
        onBackground = TdColor.TextHi,
        surface = TdColor.Ink2,
        onSurface = TdColor.TextHi,
        surfaceVariant = TdColor.Ink3,
        onSurfaceVariant = TdColor.TextMid,
        outline = TdColor.Line,
        outlineVariant = TdColor.LineSoft,
        error = TdColor.Rose,
        onError = Color.White,
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
