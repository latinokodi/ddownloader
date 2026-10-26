package com.ddownloader.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * One place for motion and surface effects.
 *
 * Rules the whole UI follows:
 *  • Durations are short (140/260/420 ms). A download client must never feel like it lags.
 *  • Anything that responds to touch scales down slightly and springs back — no ripple soup.
 *  • Loading is a shimmer of the same shape as the content it replaces, never a spinner in a
 *    list (spinners are reserved for a single in-flight action).
 *  • `rememberReduceMotion()` (system animator scale) disables the ambient animation.
 */
object Motion {
    const val FAST = 140
    const val MEDIUM = 260
    const val SLOW = 420

    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasized = CubicBezierEasing(0.05f, 0.75f, 0.1f, 1f)

    fun <T> springSoft() = spring<T>(dampingRatio = 0.78f, stiffness = 380f)
    fun <T> springSnappy() = spring<T>(dampingRatio = 0.62f, stiffness = 520f)
}

/** Scale-on-press. Compose has no built-in equivalent that composes with our own indication. */
@Composable
fun Modifier.pressEffect(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.965f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(Motion.FAST) else spring(dampingRatio = 0.45f, stiffness = 520f),
        label = "press-scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Shimmer brush for skeletons: a moving highlight band over a flat base.
 * [enabled] keeps the brush static when the system asks for reduced motion.
 */
@Composable
fun shimmerBrush(
    base: Color = TdColor.Ink2,
    highlight: Color = TdColor.Line,
    enabled: Boolean = true,
): Brush {
    if (!enabled) return Brush.linearGradient(listOf(base, base))
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -420f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "shimmer-shift",
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(shift, 0f),
        end = Offset(shift + 420f, 420f),
    )
}

/**
 * Ambient aurora behind the app's hero surfaces: three slow, offset radial gradients in the brand
 * hues. Purely decorative, so it is drawn once and animated by a single float.
 */
@Composable
fun Modifier.aurora(intensity: Float = 1f, enabled: Boolean = true): Modifier {
    // Studio backdrop: flat near-black with one barely-there vertical wash. No glow, no colour.
    return this.background(
        Brush.verticalGradient(
            listOf(TdColor.Ink1.copy(alpha = 0.55f * intensity), TdColor.Ink0),
        ),
    )
}

/** Standard card radius used everywhere, so surfaces read as one system. */
val CardRadius = 8.dp
val PillRadius = 3.dp
