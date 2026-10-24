package app.reviewnotes.timing.ui.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.LocalGlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private const val Resistance = 0.5f
private val Threshold = 90.dp
private val DropRadius = 22.dp
private val DropMinRadius = 8.dp
private val OverlayHeight = 220.dp

// Point on the drop's circle where the neck joins it: 35° above horizontal.
private const val NeckCos = 0.8192f
private const val NeckSin = 0.5736f

/**
 * Pull-to-refresh with a glass droplet.
 *
 * Over-scrolling the content at the top (it must dispatch nested scroll: LazyColumn,
 * verticalScroll, …) pulls a drop down from the top edge: it grows to 44 dp and hangs on a
 * tapering neck. Past 90 dp of pull it fills with the verdict glow and ticks once (LongPress).
 * Releasing there calls [onRefresh]; while [refreshing] the drop rests below the top edge with a
 * spinning arc, and when [refreshing] turns false it pops (scale 1.25 + fade, 260 ms). Released
 * short of the threshold it springs back. The content follows at half the pull distance.
 * If [onRefresh] doesn't turn [refreshing] on within two frames, the drop pops right away.
 */
@Composable
fun GlassPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val glow = LocalGlow.current
    val refreshingState = rememberUpdatedState(refreshing)
    val onRefreshState = rememberUpdatedState(onRefresh)

    val ctl = remember {
        PullController(
            scope = scope,
            isRefreshing = { refreshingState.value },
            onRefresh = { onRefreshState.value() },
            onArm = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
    }
    ctl.threshold = with(density) { Threshold.toPx() }
    ctl.rest = ctl.threshold
    ctl.maxPull = ctl.threshold * 2f

    // Only a refresh the user pulled for shows the drop; background refreshes (every 10 s while
    // the market is open) must not push the content down.
    LaunchedEffect(refreshing) {
        if (refreshing) {
            if (ctl.holding) ctl.hold()
        } else if (ctl.holding) {
            ctl.pop()
        }
    }

    val armedAlpha by animateFloatAsState(
        targetValue = if (ctl.armed || ctl.holding) 1f else 0f,
        animationSpec = tween(220),
        label = "dropGlow",
    )
    val spinnerAlpha by animateFloatAsState(
        targetValue = if (ctl.holding || ctl.popping) 1f else 0f,
        animationSpec = tween(200),
        label = "spinnerAlpha",
    )
    val spin: State<Float> = if (refreshing && !reduced) {
        rememberInfiniteTransition(label = "dropSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "dropSpinAngle",
        )
    } else {
        remember { mutableFloatStateOf(-90f) }
    }

    val connection = remember(ctl) { ctl.connection() }
    val path = remember { Path() }

    Box(modifier.nestedScroll(connection)) {
        Box(Modifier.graphicsLayer { translationY = ctl.pull * Resistance }) {
            content()
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(OverlayHeight)
                .clipToBounds()
                .drawWithCache {
                    val bigR = DropRadius.toPx()
                    val minR = DropMinRadius.toPx()
                    val circle = Rect(-bigR, -bigR, bigR, bigR)

                    // Everything below is in drop-local space: circle of radius bigR at origin.
                    val base = Color.Black.copy(alpha = 0.35f)
                    val sheen = Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.16f),
                        0.38f to Color.White.copy(alpha = 0.06f),
                        0.72f to Color.White.copy(alpha = 0.025f),
                        1f to Color.White.copy(alpha = 0.07f),
                        start = Offset(-bigR * 0.42f, -bigR * 0.9f),
                        end = Offset(bigR * 0.42f, bigR * 0.9f),
                    )
                    val glowFill = Brush.radialGradient(
                        0f to glow.copy(alpha = 0.45f),
                        0.6f to glow.copy(alpha = 0.16f),
                        1f to glow.copy(alpha = 0.05f),
                        center = Offset(0f, bigR * 0.2f),
                        radius = bigR * 1.1f,
                    )
                    val specCenter = Offset(-bigR * 0.35f, -bigR * 0.5f)
                    val specR = bigR * 0.95f
                    val specular = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.32f),
                        0.45f to Color.White.copy(alpha = 0.08f),
                        1f to Color.Transparent,
                        center = specCenter,
                        radius = specR,
                    )
                    val innerEdge = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.24f),
                        0.4f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                        startY = -bigR,
                        endY = bigR,
                    )
                    val innerStrokes = arrayOf(Stroke(bigR * 0.18f), Stroke(bigR * 0.42f))
                    val rim = Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.85f),
                        0.3f to Color.White.copy(alpha = 0.18f),
                        0.5f to Color.White.copy(alpha = 0.04f),
                        0.72f to Color.White.copy(alpha = 0.12f),
                        1f to Color.White.copy(alpha = 0.5f),
                        start = Offset(-bigR, -bigR),
                        end = Offset(bigR, bigR),
                    )
                    val rimStroke = Stroke(1.5.dp.toPx())
                    val spinR = bigR * 0.42f
                    val spinTopLeft = Offset(-spinR, -spinR)
                    val spinSize = Size(spinR * 2f, spinR * 2f)
                    val spinStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    val spinTrack = Ink.Text.copy(alpha = 0.14f)

                    onDrawBehind {
                        if (ctl.hidden && !ctl.popping) return@onDrawBehind
                        val resting = ctl.holding || ctl.popping
                        // While popping, the drop stays where it was; the content springs back.
                        val pull = if (ctl.popping) ctl.popAnchor else ctl.pull
                        if (pull <= 0.5f) return@onDrawBehind

                        val p = (pull / ctl.threshold).coerceIn(0f, 1f)
                        val r = if (resting) bigR else minR + (bigR - minR) * p
                        // Centre sits in the gap the content leaves (pull × 0.5), hugging the top.
                        val cy = max(pull * Resistance - r * 0.9f, r * 0.55f)
                        val s = r / bigR
                        val stretchY = if (resting) 1f else 1f + 0.10f * p
                        val pop = ctl.popProgress
                        val popScale = 1f + 0.25f * pop
                        val alpha = 1f - pop
                        val sx = s * popScale
                        val sy = s * stretchY * popScale

                        withTransform({
                            translate(left = size.width / 2f, top = cy)
                            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero)
                        }) {
                            // The canvas top edge, in drop-local units.
                            val yTop = -cy / sy
                            path.reset()
                            if (!resting && yTop < -bigR * 0.62f) {
                                val stretch = ((-yTop - bigR) / (3f * bigR)).coerceIn(0f, 1f)
                                buildDrop(path, circle, bigR, yTop, bigR * (0.5f - 0.3f * stretch))
                            } else {
                                path.addOval(circle)
                            }

                            drawPath(path, base, alpha = alpha)
                            drawPath(path, sheen, alpha = alpha)
                            if (armedAlpha > 0.01f) {
                                drawPath(path, glowFill, alpha = alpha * armedAlpha)
                            }
                            clipPath(path) {
                                drawCircle(specular, radius = specR, center = specCenter, alpha = alpha)
                                for (st in innerStrokes) {
                                    drawPath(path, innerEdge, alpha = 0.4f * alpha, style = st)
                                }
                            }
                            drawPath(path, rim, alpha = alpha, style = rimStroke)

                            val sa = spinnerAlpha * alpha
                            if (sa > 0.01f) {
                                drawArc(
                                    color = spinTrack,
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = spinTopLeft,
                                    size = spinSize,
                                    alpha = sa,
                                    style = spinStroke,
                                )
                                drawArc(
                                    color = Ink.Text,
                                    startAngle = spin.value,
                                    sweepAngle = 100f,
                                    useCenter = false,
                                    topLeft = spinTopLeft,
                                    size = spinSize,
                                    alpha = sa,
                                    style = spinStroke,
                                )
                            }
                        }
                    }
                },
        )
    }
}

/**
 * A drop hanging from the canvas top: a neck of half-width [neckHalf] at [yTop] that flows
 * tangentially into the circle (radius [r] at the origin) 35° above its equator, then around
 * the bottom of the circle and back up. One contour, so it fills without seams.
 */
private fun buildDrop(path: Path, circle: Rect, r: Float, yTop: Float, neckHalf: Float) {
    val lx = -r * NeckCos
    val ly = -r * NeckSin
    val k = min(r * 0.6f, (ly - yTop) * 0.5f)
    val midY = yTop + (ly - yTop) * 0.5f
    path.moveTo(-neckHalf, yTop)
    path.cubicTo(-neckHalf, midY, lx + NeckSin * k, ly - NeckCos * k, lx, ly)
    path.arcTo(circle, 215f, -250f, false)
    path.cubicTo(-lx - NeckSin * k, ly - NeckCos * k, neckHalf, midY, neckHalf, yTop)
    path.close()
}

private class PullController(
    private val scope: CoroutineScope,
    private val isRefreshing: () -> Boolean,
    private val onRefresh: () -> Unit,
    private val onArm: () -> Unit,
) {
    var pull by mutableFloatStateOf(0f)
    var popProgress by mutableFloatStateOf(0f)
    var popping by mutableStateOf(false)
    var hidden by mutableStateOf(false)
    var armed by mutableStateOf(false)
    var holding by mutableStateOf(false)

    var threshold = 1f
    var rest = 1f
    var popAnchor = 0f
    var maxPull = 2f

    private var job: Job? = null

    private fun cancelAnim() {
        job?.cancel()
        job = null
    }

    private fun updateArmed() {
        val now = pull >= threshold
        if (now && !armed) onArm()
        armed = now
    }

    private fun springTo(target: Float) {
        cancelAnim()
        val from = pull
        job = scope.launch {
            animate(
                initialValue = from,
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            ) { v, _ -> pull = v }
        }
    }

    fun hold() {
        holding = true
        armed = false
        springTo(rest)
    }

    fun pop() {
        cancelAnim()
        holding = false
        armed = false
        popAnchor = pull
        popping = true
        hidden = true
        job = scope.launch {
            try {
                val from = pull
                val back = launch {
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                    ) { v, _ -> pull = v }
                }
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                ) { v, _ -> popProgress = v }
                popping = false
                back.join()
                pull = 0f
            } finally {
                popping = false
                popProgress = 0f
                hidden = false
            }
        }
    }

    private fun release() {
        val trigger = pull >= threshold && !isRefreshing()
        armed = false
        if (trigger) {
            holding = true
            onRefresh()
            springTo(rest)
            scope.launch {
                withFrameNanos { }
                withFrameNanos { }
                if (holding && !isRefreshing()) pop()
            }
        } else {
            springTo(0f)
        }
    }

    fun connection(): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Scrolling back up while pulled: shrink the pull first.
            if (available.y < 0f && pull > 0f && !holding && !popping &&
                source == NestedScrollSource.UserInput
            ) {
                cancelAnim()
                val next = max(0f, pull + available.y * Resistance)
                val consumed = (next - pull) / Resistance
                pull = next
                updateArmed()
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.y > 0f && source == NestedScrollSource.UserInput &&
                !holding && !popping && !isRefreshing()
            ) {
                cancelAnim()
                pull = min(pull + available.y * Resistance, maxPull)
                updateArmed()
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pull <= 0f || holding || popping) return Velocity.Zero
            release()
            return available
        }
    }
}
