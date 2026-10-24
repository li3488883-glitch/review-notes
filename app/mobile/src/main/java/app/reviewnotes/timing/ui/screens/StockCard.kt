package app.reviewnotes.timing.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.engine.State
import app.reviewnotes.timing.engine.StockResult
import app.reviewnotes.timing.engine.pct
import app.reviewnotes.timing.engine.yi
import app.reviewnotes.timing.ui.components.Chip
import app.reviewnotes.timing.ui.components.ChipKind
import app.reviewnotes.timing.ui.components.ChipRow
import app.reviewnotes.timing.ui.components.PillButton
import app.reviewnotes.timing.ui.components.T
import app.reviewnotes.timing.ui.glass.pressScale
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type
import app.reviewnotes.timing.ui.theme.changeColor

val HOLD_TXT = listOf("ma5" to "收盘在5日线上", "high" to "3天内有新高", "vol" to "量能堆着", "trend" to "20日线向上")

@Composable
fun StateChip(r: StockResult) {
    val e = r.eval ?: return
    when (e.state) {
        State.BUY -> Chip("买点：" + e.trig.names().joinToString("、"), ChipKind.BUY)
        State.HOLD -> Chip("主升持有", ChipKind.HOLD)
        State.NO -> Chip("不符合（主升四条差 ${e.fails} 条）", ChipKind.NO)
    }
}

@Composable
fun BonusChips(r: StockResult) {
    val e = r.eval ?: return
    if (e.rs) Chip("强于指数", ChipKind.BONUS)
    if (e.kd) Chip("指数跌它抗跌", ChipKind.BONUS)
    if (e.lead) Chip("率先新高", ChipKind.BONUS)
    if (e.extreme) Chip("极限量", ChipKind.BONUS)
    if (r.dm) Chip("大马拉小车", ChipKind.BONUS)
    if (r.main) Chip("主线板块", ChipKind.BONUS)
    if (r.rank20 <= 50) Chip("20日涨幅第${r.rank20}", ChipKind.BONUS)
}

/**
 * One stock. In signal mode (自选) the sell signal leads; otherwise the screening state does.
 * Buy points glow red from the top-left corner; everything else stays quiet.
 */
@Composable
fun StockCard(r: StockResult, starred: Boolean, onStar: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier, signal: Boolean = false) {
    val e = r.eval
    val shape = RoundedCornerShape(22.dp)
    val buy = e?.state == State.BUY && !signal
    val sell = signal && e != null && e.sell.isNotEmpty()
    val edge = when {
        sell -> Ink.Down.copy(alpha = 0.5f)
        signal && e != null && e.warn.isNotEmpty() -> Ink.Amber.copy(alpha = 0.4f)
        buy -> Ink.Up.copy(alpha = 0.5f)
        e?.state == State.HOLD -> Ink.Up.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.07f)
    }
    val glow = when { sell -> Ink.Down; buy -> Ink.Up; else -> null }
    Column(
        modifier
            .fillMaxWidth()
            .pressScale()
            .clip(shape)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))))
                if (glow != null) drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.18f), Color.Transparent), center = Offset.Zero, radius = size.width * 0.9f))
            }
            .border(1.dp, edge, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            T(r.name, Type.Heading, Ink.Text, maxLines = 1)
            T("  " + r.code, Type.Caption, Ink.Muted, Modifier.weight(1f))
            if (!r.price.isNaN() && r.day == null) T(String.format(if (r.price < 2) "%.3f" else "%.2f", r.price) + "  ", Type.Num, changeColor(r.chg))
            T(pct(r.chg), Type.Small, changeColor(r.chg))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!r.amount.isNaN()) Meta("成交", yi(r.amount))
            if (!r.turnover.isNaN()) Meta("换手", String.format("%.1f%%", r.turnover))
            if (e != null && !e.bias.isNaN()) Meta("离5日线", pct(e.bias, 1))
            if (e != null && !e.amtx.isNaN()) Meta("量能10/60", String.format("%.2f", e.amtx))
        }
        if (e != null) {
            ChipRow {
                if (signal) {
                    if (e.sell.isNotEmpty()) Chip((if (r.live) "盘中" else "卖出：") + e.sell[0], ChipKind.SELL)
                    else Chip("持有条件还在：5 日线上方", ChipKind.HOLD)
                    e.warn.forEach { Chip(it, ChipKind.WARN) }
                }
                StateChip(r)
                if (r.tech) Chip("科技", ChipKind.KIND)
            }
            if (!signal) {
                val miss = HOLD_TXT.filter { !e.hold[it.first] }
                if (miss.isNotEmpty()) ChipRow { miss.forEach { Chip(it.second, ChipKind.MISS) } }
            }
            ChipRow { BonusChips(r) }
            if (!signal && e.warn.isNotEmpty()) ChipRow { e.warn.forEach { Chip(it, ChipKind.WARN) } }
            if (signal && e.sell.isNotEmpty() && r.live) T("盘中跌破、还没收盘：原规则是不能快速收回或收盘跌破才出。", Type.Caption, Ink.Muted)
        } else {
            T("上市时间太短，算不了这些条件。", Type.Caption, Ink.Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(r.day ?: e?.t ?: "", Type.Caption, Ink.Faint, Modifier.weight(1f))
            PillButton(if (starred) "★ 已自选" else "☆ 加自选", onStar)
        }
    }
}

@Composable
private fun Meta(k: String, v: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        T("$k ", Type.Caption, Ink.Muted)
        T(v, Type.Caption.copy(fontWeight = FontWeight.Medium), Ink.Text)
    }
}
