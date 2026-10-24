package app.reviewnotes.timing.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.ScanMode
import app.reviewnotes.timing.UiState
import app.reviewnotes.timing.engine.IND_NAME
import app.reviewnotes.timing.engine.StockResult
import app.reviewnotes.timing.engine.gateText
import app.reviewnotes.timing.engine.splitLists
import app.reviewnotes.timing.engine.yi
import app.reviewnotes.timing.ui.components.Segmented
import app.reviewnotes.timing.ui.components.T
import app.reviewnotes.timing.ui.glass.quietPanel
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 今天的买点和主升股 */
@Composable
fun ScanScreen(
    ui: UiState, contentPadding: PaddingValues,
    onMode: (ScanMode) -> Unit, onOpen: (StockResult) -> Unit, onStar: (StockResult) -> Unit, starred: (String) -> Boolean,
    cardModifier: @Composable (StockResult) -> Modifier = { Modifier },
    listState: LazyListState = rememberLazyListState(),
) {
    val scan = ui.scan
    val lists = scan?.let { splitLists(it.list) }
    val list = when (ui.scanMode) {
        ScanMode.OK -> lists?.ok; ScanMode.BUY -> lists?.buy; ScanMode.NEAR -> lists?.near; ScanMode.ALL -> lists?.all
    } ?: emptyList()
    LazyColumn(state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("今天的买点和主升股", Type.Title, Ink.Text)
                if (scan != null && lists != null) {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                    val parts = listOfNotNull(
                        scan.baseDate?.takeIf { it.isNotEmpty() }?.let { "历史数据到 ${it.drop(5).replace('-', '/')}" },
                        "候选 ${scan.list.size}", "买点 ${lists.buy.size}", "主升持有 ${lists.ok.size - lists.buy.size}", "差一条 ${lists.near.size}",
                        "扫描于 ${fmt.format(Date(scan.at))}",
                    )
                    T(parts.joinToString(" · "), Type.Caption, Ink.Muted)
                    if (scan.mainInd.isNotEmpty()) T("成交前 40 里最多的行业：" + scan.mainInd.joinToString("，") { (g, c) -> (IND_NAME[g] ?: g) + " $c" }, Type.Caption, Ink.Muted)
                }
            }
        }
        ui.market?.let { m ->
            item {
                val (tone, txt) = gateText(m.dsOk, m.techLv, m.level, " " + yi(m.amount))
                Column(Modifier.padding(horizontal = 16.dp)) { GateLine(tone, txt) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Segmented(ScanMode.entries.map { it.label }, ui.scanMode.ordinal, { onMode(ScanMode.entries[it]) })
            }
        }
        when {
            scan == null && ui.scanError != null -> item { Empty("候选池没拉到：${ui.scanError}。下拉再试一次。") }
            scan == null -> item { Empty("正在扫描成交额前 200 的股票……") }
            list.isEmpty() -> item {
                Empty(when (ui.scanMode) { ScanMode.NEAR, ScanMode.ALL -> "没有。"; ScanMode.BUY -> "今天成交额前 200 里没有出现买点的股票。"; ScanMode.OK -> "今天成交额前 200 里没有在主升里的股票。" })
            }
            else -> items(list.take(150), key = { it.id }) { r ->
                StockCard(r, starred(r.id), { onStar(r) }, { onOpen(r) }, Modifier.padding(horizontal = 16.dp).then(cardModifier(r)))
            }
        }
        item {
            T("规则：主升持有＝收盘在 5 日线上、3 天内有新高、量能堆着、20 日线向上；买点＝爆发日、分歧低吸、反包、放量上 5 日线、放量新高。只是个人复盘工具，不构成投资建议。",
                Type.Caption, Ink.Faint, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun Empty(text: String) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().quietPanel(RoundedCornerShape(18.dp)).padding(18.dp)) {
        T(text, Type.Small, Ink.Muted)
    }
}
