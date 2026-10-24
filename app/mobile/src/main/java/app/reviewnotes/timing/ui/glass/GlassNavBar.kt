package app.reviewnotes.timing.ui.glass

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

private val BarHeight = 62.dp
private val BarInset = 5.dp

/** The droplet's settle spring: a little bouncy, like liquid. */
private val PillSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 380f)

/** Tight spring used while the finger drags the pill, so it trails just behind the finger. */
private val FollowSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 2200f)

private val PillBrush = Brush.verticalGradient(listOf(Ink.Pill, Color(0xFFE2E2E7)))

private val LabelStyle = Type.Small.copy(fontWeight = FontWeight.SemiBold)

/**
 * iOS-26-style floating tab bar: a heavy-glass capsule 16 dp from the screen edges, above the
 * navigation bar, with a white liquid-droplet pill for the selection.
 *
 * - Tap a tab: the pill springs there (dampingRatio 0.62, stiffness 380), stretching with speed.
 * - Drag the pill: it follows the finger with rubber-band resistance at the ends, ticks
 *   (TextHandleMove) on entering each new tab, and on release snaps to the nearest tab
 *   (velocity-projected), calling [onSelect] with a CONFIRM haptic.
 * - Labels turn black exactly where the pill covers them (draw-phase clip, no recomposition).
 * - With [lens] non-null the capsule reports itself as lens 0 for [liquidLensContent].
 */
@Composable
fun GlassNavBar(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    lens: LensState?,
    modifier: Modifier = Modifier,
) {
    val count = items.size
    if (count == 0) return
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected.coerceIn(0, count - 1))
    val currentOnSelect by rememberUpdatedState(onSelect)
    val shape = RoundedCornerShape(percent = 50)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .height(BarHeight)
            .then(if (lens != null) Modifier.reportLens(lens, 0, BarHeight / 2) else Modifier)
            .clip(shape),
    ) {
        val padPx = with(density) { BarInset.toPx() }
        val fullW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
        val tabW = ((fullW - 2f * padPx) / count).coerceAtLeast(1f)
        val tabWDp = with(density) { tabW.toDp() }
        val pillH = BarHeight - BarInset * 2
        val maxX = tabW * (count - 1)

        val pos = remember { Animatable(currentSelected * tabW) }
        var dragging by remember { mutableStateOf(false) }
        val lift by animateFloatAsState(
            targetValue = if (dragging) 1.06f else 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
            label = "pillLift",
        )

        // External selection changes (and size changes) move the pill.
        LaunchedEffect(currentSelected, tabW) {
            if (!dragging) pos.animateTo(currentSelected * tabW, PillSpring)
        }

        val tap: (Int) -> Unit = { i ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch { pos.animateTo(i * tabW, PillSpring) }
            if (i != currentSelected) currentOnSelect(i)
        }

        val settle: (Float) -> Unit = { velocity ->
            val projected = pos.targetValue + velocity * 0.08f
            val idx = nearestIndex(projected, tabW, count)
            dragging = false
            scope.launch { pos.animateTo(idx * tabW, PillSpring, initialVelocity = velocity) }
            if (idx != currentSelected) {
                view.commitHaptic()
                currentOnSelect(idx)
            }
        }

        // 1. glass (its own scope, so tilt updates recompose only the backdrop)
        GlassBackdrop(shape, Modifier.matchParentSize())

        // 2. resting labels
        TabLabels(
            items = items,
            color = Ink.Muted,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = BarInset)
                .clearAndSetSemantics { },
        )

        // 3. the droplet
        Box(
            Modifier
                .offset { IntOffset((padPx + pos.value).roundToInt(), padPx.roundToInt()) }
                .size(tabWDp, pillH)
                .graphicsLayer {
                    val st = stretchOf(pos.velocity)
                    scaleX = (1f + st) * lift
                    scaleY = (1f - st * 0.45f) * lift
                }
                .background(PillBrush, shape),
        )

        // 4. black labels, revealed only where the pill is
        TabLabels(
            items = items,
            color = Ink.OnPill,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = BarInset)
                .clearAndSetSemantics { }
                .drawWithContent {
                    val sx = (1f + stretchOf(pos.velocity)) * lift
                    val center = pos.value + tabW / 2f
                    val half = tabW * sx / 2f
                    clipRect(
                        left = center - half,
                        top = 0f,
                        right = center + half,
                        bottom = size.height,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                },
        )

        // 5. invisible hit targets + accessibility, and the drag gesture over the whole bar
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = BarInset)
                .selectableGroup()
                .pointerInput(count, tabW) {
                    val tracker = VelocityTracker()
                    var raw = 0f
                    var lastIdx = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            raw = pos.value
                            lastIdx = nearestIndex(raw, tabW, count)
                            dragging = true
                        },
                        onDragEnd = { settle(tracker.calculateVelocity().x) },
                        onDragCancel = { settle(0f) },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            raw += dx
                            val target = rubberBand(raw, maxX, tabW)
                            val idx = nearestIndex(target, tabW, count)
                            if (idx != lastIdx) {
                                lastIdx = idx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            scope.launch { pos.animateTo(target, FollowSpring) }
                        },
                    )
                },
        ) {
            items.forEachIndexed { i, label ->
                key(i) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = i == currentSelected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { tap(i) },
                            )
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassBackdrop(shape: Shape, modifier: Modifier) {
    val tilt = rememberTiltLight()
    Box(modifier.heavyGlass(shape, lightOffset = tilt.value))
}

@Composable
private fun TabLabels(items: List<String>, color: Color, modifier: Modifier) {
    val style = remember(color) { LabelStyle.copy(color = color) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        items.forEach { label ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = label,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Horizontal stretch from speed (px/s): width × (1 + min(|v| / 4000, 0.35)). */
private fun stretchOf(velocity: Float): Float = min(abs(velocity) / 4000f, 0.35f)

private fun nearestIndex(x: Float, tabW: Float, count: Int): Int =
    (x / tabW).roundToInt().coerceIn(0, count - 1)

/** Follows the finger inside [0, max]; beyond, resists and saturates at ~28 % of a tab. */
private fun rubberBand(x: Float, max: Float, span: Float): Float {
    val limit = span * 0.28f
    return when {
        x < 0f -> -limit * (1f - exp(x / (limit * 1.6f)))
        x > max -> max + limit * (1f - exp(-(x - max) / (limit * 1.6f)))
        else -> x
    }
}

private fun View.commitHaptic() {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    performHapticFeedback(type)
}
