package app.reviewnotes.timing.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import app.reviewnotes.timing.ui.theme.Ink

/**
 * A number that rolls. Characters are aligned from the right (so units stay put when the
 * length changes); each changed digit slides vertically — up when the new value is larger,
 * down when smaller — while non-digit characters swap without motion. On a change the whole
 * number flashes toward [Ink.Up] (rising) or [Ink.Down] (falling) and fades back over 900 ms.
 * The value is parsed from the digits, '.', and a leading '-' / '−' (e.g. "+1.23%", "−0.4").
 */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // Direction of the latest change: +1 up, -1 down, 0 unknown / first composition.
    val previous = remember { arrayOf(text) }
    val direction = remember(text) {
        val d = compareNumeric(previous[0], text)
        previous[0] = text
        d
    }

    val flash = remember { Animatable(0f) }
    var flashDir by remember { mutableStateOf(0) }
    LaunchedEffect(text) {
        if (direction != 0) {
            flashDir = direction
            flash.snapTo(1f)
            flash.animateTo(0f, tween(durationMillis = 900))
        }
    }
    // Read in the draw phase via ColorProducer: the flash never recomposes the digits.
    val latestColor by rememberUpdatedState(color)
    val tint = remember {
        ColorProducer {
            val dir = flashDir
            if (dir == 0) {
                latestColor
            } else {
                lerp(latestColor, if (dir > 0) Ink.Up else Ink.Down, flash.value * 0.85f)
            }
        }
    }

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val n = text.length
        for (i in 0 until n) {
            key(n - i) {
                AnimatedContent(
                    targetState = text[i],
                    modifier = Modifier.clipToBounds(),
                    transitionSpec = { rollTransform(initialState, targetState, direction) },
                    contentAlignment = Alignment.Center,
                    label = "digit",
                ) { ch ->
                    BasicText(text = ch.toString(), style = style, color = tint)
                }
            }
        }
    }
}

private fun rollTransform(from: Char, to: Char, direction: Int): ContentTransform {
    if (!from.isDigit() || !to.isDigit() || direction == 0) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    val up = direction > 0
    val motion = spring<IntOffset>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )
    val enter = slideInVertically(animationSpec = motion) { h -> if (up) h else -h } +
        fadeIn(animationSpec = tween(180))
    val exit = slideOutVertically(animationSpec = motion) { h -> if (up) -h else h } +
        fadeOut(animationSpec = tween(140))
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        targetContentZIndex = 0f,
        sizeTransform = SizeTransform(clip = true),
    )
}

/** +1 if [new] parses larger than [old], -1 if smaller, 0 if equal or unparseable. */
private fun compareNumeric(old: String, new: String): Int {
    if (old == new) return 0
    val a = parseLoose(old) ?: return 0
    val b = parseLoose(new) ?: return 0
    return when {
        b > a -> 1
        b < a -> -1
        else -> 0
    }
}

private fun parseLoose(s: String): Double? {
    val sb = StringBuilder(s.length)
    var negative = false
    for (c in s) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' -> sb.append(c)
            (c == '-' || c == '−') && sb.isEmpty() -> negative = true
        }
    }
    val v = sb.toString().toDoubleOrNull() ?: return null
    return if (negative) -v else v
}

/**
 * Shrinks to 0.96 while pressed, springing back on release. It only observes pointer events
 * (never consumes them), so clickable / selectable on the same element still work; it lets go
 * as soon as something else (e.g. a scroll) consumes the movement.
 */
fun Modifier.pressScale(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.any { it.isConsumed && it.position != it.previousPosition }) break
                    }
                } finally {
                    pressed = false
                }
            }
        }
}
