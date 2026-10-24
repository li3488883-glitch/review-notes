package app.reviewnotes.timing.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.UiState
import app.reviewnotes.timing.data.Em
import app.reviewnotes.timing.engine.StockResult
import app.reviewnotes.timing.ui.components.T
import app.reviewnotes.timing.ui.theme.Ink
import app.reviewnotes.timing.ui.theme.Type

/** 自选与卖出信号 */
@Composable
fun WatchScreen(
    ui: UiState, contentPadding: PaddingValues,
    onSearch: (String) -> Unit, onPick: (Em.Hit) -> Unit, onOpen: (StockResult) -> Unit, onStar: (StockResult) -> Unit,
    cardModifier: @Composable (StockResult) -> Modifier = { Modifier },
    listState: LazyListState = rememberLazyListState(),
) {
    var q by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    // a new sell signal on a stock in the list gets a firm tap
    val sells = ui.watchResults.values.count { it.eval?.sell?.isNotEmpty() == true }
    var lastSells by remember { mutableIntStateOf(-1) }
    LaunchedEffect(sells) { if (lastSells >= 0 && sells > lastSells) haptic.performHapticFeedback(HapticFeedbackType.LongPress); lastSells = sells }

    val ordered = ui.watch.distinctBy { it.id }.sortedBy { w ->
        val e = ui.watchResults[w.id]?.eval
        when { e == null -> 3; e.sell.isNotEmpty() -> 0; e.warn.isNotEmpty() -> 1; else -> 2 }
    }
    LazyColumn(state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("自选与卖出信号", Type.Title, Ink.Text)
                T("收盘跌破 5 日线就出；盘中跌破、还没收盘的先提示。警示：三天没刷新阶段新高、大阳线回吐过半、跌停等。", Type.Caption, Ink.Muted)
            }
        }
        item {
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape)
                    .background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                if (q.isEmpty()) T("输入股票代码、名称或拼音", Type.Body, Ink.Faint)
                BasicTextField(
                    q, { q = it; onSearch(it) }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = Type.Body.copy(color = Ink.Text), cursorBrush = SolidColor(Ink.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { ui.hits.firstOrNull()?.let { h -> onPick(h); q = ""; onSearch(""); focus.clearFocus() } }),
                )
            }
        }
        if (q.isNotBlank() && ui.hits.isNotEmpty()) {
            // lazy keys must be unique: the search source can list one security twice
            items(ui.hits.distinctBy { it.id }, key = { "hit-" + it.id }) { h ->
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .clickable { onPick(h); q = ""; onSearch(""); focus.clearFocus() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(h.name, Type.Body, Ink.Text, Modifier.weight(1f))
                    T("${h.code} · ${h.type}", Type.Caption, Ink.Faint)
                }
            }
        }
        if (ui.watch.isEmpty()) item { Empty("还没有自选。在上面搜索股票，或者在选股列表里点「加自选」。") }
        items(ordered, key = { it.id }) { w ->
            val r = ui.watchResults[w.id]
            if (r == null) Empty("${w.name} ${w.code} 读取中……")
            else StockCard(r, true, { onStar(r) }, { onOpen(r) }, Modifier.padding(horizontal = 16.dp).then(cardModifier(r)), signal = true)
        }
    }
}
