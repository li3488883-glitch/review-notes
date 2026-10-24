package app.reviewnotes.timing.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.UiState
import app.reviewnotes.timing.data.Market
import app.reviewnotes.timing.engine.Amount
import app.reviewnotes.timing.engine.LV_TXT
import app.reviewnotes.timing.engine.LineStat
import app.reviewnotes.timing.engine.Session
import app.reviewnotes.timing.engine.Tone
import app.reviewnotes.timing.engine.gateText
import app.reviewnotes.timing.engine.pct
import app.reviewnotes.timing.engine.thermo
import app.reviewnotes.timing.engine.yi
import app.reviewnotes.timing.ui.components.Mark
import app.reviewnotes.timing.ui.components.PillButton
import app.reviewnotes.timing.ui.components.Spark
import app.reviewnotes.timing.ui.components.T
import app.reviewnotes.timing.ui.glass.RollingNumber
import app.reviewnotes.timing.ui.glass.heavyGlass
import app.reviewnotes.timing.ui.glass.quietPanel
import app.reviewnotes.timing.ui.glass.rememberTiltLight
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type
import app.reviewnotes.timing.ui.theme.changeColor

/** 大势：先看大势，再看量能，最后才选股. */
@Composable
fun HomeScreen(ui: UiState, contentPadding: PaddingValues, listState: LazyListState = rememberLazyListState(), onRefresh: () -> Unit) {
    LazyColumn(state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Hero(ui, onRefresh) }
        val m = ui.market
        if (m != null) {
            item { GaugeRow(listOf(Gauge("全A", "883957", "同花顺全A（沪深京）", m.A), Gauge("昨成交", "883902", "昨日成交前十", m.Y))) }
            item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { GaugeCard(Gauge("热股", "883910", "同花顺热股", m.H), Modifier.weight(1f)); AmountCard(m, ui.session, Modifier.weight(1f)) } }
            item { TechCard(m) }
            item { ThermoCard(m) }
            item {
                T("非科技股看前三条线：都在 10 日线上方才算大势符合。科技股看科技闸门（科创50、创业板指）。成交额是沪深京三市合计。",
                    Type.Caption, Ink.Faint, Modifier.padding(horizontal = 20.dp))
            }
        } else if (ui.marketError != null) {
            item { T("实时数据暂时拉不到（${ui.marketError}），下拉再试一次。", Type.Body, Ink.Muted, Modifier.padding(20.dp)) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Hero(ui: UiState, onRefresh: () -> Unit) {
    val m = ui.market
    val tilt by rememberTiltLight()
    val verdict = when { m == null -> "读取中"; m.dsOk -> "大势符合"; else -> "大势不符合" }
    val target = when { m == null -> Ink.Muted; m.dsOk -> Ink.Up; else -> Ink.Down }
    val c by animateColorAsState(target, tween(900), label = "verdict")
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .heavyGlass(RoundedCornerShape(34.dp), lightOffset = tilt)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        T("盘中实时 · 先看大势，再看量能，最后才选股", Type.Caption, Ink.Muted)
        BasicText(
            verdict,
            style = Type.Verdict.copy(brush = Brush.verticalGradient(listOf(lerpWhite(c), c)), shadow = Shadow(c.copy(alpha = 0.55f), blurRadius = 38f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.session == Session.OPEN) LiveDot()
            T(listOfNotNull(m?.A?.t, ui.session.label, if (ui.updatedAt.isNotEmpty()) "北京时间 ${ui.updatedAt}" else null).joinToString(" · "), Type.Small, Ink.Muted)
        }
        if (m != null) {
            val nUp = listOf(m.A, m.Y, m.H).count { it.above }
            val lv = m.level
            val first = if (m.dsOk) "三条线都在 10 日线上方" else (if (nUp == 0) "三条线全部" else "三条线里有 ${3 - nUp} 条") + "在 10 日线下方"
            val tail = when {
                m.dsOk && lv >= 1 -> "可以往下看选股。"
                m.techLv == 2 && lv >= 1 -> "不过科技指数在 10 日线上方，科技股可以单独看。"
                else -> "按原规则，今天到这里就够了，不选股。"
            }
            T("$first；两市成交额${if (ui.session == Session.OPEN || ui.session == Session.NOON) "目前" else ""} ${yi(m.amount)}，${Amount.tiers[lv].third}。$tail", Type.Body, Ink.Text.copy(alpha = 0.9f))
            val (tone, txt) = gateText(m.dsOk, m.techLv, lv, " " + yi(m.amount))
            GateLine(tone, txt)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (ui.refreshing) "更新中…" else "立即刷新", onRefresh, solid = true)
            T(ui.status, Type.Caption, Ink.Faint)
        }
    }
}

private fun lerpWhite(c: Color) = Color((c.red + 1f) / 2f, (c.green + 1f) / 2f, (c.blue + 1f) / 2f, 1f)

@Composable
fun GateLine(tone: Tone, text: String) {
    val col = when (tone) { Tone.OK -> Ink.Up; Tone.PART -> Ink.Amber; Tone.NO -> Ink.Down }
    Box(Modifier.fillMaxWidth().quietPanel(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        T(text, Type.Small, col)
    }
}

@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveA")
    Box(Modifier.width(8.dp).height(8.dp).graphicsLayer { alpha = a }.clip(RoundedCornerShape(50)).background(Ink.Up))
}

private class Gauge(val name: String, val code: String, val sub: String, val s: LineStat)

@Composable
private fun GaugeRow(g: List<Gauge>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        g.forEach { GaugeCard(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun GaugeCard(g: Gauge, modifier: Modifier) {
    val s = g.s
    Column(modifier.quietPanel(RoundedCornerShape(22.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            T(g.name, Type.Heading, Ink.Text, Modifier.weight(1f))
            T(g.code, Type.Caption, Ink.Faint)
        }
        T(g.sub, Type.Caption, Ink.Muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mark(if (s.above) 2 else 0)
            T(if (s.above) "在 10 日线上方" else "在 10 日线下方", Type.Small.copy(fontWeight = FontWeight.SemiBold), if (s.above) Ink.Up else Ink.Down)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RollingNumber(String.format("%.2f", s.c), Type.Num, Ink.Text)
            T(pct(s.chg), Type.Small, changeColor(s.chg))
        }
        T("10 日线 ${String.format("%.2f", s.m10)} · 距离 ${pct(s.dist, 1)}", Type.Caption, Ink.Muted)
        Spark(s.spark)
        T(if (s.above5) "在 5 日线上" else "在 5 日线下", Type.Caption, Ink.Faint)
    }
}

@Composable
private fun AmountCard(m: Market, session: Session, modifier: Modifier) {
    val lv = m.level
    Column(modifier.quietPanel(RoundedCornerShape(22.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            T("两市成交额", Type.Heading, Ink.Text, Modifier.weight(1f))
            T("沪深京", Type.Caption, Ink.Faint)
        }
        T(if (session == Session.OPEN || session == Session.NOON) "盘中累计，收盘前还会增加" else m.amountDay.drop(5).replace('-', '/') + " 全天", Type.Caption, Ink.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mark(if (lv >= 2) 2 else if (lv == 1) 1 else 0)
            RollingNumber(yi(m.amount), Type.Num.copy(fontWeight = FontWeight.SemiBold), if (lv >= 2) Ink.Up else if (lv == 1) Ink.Amber else Ink.Down)
        }
        T("档位 ${Amount.tiers[lv].second}", Type.Caption, Ink.Muted)
        T("前一天 ${yi(m.amountPrev)}", Type.Caption, Ink.Muted)
        T(Amount.tiers[lv].third, Type.Small, Ink.Text)
    }
}

@Composable
private fun TechCard(m: Market) {
    val lv = m.techLv
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().quietPanel(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            T("科技闸门", Type.Heading, Ink.Text, Modifier.weight(1f))
            T("科创50 · 创业板指", Type.Caption, Ink.Faint)
        }
        if (lv < 0) { T("科创50 和创业板指暂时没拉到。", Type.Small, Ink.Muted); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mark(lv)
            T(LV_TXT[lv], Type.Small.copy(fontWeight = FontWeight.SemiBold), if (lv == 2) Ink.Up else if (lv == 1) Ink.Amber else Ink.Down)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("科创50" to m.kc, "创业板指" to m.cy).forEach { (nm, s) ->
                if (s != null) T("$nm ${pct(s.chg)}  ${if (s.above) "10日线上" else if (s.above5) "5日线上" else "5日线下"}", Type.Small, changeColor(s.chg))
            }
        }
        m.kc?.let { Spark(it.spark) }
        T("原规则：科技只看自己的指数和板块；放量站上 10 日线才算共振，一波下跌后起码要上 5 日线。", Type.Caption, Ink.Faint)
    }
}

@Composable
private fun ThermoCard(m: Market) {
    val (name, txt) = thermo(m.Y.above5, m.H.above5)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().quietPanel(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("两个温度计：$name", Type.Heading, Ink.Text)
        T("昨成交${if (m.Y.above5) "在 5 日线上方" else "跌破 5 日线"}，热股${if (m.H.above5) "在 5 日线上方" else "跌破 5 日线"}。$txt。", Type.Small, Ink.Muted)
        m.sh?.let { T("上证 ${String.format("%.2f", it.c)}（${pct(it.chg)}），在 5 日线${if (it.above5) "上方" else "下方"}、10 日线${if (it.above) "上方" else "下方"}。", Type.Caption, Ink.Faint) }
    }
}
