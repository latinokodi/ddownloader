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
 * "Graphite" design system.
 *
 * A product tool, not a poster: neutral graphite surfaces, hairline borders, one azure accent that
 * means "primary action", and neutral signal colours. Earlier revisions leaned on a violet→cyan
 * gradient everywhere, which read as a game launcher; the accent is now reserved for things you can
 * act on, and hierarchy comes from type and surface steps instead of colour noise.
 *
 * Dark-only by design: the telemetry (speeds, ETAs, logs) needs the contrast.
 */

// ── Palette ────────────────────────────────────────────────────────────────
object TdColor {
    val Ink0 = Color(0xFF0D1117)      // window background
    val Ink1 = Color(0xFF121821)      // scaffold / inset
    val Ink2 = Color(0xFF171E27)      // card
    val Ink3 = Color(0xFF1F2732)      // input, elevated, pressed
    val Line = Color(0xFF27313D)      // hairline border
    val LineSoft = Color(0xFF1B232C)  // subtle divider

    val TextHi = Color(0xFFE9EFF6)
    val TextMid = Color(0xFF9FAAB8)
    // 11-12 sp labels must clear WCAG AA 4.5:1 on Ink0/Ink1/Ink2 — this is ~4.9:1 on Ink2.
    val TextLow = Color(0xFF7E8B9B)

    /** Primary accent: azure. Reserved for actions and live state. */
    val Primary = Color(0xFF4C82F7)
    val PrimaryDim = Color(0xFF2C5AC9)
    /** Secondary accent: indigo, for brand marks and provider identity. */
    val Secondary = Color(0xFF6E63F1)

    // Legacy names kept so the whole codebase keeps compiling against one palette.
    val Violet = Secondary
    val VioletDim = Color(0xFF4E46B8)
    val Cyan = Primary
    val Mint = Color(0xFF33B98C)
    val Amber = Color(0xFFDFA23C)
    val Rose = Color(0xFFE0655F)
}

@Immutable
data class TdGradients(
    val brand: Brush = Brush.linearGradient(listOf(TdColor.Secondary, TdColor.Primary)),
    val brandSoft: Brush = Brush.linearGradient(
        listOf(TdColor.Primary.copy(alpha = 0.14f), Color.Transparent),
    ),
    val heroGlow: Brush = Brush.radialGradient(
        listOf(TdColor.Primary.copy(alpha = 0.16f), Color.Transparent),
    ),
    val success: Brush = Brush.linearGradient(listOf(TdColor.Mint, TdColor.Primary)),
    // Text-bearing button ramp: white on #3D6FE0 is ~5.1:1, so 14 sp labels stay AA.
    val brandButton: Brush = Brush.linearGradient(listOf(Color(0xFF2C5AC9), Color(0xFF3D6FE0))),
)

val LocalTdGradients = staticCompositionLocalOf { TdGradients() }

// ── Typography ─────────────────────────────────────────────────────────────
// One UI face (Inter) for everything a user reads; JetBrains Mono only where digits must not
// dance (labels, telemetry, logs). The display face is no longer a different family: a hero that
// shouts in a geometric display font is the fastest way to look like a demo app.
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
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.9).sp),
    displayMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.5.sp),
)

/** Metric text (speeds, percentages) — mono so digits don't dance. */
val Typography.monoNumber: TextStyle
    get() = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp)

// Tighter radii: 18-24 dp on every surface is what makes an app read as "mobile demo".
private val TdShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

@Composable
fun TdTheme(content: @Composable () -> Unit) {
    // Dark-only by design; isSystemInDarkTheme() is read so a light scheme can branch here later
    // without touching call sites.
    isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = TdColor.Primary,
        onPrimary = Color.White,
        primaryContainer = TdColor.PrimaryDim,
        onPrimaryContainer = TdColor.TextHi,
        secondary = TdColor.Secondary,
        onSecondary = Color.White,
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
