package app.reviewnotes.timing.ui.glass

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.LocalGlow
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * True when the system "Animator duration scale" is 0 (developer options / accessibility
 * "Remove animations"). Every looping or sensor-driven effect in this package freezes then.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (_: Throwable) {
            1f
        }
        scale == 0f
    }
}

/**
 * The black stage: one big soft light in the verdict color (top-left), a cool blue rim light
 * (top-right) and two very faint violet / teal lights lower down. Lights drift and the big one
 * breathes; all of it freezes under reduced motion.
 *
 * Animated values are only read inside the draw lambda, so the stage redraws every frame but
 * never recomposes. Brushes are built once per size / glow color in drawWithCache and placed
 * with canvas transforms, so nothing is allocated per frame.
 */
@Composable
fun Stage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduced = rememberReducedMotion()
    val glow = animateColorAsState(
        targetValue = LocalGlow.current,
        animationSpec = tween(durationMillis = 1000),
        label = "stageGlow",
    )

    val bigDrift: State<Float>
    val coolDrift: State<Float>
    val violetDrift: State<Float>
    val tealDrift: State<Float>
    val breathe: State<Float>
    if (reduced) {
        bigDrift = remember { mutableFloatStateOf(0f) }
        coolDrift = remember { mutableFloatStateOf(0f) }
        violetDrift = remember { mutableFloatStateOf(0f) }
        tealDrift = remember { mutableFloatStateOf(0f) }
        breathe = remember { mutableFloatStateOf(0.5f) }
    } else {
        val t = rememberInfiniteTransition(label = "stage")
        bigDrift = t.loop(periodMs = 41_000, label = "bigDrift")
        coolDrift = t.loop(periodMs = 29_000, label = "coolDrift")
        violetDrift = t.loop(periodMs = 47_000, label = "violetDrift")
        tealDrift = t.loop(periodMs = 34_000, label = "tealDrift")
        breathe = t.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.65f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3_500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathe",
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val longSide = max(w, h)
                val glowColor = glow.value // read here: cache rebuilds only while the color animates

                // Each brush is centred on the origin; placement happens with a transform.
                val bigR = longSide * 0.78f
                val bigBrush = Brush.radialGradient(
                    0f to glowColor,
                    0.22f to glowColor.copy(alpha = 0.6f),
                    0.5f to glowColor.copy(alpha = 0.18f),
                    0.78f to glowColor.copy(alpha = 0.04f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = bigR,
                )
                val coolR = w * 0.62f
                val coolBrush = Brush.radialGradient(
                    0f to Ink.CoolLight.copy(alpha = 0.42f),
                    0.4f to Ink.CoolLight.copy(alpha = 0.14f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = coolR,
                )
                val violetR = w * 0.75f
                val violetBrush = Brush.radialGradient(
                    0f to Ink.Violet.copy(alpha = 0.10f),
                    0.5f to Ink.Violet.copy(alpha = 0.035f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = violetR,
                )
                val tealR = w * 0.7f
                val tealBrush = Brush.radialGradient(
                    0f to Ink.Teal.copy(alpha = 0.075f),
                    0.5f to Ink.Teal.copy(alpha = 0.025f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = tealR,
                )
                // A gentle floor shadow keeps the bottom of the stage pure black.
                val floor = Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                )

                onDrawBehind {
                    drawRect(Ink.Stage)

                    val a = bigDrift.value * TWO_PI
                    light(
                        brush = bigBrush,
                        radius = bigR,
                        x = w * (0.12f + 0.045f * sin(a)),
                        y = h * (0.04f + 0.03f * cos(a * 2f)),
                        scale = 1f + 0.08f * sin(a + 1.3f),
                        alpha = breathe.value,
                    )
                    val c = coolDrift.value * TWO_PI
                    light(
                        brush = coolBrush,
                        radius = coolR,
                        x = w * (1.02f + 0.04f * cos(c)),
                        y = h * (0.01f + 0.025f * sin(c)),
                        scale = 1f + 0.07f * cos(c + 0.7f),
                        alpha = 1f,
                    )
                    val v = violetDrift.value * TWO_PI
                    light(
                        brush = violetBrush,
                        radius = violetR,
                        x = w * (0.88f + 0.06f * sin(v)),
                        y = h * (0.62f + 0.04f * cos(v)),
                        scale = 1f + 0.08f * sin(v + 2.1f),
                        alpha = 1f,
                    )
                    val te = tealDrift.value * TWO_PI
                    light(
                        brush = tealBrush,
                        radius = tealR,
                        x = w * (0.08f + 0.05f * cos(te)),
                        y = h * (0.9f + 0.03f * sin(te)),
                        scale = 1f + 0.08f * cos(te + 0.4f),
                        alpha = 1f,
                    )
                    drawRect(floor)
                }
            },
        content = content,
    )
}

private const val TWO_PI = 6.2831855f

@Composable
private fun InfiniteTransition.loop(periodMs: Int, label: String): State<Float> =
    animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = label,
    )

/** Draws an origin-centred radial brush at (x, y), scaled, without allocating. */
private fun DrawScope.light(
    brush: Brush,
    radius: Float,
    x: Float,
    y: Float,
    scale: Float,
    alpha: Float,
) {
    withTransform({
        translate(left = x, top = y)
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = brush,
            radius = radius,
            center = Offset.Zero,
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}
