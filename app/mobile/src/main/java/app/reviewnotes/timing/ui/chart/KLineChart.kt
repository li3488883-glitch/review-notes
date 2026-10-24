package app.reviewnotes.timing.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.data.Bar
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ───────────────────────────── Public API ─────────────────────────────

enum class BandState { BUY, HOLD, NO }

data class ChartMarker(val index: Int, val text: String, val color: Color, val above: Boolean)

data class LineSeries(val values: DoubleArray, val color: Color, val width: Float = 1.2f, val dashed: Boolean = false)

enum class LowerPane { Volume, Oscillator }

/**
 * Candlestick chart with pinch-zoom, pan and a long-press crosshair.
 * Red = up, green = down (A 股习惯). Background is transparent.
 */
@Composable
fun KLineChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    lines: List<LineSeries> = emptyList(),
    bands: List<BandState?>? = null,
    showBands: Boolean = true,
    markers: List<ChartMarker> = emptyList(),
    focusIndex: Int? = null,
    initialVisible: Int = 120,
    lowerPane: LowerPane = LowerPane.Volume,
    oscillator: DoubleArray? = null,
    onCrosshair: (Int?) -> Unit = {},
) {
    val tm = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val dash = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f) }
    }
    val vp = remember { KViewport() }
    val cross = remember { CrossState() }
    val path = remember { Path() }
    val haptic = rememberUpdatedState(LocalHapticFeedback.current)
    val onCross = rememberUpdatedState(onCrosshair)
    val styles = remember { ChartStyles() }
    val axisSample = remember(bars) {
        var m = 0.0
        for (b in bars) if (b.h.isFinite() && b.h > m) m = b.h
        fmtNum(m, if (m in 0.0..2.0) 3 else 2)
    }

    // Runs after composition and before draw: keeps zoom/scroll across live updates.
    SideEffect { vp.sync(bars.size, focusIndex, initialVisible) }

    Canvas(
        modifier = modifier
            .pointerInput(vp, cross) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (cross.active) return@detectTransformGestures
                    val n = vp.n
                    if (n <= 0) return@detectTransformGestures
                    val plotW = max(1f, size.width - vp.axisW)
                    if (zoom != 1f && zoom > 0f) {
                        val frac = (centroid.x / plotW).coerceIn(0f, 1f)
                        val anchor = vp.leftEdge() + frac * vp.visible
                        vp.visible = vp.visible / zoom
                        vp.clampVisible()
                        val newLeft = anchor - frac * vp.visible
                        vp.rightOffset = n + RIGHT_PAD - (newLeft + vp.visible)
                    }
                    val barW = plotW / vp.visible
                    vp.rightOffset += pan.x / barW
                    vp.clamp()
                }
            }
            .pointerInput(vp, cross) {
                fun indexAt(x: Float): Int {
                    val n = vp.n
                    if (n <= 0) return -1
                    val plotW = max(1f, size.width - vp.axisW)
                    val barW = plotW / vp.visible
                    return floor(vp.leftEdge() + x / barW).toInt().coerceIn(0, n - 1)
                }
                fun release() {
                    if (cross.active) {
                        cross.active = false
                        cross.index = -1
                        onCross.value(null)
                    }
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        val idx = indexAt(p.x)
                        if (idx >= 0) {
                            cross.x = p.x
                            cross.y = p.y
                            cross.index = idx
                            cross.active = true
                            haptic.value.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCross.value(idx)
                        }
                    },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                    onDrag = { change, _ ->
                        if (cross.active) {
                            val p = change.position
                            cross.x = p.x
                            cross.y = p.y
                            val idx = indexAt(p.x)
                            if (idx >= 0 && idx != cross.index) {
                                cross.index = idx
                                haptic.value.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCross.value(idx)
                            }
                        }
                    },
                )
            },
    ) {
        drawKLine(
            bars = bars, lines = lines, bands = bands, showBands = showBands, markers = markers,
            focusIndex = focusIndex, lowerPane = lowerPane, oscillator = oscillator,
            vp = vp, cross = cross, tm = tm, path = path, dash = dash, styles = styles, axisSample = axisSample,
        )
    }
}

/**
 * 分时 chart: price line, dashed average line, dashed 昨收 line and a volume histogram.
 * The x axis always spans the full session (241 minute slots: 09:30–11:30, 13:01–15:00).
 */
@Composable
fun TickChart(
    times: List<String>,
    prices: DoubleArray,
    avg: DoubleArray,
    volumes: DoubleArray,
    prevClose: Double,
    modifier: Modifier = Modifier,
    onCrosshair: (Int?) -> Unit = {},
) {
    val tm = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val dash = remember(density) {
        with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f) }
    }
    val cross = remember { CrossState() }
    val geo = remember { AxisHolder() }
    val path = remember { Path() }
    val styles = remember { ChartStyles() }
    val haptic = rememberUpdatedState(LocalHapticFeedback.current)
    val onCross = rememberUpdatedState(onCrosshair)
    val slots = remember(times) {
        IntArray(times.size) { i ->
            val s = sessionSlot(times[i])
            if (s < 0) min(i, TICK_SLOTS - 1) else s
        }
    }
    val slotsState = rememberUpdatedState(slots)
    val countState = rememberUpdatedState(min(times.size, prices.size))

    Canvas(
        modifier = modifier.pointerInput(cross, geo) {
            fun indexAt(x: Float): Int {
                val sl = slotsState.value
                val cnt = min(sl.size, countState.value)
                if (cnt <= 0) return -1
                val plotW = max(1f, size.width - geo.axisW)
                val target = x / plotW * TICK_SLOTS - 0.5f
                var best = 0
                var bestD = Float.MAX_VALUE
                for (i in 0 until cnt) {
                    val d = abs(sl[i] - target)
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                return best
            }
            fun release() {
                if (cross.active) {
                    cross.active = false
                    cross.index = -1
                    onCross.value(null)
                }
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { p ->
                    val idx = indexAt(p.x)
                    if (idx >= 0) {
                        cross.x = p.x
                        cross.y = p.y
                        cross.index = idx
                        cross.active = true
                        haptic.value.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCross.value(idx)
                    }
                },
                onDragEnd = { release() },
                onDragCancel = { release() },
                onDrag = { change, _ ->
                    if (cross.active) {
                        val p = change.position
                        cross.x = p.x
                        cross.y = p.y
                        val idx = indexAt(p.x)
                        if (idx >= 0 && idx != cross.index) {
                            cross.index = idx
                            haptic.value.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCross.value(idx)
                        }
                    }
                },
            )
        },
    ) {
        drawTick(
            times = times, prices = prices, avg = avg, volumes = volumes, prevClose = prevClose,
            slots = slots, cross = cross, geo = geo, tm = tm, path = path, dash = dash, styles = styles,
        )
    }
}

// ───────────────────────────── State ─────────────────────────────

private const val MIN_VISIBLE = 20f
private const val MAX_VISIBLE = 600
private const val RIGHT_PAD = 3
private const val MAIN_FRAC = 0.72f
private const val LOWER_TOP_FRAC = 0.78f
private const val TICK_SLOTS = 241

/** Viewport in bar-index units. rightOffset = 0 means pinned to the right edge (with RIGHT_PAD empty bars). */
@Stable
private class KViewport {
    var visible by mutableFloatStateOf(120f)
    var rightOffset by mutableFloatStateOf(0f)

    // Plain fields: not observed, only read by gestures and written by sync/draw.
    var n: Int = 0
    var axisW: Float = 0f
    private var initialized = false
    private var lastFocus: Int? = null

    fun rightEdge(): Float = n + RIGHT_PAD - rightOffset
    fun leftEdge(): Float = rightEdge() - visible

    fun clampVisible() {
        val vMax = max(MIN_VISIBLE, min(n, MAX_VISIBLE).toFloat())
        visible = visible.coerceIn(MIN_VISIBLE, vMax)
    }

    fun clamp() {
        clampVisible()
        val maxOff = max(0f, n + RIGHT_PAD - visible)
        rightOffset = rightOffset.coerceIn(0f, maxOff)
    }

    fun sync(newN: Int, focus: Int?, initialVisible: Int) {
        if (newN <= 0) {
            n = 0
            initialized = false
            return
        }
        if (!initialized) {
            n = newN
            visible = initialVisible.toFloat()
            rightOffset = 0f
            clamp()
            centerOn(focus)
            lastFocus = focus
            initialized = true
            return
        }
        if (newN != n) {
            val pinned = rightOffset < 0.5f
            val delta = newN - n
            n = newN
            rightOffset = if (pinned) 0f else rightOffset + delta
            clamp()
        }
        if (focus != lastFocus) {
            lastFocus = focus
            centerOn(focus)
        }
    }

    private fun centerOn(focus: Int?) {
        if (focus == null || n <= 0) return
        val f = focus.coerceIn(0, n - 1)
        val right = f + 0.5f + visible / 2f
        rightOffset = n + RIGHT_PAD - right
        clamp()
    }
}

@Stable
private class CrossState {
    var active by mutableStateOf(false)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var index by mutableIntStateOf(-1)
}

private class AxisHolder {
    var axisW: Float = 0f
}

private class ChartStyles {
    val axis: TextStyle = Type.Caption.copy(color = Ink.Faint, fontFeatureSettings = "tnum")
    val marker: TextStyle = Type.Caption.copy(fontWeight = FontWeight.SemiBold)
    val pill: TextStyle = Type.Caption.copy(color = Ink.OnPill, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}

// ───────────────────────────── K-line drawing ─────────────────────────────

private fun DrawScope.drawKLine(
    bars: List<Bar>,
    lines: List<LineSeries>,
    bands: List<BandState?>?,
    showBands: Boolean,
    markers: List<ChartMarker>,
    focusIndex: Int?,
    lowerPane: LowerPane,
    oscillator: DoubleArray?,
    vp: KViewport,
    cross: CrossState,
    tm: TextMeasurer,
    path: Path,
    dash: PathEffect,
    styles: ChartStyles,
    axisSample: String,
) {
    val w = size.width
    val h = size.height
    val axisW = tm.measure(axisSample, styles.axis).size.width + 6.dp.toPx()
    vp.axisW = axisW
    val plotW = max(1f, w - axisW)
    val mainTop = 0f
    val mainBottom = h * MAIN_FRAC
    val lowerTop = h * LOWER_TOP_FRAC
    val lowerBottom = h
    val n = bars.size

    // Pane separators even when empty.
    drawLine(Ink.Hairline, Offset(0f, mainBottom), Offset(plotW, mainBottom), strokeWidth = 1f)
    drawLine(Ink.Hairline, Offset(0f, lowerTop), Offset(plotW, lowerTop), strokeWidth = 1f)
    if (n == 0) return

    val visible = vp.visible
    val rightEdge = n + RIGHT_PAD - vp.rightOffset
    val left = rightEdge - visible
    val barW = plotW / visible
    val first = max(0, floor(left).toInt())
    val last = min(n - 1, ceil(rightEdge).toInt() - 1)

    // ── Price range over visible bars and lines ──
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (i in first..last) {
        val b = bars[i]
        if (b.l.isFinite() && b.l < lo) lo = b.l
        if (b.h.isFinite() && b.h > hi) hi = b.h
    }
    for (ls in lines) {
        val vs = ls.values
        val end = min(last, vs.size - 1)
        for (i in first..end) {
            val v = vs[i]
            if (v.isFinite()) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
    }
    if (!lo.isFinite() || !hi.isFinite()) {
        lo = 0.0
        hi = 1.0
    }
    if (hi - lo < 1e-9) {
        val c = hi
        lo = c - max(abs(c) * 0.01, 0.01)
        hi = c + max(abs(c) * 0.01, 0.01)
    }
    val pad = (hi - lo) * 0.06
    lo -= pad
    hi += pad
    val mainH = mainBottom - mainTop
    val range = hi - lo

    fun xOf(i: Int): Float = (i + 0.5f - left) * barW
    fun yOf(p: Double): Float = (mainBottom - (p - lo) / range * mainH).toFloat()

    // ── Bands (under everything), merged into runs ──
    if (showBands && bands != null) {
        val lastB = min(last, bands.size - 1)
        var i = first
        while (i <= lastB) {
            val s = bands[i]
            var j = i
            while (j + 1 <= lastB && bands[j + 1] == s) j++
            if (s != null) {
                val x0 = max(0f, (i - left) * barW)
                val x1 = min(plotW, (j + 1 - left) * barW)
                if (x1 > x0) {
                    drawRect(bandColor(s), Offset(x0, mainTop), Size(x1 - x0, lowerBottom - mainTop))
                }
            }
            i = j + 1
        }
    }

    // ── Grid + right axis labels ──
    val step = niceStep(range, 4)
    val dec = if (step < 0.01) 3 else 2
    var tick = ceil(lo / step) * step
    var guard = 0
    while (tick <= hi && guard < 12) {
        val y = yOf(tick)
        drawLine(Ink.Hairline, Offset(0f, y), Offset(plotW, y), strokeWidth = 1f)
        val layout = tm.measure(fmtNum(tick, dec), styles.axis)
        val th = layout.size.height.toFloat()
        val ty = clampSafe(y - th / 2f, mainTop, mainBottom - th)
        drawText(layout, topLeft = Offset(w - layout.size.width, ty))
        tick += step
        guard++
    }

    // ── Main pane: candles, lines, markers ──
    clipRect(left = 0f, top = mainTop, right = plotW, bottom = mainBottom) {
        val bodyW = max(1f, barW * 0.7f)
        val wickW = max(1f, min(1.dp.toPx(), bodyW))
        for (i in first..last) {
            val b = bars[i]
            if (!(b.o.isFinite() && b.h.isFinite() && b.l.isFinite() && b.c.isFinite())) continue
            val col = if (b.c >= b.o) Ink.Up else Ink.Down
            val cx = xOf(i)
            val yH = yOf(b.h)
            val yL = yOf(b.l)
            val yO = yOf(b.o)
            val yC = yOf(b.c)
            drawRect(col, Offset(cx - wickW / 2f, yH), Size(wickW, max(1f, yL - yH)))
            drawRect(col, Offset(cx - bodyW / 2f, min(yO, yC)), Size(bodyW, max(1f, abs(yO - yC))))
        }

        for (ls in lines) {
            val vs = ls.values
            val start = max(0, first - 1)
            val end = min(last + 1, vs.size - 1)
            if (end < start) continue
            path.reset()
            var pen = false
            var any = false
            for (i in start..end) {
                val v = vs[i]
                if (v.isFinite()) {
                    val x = xOf(i)
                    val y = yOf(v)
                    if (pen) {
                        path.lineTo(x, y)
                        any = true
                    } else {
                        path.moveTo(x, y)
                    }
                    pen = true
                } else {
                    pen = false
                }
            }
            if (any) {
                drawPath(
                    path = path,
                    color = ls.color,
                    style = Stroke(
                        width = ls.width.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (ls.dashed) dash else null,
                    ),
                )
            }
        }

        if (markers.isNotEmpty()) {
            val gap = 2.dp.toPx()
            val tri = 4.dp.toPx()
            for (k in markers.indices) {
                val m = markers[k]
                if (m.index < first || m.index > last) continue
                val b = bars[m.index]
                var stack = 0
                for (q in 0 until k) {
                    val o = markers[q]
                    if (o.index == m.index && o.above == m.above) stack++
                }
                val layout = tm.measure(m.text, styles.marker)
                val tw = layout.size.width.toFloat()
                val th = layout.size.height.toFloat()
                val unit = th + tri + gap
                val cx = xOf(m.index)
                val tx = clampSafe(cx - tw / 2f, 0f, plotW - tw)
                path.reset()
                if (m.above) {
                    val anchor = if (b.h.isFinite()) yOf(b.h) else mainTop + unit
                    val tipY = anchor - gap - stack * unit
                    path.moveTo(cx, tipY)
                    path.lineTo(cx - tri * 0.8f, tipY - tri)
                    path.lineTo(cx + tri * 0.8f, tipY - tri)
                    path.close()
                    drawPath(path, m.color)
                    val ty = max(mainTop, tipY - tri - th)
                    drawText(layout, color = m.color, topLeft = Offset(tx, ty))
                } else {
                    val anchor = if (b.l.isFinite()) yOf(b.l) else mainBottom - unit
                    val tipY = anchor + gap + stack * unit
                    path.moveTo(cx, tipY)
                    path.lineTo(cx - tri * 0.8f, tipY + tri)
                    path.lineTo(cx + tri * 0.8f, tipY + tri)
                    path.close()
                    drawPath(path, m.color)
                    val ty = min(mainBottom - th, tipY + tri)
                    drawText(layout, color = m.color, topLeft = Offset(tx, ty))
                }
            }
        }

        if (focusIndex != null && focusIndex in first..last) {
            val s = 5.dp.toPx()
            drawRect(
                Ink.Accent,
                Offset(xOf(focusIndex) - s / 2f, mainBottom - s - 2.dp.toPx()),
                Size(s, s),
            )
        }
    }

    // ── Lower pane ──
    val lowerH = lowerBottom - lowerTop
    clipRect(left = 0f, top = lowerTop, right = plotW, bottom = lowerBottom) {
        if (lowerPane == LowerPane.Volume) {
            var maxV = 0.0
            for (i in first..last) {
                val v = bars[i].v
                if (v.isFinite() && v > maxV) maxV = v
            }
            if (maxV > 0.0) {
                val bodyW = max(1f, barW * 0.7f)
                for (i in first..last) {
                    val b = bars[i]
                    val v = b.v
                    if (!v.isFinite() || v <= 0.0) continue
                    val bh = max(1f, (v / maxV * lowerH).toFloat())
                    val col = if (b.c >= b.o) Ink.Up else Ink.Down
                    drawRect(
                        col.copy(alpha = 0.6f),
                        Offset(xOf(i) - bodyW / 2f, lowerBottom - bh),
                        Size(bodyW, bh),
                    )
                }
            }
        } else {
            fun yOsc(v: Double): Float = (lowerBottom - v.coerceIn(0.0, 100.0) / 100.0 * lowerH).toFloat()
            val y30 = yOsc(30.0)
            val y20 = yOsc(20.0)
            drawLine(Ink.Muted, Offset(0f, y30), Offset(plotW, y30), strokeWidth = 1f, pathEffect = dash)
            drawLine(Ink.Teal, Offset(0f, y20), Offset(plotW, y20), strokeWidth = 1f, pathEffect = dash)
            if (oscillator != null) {
                val start = max(0, first - 1)
                val end = min(last + 1, oscillator.size - 1)
                if (end >= start) {
                    path.reset()
                    var pen = false
                    var any = false
                    for (i in start..end) {
                        val v = oscillator[i]
                        if (v.isFinite()) {
                            val x = xOf(i)
                            val y = yOsc(v)
                            if (pen) {
                                path.lineTo(x, y)
                                any = true
                            } else {
                                path.moveTo(x, y)
                            }
                            pen = true
                        } else {
                            pen = false
                        }
                    }
                    if (any) {
                        drawPath(
                            path = path,
                            color = Ink.Amber,
                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
    // Lower-pane axis labels (outside the clip).
    if (lowerPane == LowerPane.Volume) {
        var maxV = 0.0
        for (i in first..last) {
            val v = bars[i].v
            if (v.isFinite() && v > maxV) maxV = v
        }
        if (maxV > 0.0) {
            val layout = tm.measure(fmtCompact(maxV), styles.axis)
            drawText(layout, topLeft = Offset(w - layout.size.width, lowerTop))
        }
    } else {
        for (g in 0..1) {
            val v = if (g == 0) 30.0 else 20.0
            val y = (lowerBottom - v / 100.0 * lowerH).toFloat()
            val layout = tm.measure(if (g == 0) "30" else "20", styles.axis)
            val th = layout.size.height.toFloat()
            val ty = clampSafe(y - th / 2f, lowerTop, lowerBottom - th)
            drawText(layout, color = if (g == 0) Ink.Muted else Ink.Teal, topLeft = Offset(w - layout.size.width, ty))
        }
    }

    // ── Dates in the gap between panes ──
    val gapH = lowerTop - mainBottom
    if (!cross.active && last >= first) {
        val firstFull = min(last, max(first, ceil(left).toInt()))
        val lLayout = tm.measure(shortDate(bars[firstFull].t), styles.axis)
        val th = lLayout.size.height.toFloat()
        if (gapH >= th * 0.8f) {
            val ty = mainBottom + (gapH - th) / 2f
            drawText(lLayout, topLeft = Offset(0f, ty))
            if (last != firstFull) {
                val rLayout = tm.measure(shortDate(bars[last].t), styles.axis)
                val rx = min(xOf(last) + barW / 2f, plotW) - rLayout.size.width
                if (rx > lLayout.size.width + 8.dp.toPx()) {
                    drawText(rLayout, topLeft = Offset(rx, ty))
                }
            }
        }
    }

    // ── Crosshair ──
    if (cross.active) {
        val ci = cross.index.coerceIn(0, n - 1)
        val col = Ink.Text.copy(alpha = 0.5f)
        val cx = xOf(ci)
        if (cx >= 0f && cx <= plotW) {
            drawLine(col, Offset(cx, mainTop), Offset(cx, lowerBottom), strokeWidth = 1f)
        }
        val cy = clampSafe(cross.y, mainTop, mainBottom)
        drawLine(col, Offset(0f, cy), Offset(plotW, cy), strokeWidth = 1f)
        val price = lo + (mainBottom - cy) / mainH * range
        val pLayout = tm.measure(fmtNum(price, dec), styles.pill)
        val pw = pillWidth(pLayout)
        drawPill(pLayout, min(plotW + 1f, w - pw), cy, Ink.Pill, Ink.OnPill)

        val dLayout = tm.measure(shortDate(bars[ci].t), styles.pill)
        val dw = pillWidth(dLayout)
        val dx = clampSafe(cx - dw / 2f, 0f, plotW - dw)
        drawPill(dLayout, dx, mainBottom + gapH / 2f, Ink.Pill, Ink.OnPill)
    }
}

private fun bandColor(s: BandState): Color = when (s) {
    BandState.BUY -> Ink.Up.copy(alpha = 0.30f)
    BandState.HOLD -> Ink.Up.copy(alpha = 0.16f)
    BandState.NO -> Ink.Down.copy(alpha = 0.12f)
}

// ───────────────────────────── 分时 drawing ─────────────────────────────

private fun DrawScope.drawTick(
    times: List<String>,
    prices: DoubleArray,
    avg: DoubleArray,
    volumes: DoubleArray,
    prevClose: Double,
    slots: IntArray,
    cross: CrossState,
    geo: AxisHolder,
    tm: TextMeasurer,
    path: Path,
    dash: PathEffect,
    styles: ChartStyles,
) {
    val w = size.width
    val h = size.height
    val cnt = min(min(times.size, prices.size), slots.size)

    // Base (昨收) and symmetric range around it.
    var base = prevClose
    if (!(base.isFinite() && base > 0.0)) {
        base = Double.NaN
        for (i in 0 until cnt) {
            val p = prices[i]
            if (p.isFinite() && p > 0.0) {
                base = p
                break
            }
        }
    }
    val hasData = base.isFinite()
    if (!hasData) base = 1.0
    var dev = 0.0
    var lastPrice = Double.NaN
    if (hasData) {
        for (i in 0 until cnt) {
            val p = prices[i]
            if (p.isFinite() && p > 0.0) {
                dev = max(dev, abs(p - base))
                lastPrice = p
            }
        }
        val ae = min(cnt, avg.size)
        for (i in 0 until ae) {
            val a = avg[i]
            if (a.isFinite() && a > 0.0) dev = max(dev, abs(a - base))
        }
    }
    dev = max(dev, base * 0.002) * 1.06
    val hi = base + dev
    val lo = base - dev
    val dec = if (base < 2.0) 3 else 2

    val axisW = tm.measure(fmtNum(hi, dec), styles.axis).size.width + 6.dp.toPx()
    geo.axisW = axisW
    val plotW = max(1f, w - axisW)
    val labelH = tm.measure("09:30", styles.axis).size.height.toFloat()
    val usable = max(1f, h - labelH - 2.dp.toPx())
    val mainTop = 0f
    val mainBottom = usable * MAIN_FRAC
    val lowerTop = usable * LOWER_TOP_FRAC
    val lowerBottom = usable
    val mainH = mainBottom - mainTop
    val lowerH = lowerBottom - lowerTop

    fun xOf(slot: Int): Float = (slot + 0.5f) / TICK_SLOTS * plotW
    fun yOf(p: Double): Float = (mainBottom - (p - lo) / (hi - lo) * mainH).toFloat()

    // ── Grid ──
    for (s in intArrayOf(60, 120, 180)) {
        val x = xOf(s)
        drawLine(Ink.Hairline, Offset(x, mainTop), Offset(x, mainBottom), strokeWidth = 1f)
        drawLine(Ink.Hairline, Offset(x, lowerTop), Offset(x, lowerBottom), strokeWidth = 1f)
    }
    drawLine(Ink.Hairline, Offset(0f, lowerTop), Offset(plotW, lowerTop), strokeWidth = 1f)
    drawLine(Ink.Hairline, Offset(0f, lowerBottom), Offset(plotW, lowerBottom), strokeWidth = 1f)
    for (k in 0..4) {
        val p = hi - k * dev / 2.0
        val y = yOf(p)
        if (k != 2) drawLine(Ink.Hairline, Offset(0f, y), Offset(plotW, y), strokeWidth = 1f)
        val color = when {
            !hasData -> Ink.Faint
            k < 2 -> Ink.Up
            k > 2 -> Ink.Down
            else -> Ink.Faint
        }
        val layout = tm.measure(fmtNum(p, dec), styles.axis)
        val th = layout.size.height.toFloat()
        val ty = clampSafe(y - th / 2f, mainTop, mainBottom - th)
        drawText(layout, color = color, topLeft = Offset(w - layout.size.width, ty))
    }
    if (hasData) {
        val pct = dev / base * 100.0
        val pad = 2.dp.toPx()
        val top = tm.measure("+" + fmtNum(pct, 2) + "%", styles.axis)
        drawText(top, color = Ink.Up, topLeft = Offset(pad, mainTop))
        val bot = tm.measure("-" + fmtNum(pct, 2) + "%", styles.axis)
        drawText(bot, color = Ink.Down, topLeft = Offset(pad, mainBottom - bot.size.height))
    }

    // ── 昨收 line ──
    val yBase = yOf(base)
    drawLine(Ink.Faint, Offset(0f, yBase), Offset(plotW, yBase), strokeWidth = 1f, pathEffect = dash)
    run {
        val layout = tm.measure("昨收", styles.axis)
        drawText(layout, topLeft = Offset(2.dp.toPx(), max(mainTop, yBase - layout.size.height - 1f)))
    }

    // ── Volume (direction vs previous tick, first tick vs 昨收) ──
    val ve = min(cnt, volumes.size)
    var maxV = 0.0
    for (i in 0 until ve) {
        val v = volumes[i]
        if (v.isFinite() && v > maxV) maxV = v
    }
    if (maxV > 0.0) {
        val bw = max(1f, plotW / TICK_SLOTS * 0.6f)
        var prev = base
        for (i in 0 until ve) {
            val p = prices[i]
            val v = volumes[i]
            val up = if (p.isFinite() && p > 0.0) p >= prev else true
            if (p.isFinite() && p > 0.0) prev = p
            if (!v.isFinite() || v <= 0.0) continue
            val bh = max(1f, (v / maxV * lowerH).toFloat())
            val col = if (up) Ink.Up else Ink.Down
            drawRect(col.copy(alpha = 0.6f), Offset(xOf(slots[i]) - bw / 2f, lowerBottom - bh), Size(bw, bh))
        }
        val layout = tm.measure(fmtCompact(maxV), styles.axis)
        drawText(layout, topLeft = Offset(w - layout.size.width, lowerTop))
    }

    // ── Average + price lines ──
    clipRect(left = 0f, top = mainTop, right = plotW, bottom = mainBottom) {
        val ae = min(cnt, avg.size)
        if (ae > 1) {
            path.reset()
            var pen = false
            var any = false
            for (i in 0 until ae) {
                val a = avg[i]
                if (a.isFinite() && a > 0.0) {
                    val x = xOf(slots[i])
                    val y = yOf(a)
                    if (pen) {
                        path.lineTo(x, y)
                        any = true
                    } else {
                        path.moveTo(x, y)
                    }
                    pen = true
                } else {
                    pen = false
                }
            }
            if (any) {
                drawPath(path, Ink.Amber, style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round, pathEffect = dash))
            }
        }
        if (cnt > 0 && hasData) {
            path.reset()
            var pen = false
            var any = false
            for (i in 0 until cnt) {
                val p = prices[i]
                if (p.isFinite() && p > 0.0) {
                    val x = xOf(slots[i])
                    val y = yOf(p)
                    if (pen) {
                        path.lineTo(x, y)
                        any = true
                    } else {
                        path.moveTo(x, y)
                    }
                    pen = true
                } else {
                    pen = false
                }
            }
            val lineColor = if (lastPrice.isFinite() && lastPrice < base) Ink.Down else Ink.Up
            if (any) {
                drawPath(
                    path,
                    lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else if (lastPrice.isFinite()) {
                // Only one tick so far: a dot.
                var only = 0
                for (i in 0 until cnt) {
                    val p = prices[i]
                    if (p.isFinite() && p > 0.0) {
                        only = i
                        break
                    }
                }
                drawCircle(lineColor, radius = 2.dp.toPx(), center = Offset(xOf(slots[only]), yOf(lastPrice)))
            }
        }
    }

    // ── Bottom time labels ──
    val labelTop = usable + 2.dp.toPx()
    run {
        val l0 = tm.measure("09:30", styles.axis)
        drawText(l0, topLeft = Offset(0f, labelTop))
        val l1 = tm.measure("11:30/13:00", styles.axis)
        drawText(l1, topLeft = Offset(xOf(120) - l1.size.width / 2f, labelTop))
        val l2 = tm.measure("15:00", styles.axis)
        drawText(l2, topLeft = Offset(plotW - l2.size.width, labelTop))
    }

    // ── Crosshair ──
    if (cross.active && cnt > 0) {
        val ci = cross.index.coerceIn(0, cnt - 1)
        val col = Ink.Text.copy(alpha = 0.5f)
        val cx = xOf(slots[ci])
        drawLine(col, Offset(cx, mainTop), Offset(cx, lowerBottom), strokeWidth = 1f)
        val cy = clampSafe(cross.y, mainTop, mainBottom)
        drawLine(col, Offset(0f, cy), Offset(plotW, cy), strokeWidth = 1f)
        val price = lo + (mainBottom - cy) / mainH * (hi - lo)
        val pLayout = tm.measure(fmtNum(price, dec), styles.pill)
        val pw = pillWidth(pLayout)
        drawPill(pLayout, min(plotW + 1f, w - pw), cy, Ink.Pill, Ink.OnPill)

        val tLayout = tm.measure(hhmm(times[ci]), styles.pill)
        val tw = pillWidth(tLayout)
        val tx = clampSafe(cx - tw / 2f, 0f, plotW - tw)
        drawPill(tLayout, tx, labelTop + labelH / 2f, Ink.Pill, Ink.OnPill)
    }
}

// ───────────────────────────── Helpers ─────────────────────────────

private fun DrawScope.pillWidth(layout: TextLayoutResult): Float = layout.size.width + 8.dp.toPx()

private fun DrawScope.drawPill(layout: TextLayoutResult, left: Float, centerY: Float, bg: Color, fg: Color) {
    val padH = 4.dp.toPx()
    val padV = 1.dp.toPx()
    val pw = layout.size.width + padH * 2f
    val ph = layout.size.height + padV * 2f
    val top = centerY - ph / 2f
    drawRoundRect(
        color = bg,
        topLeft = Offset(left, top),
        size = Size(pw, ph),
        cornerRadius = CornerRadius(ph / 2f, ph / 2f),
    )
    drawText(layout, color = fg, topLeft = Offset(left + padH, top + padV))
}

/** coerceIn that never throws when the bounds cross (tiny canvases). */
private fun clampSafe(v: Float, lo: Float, hi: Float): Float =
    if (hi < lo) lo else if (v < lo) lo else if (v > hi) hi else v

private fun niceStep(range: Double, target: Int): Double {
    if (!(range > 0.0) || !range.isFinite()) return 1.0
    val raw = range / target
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val m = when {
        norm < 1.5 -> 1.0
        norm < 2.25 -> 2.0
        norm < 3.5 -> 2.5
        norm < 7.5 -> 5.0
        else -> 10.0
    }
    return m * mag
}

private fun fmtNum(v: Double, decimals: Int): String = when (decimals) {
    3 -> String.format(Locale.US, "%.3f", v)
    else -> String.format(Locale.US, "%.2f", v)
}

private fun fmtCompact(v: Double): String = when {
    v >= 1e8 -> String.format(Locale.US, "%.2f亿", v / 1e8)
    v >= 1e4 -> String.format(Locale.US, "%.1f万", v / 1e4)
    else -> String.format(Locale.US, "%.0f", v)
}

/** "YYYY-MM-DD" stays as is; "YYYY-MM-DD HH:mm" becomes "MM-DD HH:mm". */
private fun shortDate(t: String): String = when {
    t.length >= 16 -> t.substring(5, 16)
    else -> t
}

/** Last "HH:mm" of a time string, or the string itself. */
private fun hhmm(t: String): String = if (t.length >= 5 && t[t.length - 3] == ':') t.substring(t.length - 5) else t

/**
 * Maps "HH:mm" (or "... HH:mm") to a session slot 0..240:
 * 09:30 → 0, 11:30 → 120, 13:00 → 120, 13:01 → 121, 15:00 → 240. Returns -1 if unparsable.
 */
private fun sessionSlot(t: String): Int {
    if (t.length < 5 || t[t.length - 3] != ':') return -1
    val hh = t.substring(t.length - 5, t.length - 3).trim().toIntOrNull() ?: return -1
    val mm = t.substring(t.length - 2).toIntOrNull() ?: return -1
    val m = hh * 60 + mm
    return when {
        m <= 690 -> max(0, m - 570)
        m < 780 -> 120
        else -> min(TICK_SLOTS - 1, 120 + (m - 780))
    }
}
