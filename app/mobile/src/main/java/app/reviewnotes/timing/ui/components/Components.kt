package app.reviewnotes.timing.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type

@Composable
fun T(text: String, style: TextStyle = Type.Body, color: Color = Ink.Text, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

enum class ChipKind { BUY, HOLD, NO, OK, MISS, BONUS, KIND, WARN, SELL }

/** Small rounded label. BUY is a solid red pill, the rest are tinted. */
@Composable
fun Chip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        ChipKind.BUY -> Ink.Up to Color.White
        ChipKind.HOLD -> Ink.Up.copy(alpha = 0.15f) to Ink.Up.copy(alpha = 0.95f)
        ChipKind.NO -> Color.White.copy(alpha = 0.06f) to Ink.Muted
        ChipKind.OK -> Ink.Up.copy(alpha = 0.10f) to Ink.Up
        ChipKind.MISS -> Color.White.copy(alpha = 0.04f) to Ink.Faint
        ChipKind.BONUS -> Ink.Teal.copy(alpha = 0.11f) to Ink.Teal
        ChipKind.KIND -> Ink.Accent.copy(alpha = 0.13f) to Ink.Accent
        ChipKind.WARN -> Ink.Amber.copy(alpha = 0.10f) to Ink.Amber
        ChipKind.SELL -> Ink.Down to Color.Black
    }
    val shape = RoundedCornerShape(50)
    var m = modifier.clip(shape).background(
        if (kind == ChipKind.BUY) Brush.verticalGradient(listOf(Color(0xFFFF7A80), Ink.Up)) else Brush.linearGradient(listOf(bg, bg)),
    )
    if (kind == ChipKind.WARN) m = m.border(1.dp, Ink.Amber.copy(alpha = 0.45f), shape)
    BasicText(
        text, m.padding(horizontal = 9.dp, vertical = 3.dp),
        Type.Caption.copy(
            color = fg,
            fontWeight = if (kind == ChipKind.BUY || kind == ChipKind.SELL || kind == ChipKind.HOLD) FontWeight.SemiBold else FontWeight.Normal,
            textDecoration = if (kind == ChipKind.MISS) TextDecoration.LineThrough else null,
        ),
        maxLines = 1,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/**
 * Segmented control: the selection is a solid white pill with black text that springs between options.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pos = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }   // x, width in px
    val target = pos[selected]
    val x by animateDpAsState(with(density) { (target?.first ?: 0f).toDp() }, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "segX")
    val w by animateDpAsState(with(density) { (target?.second ?: 0f).toDp() }, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "segW")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(3.dp),
    ) {
        if (target != null) {
            Box(
                Modifier
                    .offset(x = x)
                    .width(w)
                    .height(30.dp)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE9E9EF)))),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { k, label ->
                val on = k == selected
                val c by animateColorAsState(if (on) Ink.OnPill else Ink.Muted, label = "segC")
                Box(
                    Modifier
                        .height(30.dp)
                        .onGloballyPositioned { pos[k] = it.positionInParent().x to it.size.width.toFloat() }
                        .clip(shape)
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (k != selected) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(k) }
                        }
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(label, style = Type.Small.copy(color = c, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
                }
            }
        }
    }
}

/** A 40-day sparkline: the line and its 10-day average (dashed). */
@Composable
fun Spark(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier.fillMaxWidth().height(40.dp)) {
    if (points.size < 2) return
    Canvas(modifier) {
        val all = points.flatMap { listOf(it.first, it.second) }.filter { !it.isNaN() }
        if (all.isEmpty()) return@Canvas
        val lo = all.min(); val hi = all.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val sx = size.width / (points.size - 1)
        fun y(v: Double) = (size.height - 3f - ((v - lo) / span * (size.height - 6f))).toFloat()
        val p = Path(); val m = Path()
        var startedM = false
        points.forEachIndexed { i, (a, b) ->
            if (i == 0) p.moveTo(0f, y(a)) else p.lineTo(i * sx, y(a))
            if (!b.isNaN()) { if (!startedM) { m.moveTo(i * sx, y(b)); startedM = true } else m.lineTo(i * sx, y(b)) }
        }
        drawPath(m, Ink.Accent.copy(alpha = 0.8f), style = Stroke(width = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        drawPath(p, Ink.Text, style = Stroke(width = 1.6f))
    }
}

/** A round icon-like text button on glass (e.g. ☆, ‹). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false) {
    val shape = RoundedCornerShape(50)
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .clip(shape)
            .background(if (solid) Brush.verticalGradient(listOf(Color.White, Color(0xFFE9E9EF))) else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.05f))))
            .border(1.dp, Color.White.copy(alpha = if (solid) 0f else 0.12f), shape)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Type.Small.copy(color = if (solid) Ink.OnPill else Ink.Text, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** ✓ / × / ~ mark in a colored circle. */
@Composable
fun Mark(state: Int, modifier: Modifier = Modifier) {   // 2 ok, 1 partial, 0 no
    val c = when (state) { 2 -> Ink.Up; 1 -> Ink.Amber; else -> Ink.Down }
    Box(modifier.width(22.dp).height(22.dp).clip(RoundedCornerShape(50)).background(c), contentAlignment = Alignment.Center) {
        BasicText(when (state) { 2 -> "✓"; 1 -> "~"; else -> "×" }, style = Type.Small.copy(color = Color.Black, fontWeight = FontWeight.Bold))
    }
}
