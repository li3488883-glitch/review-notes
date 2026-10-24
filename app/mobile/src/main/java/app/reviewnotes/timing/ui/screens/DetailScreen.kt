package app.reviewnotes.timing.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.DetailState
import app.reviewnotes.timing.data.Em
import app.reviewnotes.timing.data.Fenshi
import app.reviewnotes.timing.engine.Bj
import app.reviewnotes.timing.engine.Session
import app.reviewnotes.timing.engine.State
import app.reviewnotes.timing.engine.pct
import app.reviewnotes.timing.engine.yi
import app.reviewnotes.timing.ui.chart.BandState
import app.reviewnotes.timing.ui.chart.ChartMarker
import app.reviewnotes.timing.ui.chart.KLineChart
import app.reviewnotes.timing.ui.chart.LineSeries
import app.reviewnotes.timing.ui.chart.TickChart
import app.reviewnotes.timing.ui.components.Chip
import app.reviewnotes.timing.ui.components.ChipKind
import app.reviewnotes.timing.ui.components.ChipRow
import app.reviewnotes.timing.ui.components.PillButton
import app.reviewnotes.timing.ui.components.Segmented
import app.reviewnotes.timing.ui.components.T
import app.reviewnotes.timing.ui.glass.RollingNumber
import app.reviewnotes.timing.ui.glass.heavyGlass
import app.reviewnotes.timing.ui.glass.quietPanel
import app.reviewnotes.timing.ui.glass.rememberTiltLight
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type
import app.reviewnotes.timing.ui.theme.changeColor
import kotlinx.coroutines.delay

/** One stock: price, the screening state, and its daily chart with the 符合 / 不符合 bands. */
@Composable
fun DetailScreen(
    d: DetailState, starred: Boolean, contentPadding: PaddingValues,
    onBack: () -> Unit, onStar: () -> Unit, headerModifier: Modifier = Modifier,
) {
    val tilt by rememberTiltLight()
    var showBands by remember { mutableStateOf(true) }
    var tf by remember { mutableIntStateOf(0) }    // 0 日K, 1 分时
    var hover by remember { mutableStateOf<Int?>(null) }
    val r = d.result
    val e = r?.eval
    val bars = d.bars
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("‹ 返回", onBack)
            Spacer(Modifier.weight(1f))
            PillButton(if (starred) "★ 移出自选" else "☆ 加入自选", onStar, solid = !starred)
        }
        // header: the shared element that the list card grows into
        Column(
            headerModifier.padding(horizontal = 16.dp).fillMaxWidth()
                .heavyGlass(RoundedCornerShape(30.dp), lightOffset = tilt)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(d.name, Type.Title, Ink.Text)
                T("  " + d.code, Type.Small, Ink.Muted)
            }
            val shown = hover?.let { bars.getOrNull(it) } ?: bars.lastOrNull()
            val prev = bars.getOrNull((hover ?: bars.lastIndex) - 1)
            val chg = if (shown != null && prev != null) shown.c / prev.c - 1 else Double.NaN
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RollingNumber(shown?.let { String.format(if (it.c < 2) "%.3f" else "%.2f", it.c) } ?: "—", Type.NumBig, changeColor(chg))
                T(pct(chg), Type.Heading, changeColor(chg))
                T(shown?.t ?: "", Type.Small, Ink.Muted)
            }
            if (shown != null) {
                T("开 ${f2(shown.o)}  高 ${f2(shown.h)}  低 ${f2(shown.l)}  额 ${yi(shown.a)}", Type.Caption, Ink.Muted)
            }
            if (e != null) {
                T("5日线 ${f2(e.m5)} · 10日线 ${f2(e.m10)} · 20日线 ${f2(e.m20)}" + (if (r?.live == true && Bj.session() != Session.CLOSED) " · 盘中" else ""), Type.Caption, Ink.Faint)
            }
        }
        if (d.error != null) Empty(d.error)
        if (r != null && e != null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipRow {
                    if (e.sell.isNotEmpty()) Chip((if (r.live) "盘中" else "卖出：") + e.sell[0], ChipKind.SELL) else Chip("持有条件还在", ChipKind.HOLD)
                    StateChip(r)
                    Chip(if (d.tech) "科技：看科创50 / 创业板指" else "非科技：看大盘", ChipKind.KIND)
                }
                ChipRow { HOLD_TXT.forEach { (k, txt) -> Chip(txt, if (e.hold[k]) ChipKind.OK else ChipKind.MISS) } }
                ChipRow { BonusChips(r); e.warn.forEach { Chip(it, ChipKind.WARN) } }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(listOf("日K", "分时"), tf, { tf = it })
            if (tf == 0) Segmented(listOf("显示区间", "隐藏区间"), if (showBands) 0 else 1, { showBands = it == 0 })
        }
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().quietPanel(RoundedCornerShape(24.dp)).padding(10.dp)) {
            if (tf == 0) {
                if (bars.size < 2) T(if (d.loading) "读取中……" else "没有数据", Type.Small, Ink.Muted, Modifier.padding(20.dp))
                else DayChart(d, showBands) { hover = it }
            } else {
                FenshiPane(d.id)
            }
        }
        if (tf == 0) {
            T(
                (if (showBands) "深红：买点 · 浅红：主升持有 · 绿色：不符合 · " else "") + "白线 MA5 · 蓝线 MA10 · 灰线 MA20 · ▲爆发/反包/上5日/新高 ●低吸 ▼破5日。双指缩放，长按看每天的数据。",
                Type.Caption, Ink.Faint, Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun f2(x: Double) = if (x.isNaN()) "—" else String.format(if (x < 2) "%.3f" else "%.2f", x)

@Composable
private fun DayChart(d: DetailState, showBands: Boolean, onHover: (Int?) -> Unit) {
    val bars = d.bars
    val evals = d.evals
    val ma = remember(bars) {
        fun ma(k: Int) = DoubleArray(bars.size) { i -> if (i < k - 1) Double.NaN else (i - k + 1..i).sumOf { bars[it].c } / k }
        Triple(ma(5), ma(10), ma(20))
    }
    val bands = remember(evals) { evals.map { e -> when (e?.state) { State.BUY -> BandState.BUY; State.HOLD -> BandState.HOLD; State.NO -> BandState.NO; null -> null } } }
    val markers = remember(evals, bars) {
        val out = ArrayList<ChartMarker>()
        val from = maxOf(65, bars.size - 160)
        for (j in from until bars.size) {
            val e = evals.getOrNull(j) ?: continue
            if (e.buy) {
                val t = e.trig
                val txt = when { t.burst -> "▲爆发"; t.fanbao -> "▲反包"; t.reclaim -> "▲上5日"; t.newHigh -> "▲新高"; else -> "●低吸" }
                out += ChartMarker(j, txt, when { t.burst -> Ink.Up; t.dip -> Ink.Teal; else -> Ink.Violet }, above = false)
            } else if (bars[j].c < ma.first[j] && bars[j - 1].c >= ma.first[j - 1]) {
                out += ChartMarker(j, "▼破5日", Ink.Down, above = true)
            }
        }
        out
    }
    KLineChart(
        bars, Modifier.fillMaxWidth().height(420.dp),
        lines = listOf(LineSeries(ma.first, Ink.Text, 1.1f), LineSeries(ma.second, Ink.Accent, 1.8f), LineSeries(ma.third, Ink.Faint, 1.1f)),
        bands = bands, showBands = showBands, markers = markers, initialVisible = 120, onCrosshair = onHover,
    )
}

@Composable
private fun FenshiPane(id: String) {
    var fs by remember(id) { mutableStateOf<Fenshi?>(null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        while (true) {
            runCatching { Em.fenshi(id, isIndex = false) }.onSuccess { fs = it; err = null }.onFailure { if (fs == null) err = "分时暂时没拉到，稍后会自动重试。" }
            delay(if (Bj.session() == Session.OPEN) 15_000 else 120_000)
        }
    }
    val f = fs
    when {
        f != null && f.points.isNotEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val last = f.points.last()
                T("${f.date.drop(5).replace('-', '/')} 分时 · 最新 ${f2(last.price)}（${pct(last.price / f.prevClose - 1)}）· 黄线是${f.avgLabel} · 虚线是昨收", Type.Caption.copy(fontWeight = FontWeight.Medium), Ink.Muted)
                TickChart(f.points.map { it.time }, f.points.map { it.price }.toDoubleArray(), f.points.map { it.avg }.toDoubleArray(), f.points.map { it.volume }.toDoubleArray(), f.prevClose,
                    Modifier.fillMaxWidth().height(360.dp))
            }
        }
        err != null -> T(err!!, Type.Small, Ink.Muted, Modifier.padding(20.dp))
        else -> T("读取中……", Type.Small, Ink.Muted, Modifier.padding(20.dp))
    }
}
