package app.reviewnotes.timing.engine

import app.reviewnotes.timing.data.Bar
import kotlin.math.roundToInt

/** One stock in the scan, the watchlist or a past day's list. */
class StockResult(
    val id: String,
    val code: String,
    val name: String,
    val eval: Eval?,
    val amount: Double,
    val turnover: Double,
    val price: Double,
    val chg: Double,
    val industry: String,
    val tech: Boolean,
    val live: Boolean,
    val day: String? = null,            // set for past days
) {
    var rank20: Int = Int.MAX_VALUE
    var dm: Boolean = false              // 大马拉小车
    var main: Boolean = false            // 主线板块
    val bonus: Int get() {
        val e = eval ?: return 0
        return listOf(e.rs, e.kd, e.lead, e.extreme, dm, main, rank20 <= 50).count { it }
    }
}

/** The index maps for the two kinds of stocks: T (科创50, 创业板指 before it existed) and N (上证). */
class IndexMaps(val tech: Map<String, Double>, val other: Map<String, Double>)

fun mergeIndex(kc: Map<String, Double>, cy: Map<String, Double>): Map<String, Double> {
    val t = HashMap(cy)
    t.putAll(kc)
    // 科创50 and 创业板指 differ in level: only use 创业板指 where 科创50 has no data at all
    val kcStart = kc.keys.minOrNull()
    if (kcStart != null) t.keys.filter { it >= kcStart && !kc.containsKey(it) }.forEach { t.remove(it) }
    return t
}

/** Evaluate the last bar of [bars]. [vmFromBase] is the 500-day volume maximum stored in the base file, when the bars come from it. */
fun evaluateStock(bars: List<Bar>, code: String, name: String, industry: String, ix: IndexMaps, vmFromBase: Double? = null): Pair<Prepared, Eval?> {
    val tech = isTech(code, industry)
    val P = prep(bars, if (tech) ix.tech else ix.other)
    val i = bars.size - 1
    if (vmFromBase != null && i >= 0) {
        P.vmax[i] = maxOf(vmFromBase, if (P.v5[i].isNaN()) 0.0 else P.v5[i])
        P.vmLong = true
    }
    return P to (if (i >= 0) evalAt(bars, P, i, code, name, tech) else null)
}

/** rank20, 大马拉小车 and 主线板块 across the whole candidate pool, as on the page. Returns the top industries. */
fun rankPool(out: List<StockResult>): List<Pair<String, Int>> {
    out.filter { it.eval != null }.sortedByDescending { it.eval!!.ret20 }.forEachIndexed { k, r -> r.rank20 = k + 1 }
    val byAmt = out.sortedByDescending { it.amount }
    val t100 = byAmt.take(100).map { it.turnover }.filter { !it.isNaN() }.sorted()
    val cut = if (t100.size >= 10) t100[(0.7 * (t100.size - 1)).roundToInt()] else Double.POSITIVE_INFINITY
    val cnt = HashMap<String, Int>()
    byAmt.take(40).forEach { if (it.industry.isNotEmpty()) cnt[it.industry] = (cnt[it.industry] ?: 0) + 1 }
    val top = cnt.entries.sortedByDescending { it.value }.map { it.key to it.value }
    val main = top.take(2).filter { it.second >= 3 }.map { it.first }.toSet()
    byAmt.forEachIndexed { k, r -> r.dm = k < 100 && !r.turnover.isNaN() && r.turnover >= cut; r.main = r.industry in main }
    return top.take(3)
}

class Lists(val ok: List<StockResult>, val buy: List<StockResult>, val near: List<StockResult>, val all: List<StockResult>)

fun splitLists(all: List<StockResult>): Lists {
    val score = { r: StockResult -> (if (r.eval?.buy == true) 100 else 0) + r.bonus }
    val ok = all.filter { it.eval?.pass == true }.sortedWith(compareByDescending<StockResult> { score(it) }.thenByDescending { it.amount })
    val near = all.filter { it.eval != null && !it.eval.pass && it.eval.fails == 1 }.sortedByDescending { it.amount }
    val every = all.sortedWith(compareByDescending<StockResult> { if (it.eval?.pass == true) 1 else 0 }.thenBy { it.eval?.fails ?: 9 }.thenByDescending { it.amount })
    return Lists(ok, ok.filter { it.eval?.buy == true }, near, every)
}
