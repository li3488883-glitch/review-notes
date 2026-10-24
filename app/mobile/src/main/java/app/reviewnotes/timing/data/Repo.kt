package app.reviewnotes.timing.data

import app.reviewnotes.timing.engine.Bj
import app.reviewnotes.timing.engine.IndexMaps
import app.reviewnotes.timing.engine.LineStat
import app.reviewnotes.timing.engine.Session
import app.reviewnotes.timing.engine.StockResult
import app.reviewnotes.timing.engine.evaluateStock
import app.reviewnotes.timing.engine.lineStat
import app.reviewnotes.timing.engine.mergeIndex
import app.reviewnotes.timing.engine.rankPool
import app.reviewnotes.timing.engine.Amount
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 大势 at one moment. */
class Market(
    val A: LineStat, val Y: LineStat, val H: LineStat,
    val sh: LineStat?, val kc: LineStat?, val cy: LineStat?,
    val amount: Double, val amountPrev: Double, val amountDay: String,
) {
    val dsOk: Boolean get() = A.above && Y.above && H.above
    val level: Int get() = Amount.level(amount)
    val techLv: Int get() = maxOf(kc?.level ?: -1, cy?.level ?: -1)
}

class ScanResult(val list: List<StockResult>, val mainInd: List<Pair<String, Int>>, val baseDate: String?, val pool: Int, val at: Long, val missing: Int)

/** Everything that touches the network, with the caches the page keeps. */
class Repo {
    companion object {
        val LINES = listOf(Triple("A", "883957", "全A"), Triple("Y", "883902", "昨成交"), Triple("H", "883910", "热股"))
        const val KC = "1.000688"; const val CY = "0.399006"; const val SH = "1.000001"
    }

    // ---------- base file: re-read once a day, and again after 19:30 when the new one is out ----------
    private val baseLock = Mutex()
    private var base: Site.Base? = null
    private var baseKey = ""
    suspend fun base(): Site.Base? = baseLock.withLock {
        val n = Bj.now()
        val key = Bj.date() + if (n.hour * 60 + n.minute >= 1170) "b" else "a"
        if (base != null && baseKey == key) return@withLock base
        val b = runCatching { Site.base(key) }.getOrNull()
        if (b != null && b.version == 2) { base = b; baseKey = key }
        base
    }
    fun industry(id: String): String = base?.stocks?.get(id)?.g ?: ""

    // ---------- index history for relative strength ----------
    private var ixLong: Map<String, List<Bar>> = emptyMap()
    private var ixLongAt = 0L
    @Volatile var indexMaps: IndexMaps = IndexMaps(emptyMap(), emptyMap())
        private set

    private suspend fun refreshIndexMaps(market: Market?) {
        if (System.currentTimeMillis() - ixLongAt > 30 * 60_000L || ixLong.isEmpty()) {
            ixLongAt = System.currentTimeMillis()
            ixLong = coroutineScope {
                listOf("kc" to KC, "cy" to CY, "sh" to SH).map { (k, id) -> async { k to (runCatching { Em.kline(id, 800).second }.getOrNull() ?: emptyList()) } }
                    .associate { it.await() }
            }
        }
        val m = HashMap<String, HashMap<String, Double>>()
        listOf("kc", "cy", "sh").forEach { m[it] = HashMap() }
        base?.let { b -> b.ix.forEach { (k, v) -> m[k]?.putAll(v) } }
        ixLong.forEach { (k, bars) -> bars.forEach { m[k]?.put(it.t, it.c) } }
        market?.let { mk ->
            mk.kc?.bars?.forEach { m["kc"]!![it.t] = it.c }; mk.cy?.bars?.forEach { m["cy"]!![it.t] = it.c }; mk.sh?.bars?.forEach { m["sh"]!![it.t] = it.c }
        }
        indexMaps = IndexMaps(mergeIndex(m["kc"]!!, m["cy"]!!), m["sh"]!!)
    }

    // ---------- 大势 ----------
    suspend fun market(): Market = coroutineScope {
        val lines = LINES.map { (k, code, _) -> async { k to Ths.daily(code) } }.associate { it.await() }
        val sh = async { runCatching { Em.kline(SH, 60).second }.getOrNull() }
        val kc = async { runCatching { Em.kline(KC, 60).second }.getOrNull() }
        val cy = async { runCatching { Em.kline(CY, 60).second }.getOrNull() }
        val A = lineStat(lines["A"]!!) ?: error("全A 没拉到")
        val aBars = lines["A"]!!
        val mk = Market(
            A, lineStat(lines["Y"]!!) ?: error("昨成交 没拉到"), lineStat(lines["H"]!!) ?: error("热股 没拉到"),
            sh.await()?.let { lineStat(it) }, kc.await()?.let { lineStat(it) }, cy.await()?.let { lineStat(it) },
            aBars.last().a, aBars[aBars.size - 2].a, aBars.last().t,
        )
        runCatching { refreshIndexMaps(mk) }
        mk
    }

    // ---------- today's candidates ----------
    suspend fun scan(): ScanResult = coroutineScope {
        val pages = mutableListOf<Quote>()
        for (p in 1..2) {
            try { pages += Em.top(p) } catch (e: Exception) { if (p == 1) throw IllegalStateException("成交额排行没拉到") }
        }
        val seen = HashSet<String>()
        val list = pages.filter { it.price > 0 && it.amount > 0 && !Regex("ST|退").containsMatchIn(it.name) && seen.add(it.id) }
        if (list.isEmpty()) error("候选池为空")
        list.forEach { patch(it) }
        val b = base()
        runCatching { refreshIndexMaps(null) }
        val today = Bj.date(); val pre = Bj.session() == Session.PRE
        val res: List<StockResult?>
        var missing = 0
        if (b != null) {
            res = list.map { q ->
                val x = Site.bars(b, q.id, q, today, pre) ?: return@map null
                val (bars, rec) = x
                val (_, e) = evaluateStock(bars, q.code, q.name, rec.g, indexMaps, rec.m)
                StockResult(q.id, q.code, q.name, e, q.amount, q.turnover, q.price, q.change, rec.g, app.reviewnotes.timing.engine.isTech(q.code, rec.g), bars.size > rec.c.size)
            }
            missing = res.count { it == null }
        } else {
            // no base file: per-stock history for the top 40 only, to stay gentle on the sources
            res = list.take(40).map { q ->
                runCatching {
                    val bars = klineCached(q.id, 500).second
                    val (_, e) = evaluateStock(bars, q.code, q.name, "", indexMaps)
                    StockResult(q.id, q.code, q.name, e, q.amount, q.turnover, q.price, q.change, "", app.reviewnotes.timing.engine.isTech(q.code, ""), true)
                }.getOrNull()
            }
            missing = list.size - 40
        }
        val out = res.filterNotNull()
        val main = rankPool(out)
        ScanResult(out, main, b?.date, list.size, System.currentTimeMillis(), missing)
    }

    // ---------- per-stock history, fetched once per day and patched from snapshots ----------
    private class KCache(val day: String, val limit: Int, val name: String, val bars: MutableList<Bar>)
    private val kcache = ConcurrentHashMap<String, KCache>()

    suspend fun klineCached(id: String, limit: Int): Pair<String, List<Bar>> {
        val day = Bj.date()
        kcache[id]?.let { c -> if (c.day == day && c.limit >= limit) return c.name to c.bars.toList() }
        val (name, bars) = Em.kline(id, limit)
        kcache[id] = KCache(day, limit, name, bars.toMutableList())
        return name to bars
    }

    fun patch(q: Quote) {
        val c = kcache[q.id] ?: return
        if (c.bars.isEmpty() || listOf(q.open, q.high, q.low, q.price, q.volume).any { it.isNaN() || it <= 0 }) return
        val today = q.date ?: Bj.date()
        val last = c.bars.last()
        val bar = Bar(today, q.open, q.high, q.low, q.price, q.volume, q.amount)
        if (last.t == today) c.bars[c.bars.size - 1] = bar
        else if (last.t < today && (q.volume != last.v || q.price != last.c) && Bj.session() != Session.PRE) c.bars.add(bar)
    }

    /** Evaluate one stock (watchlist, detail): base file when it has the stock, otherwise its own history. */
    suspend fun evaluate(id: String, code: String, name: String, quote: Quote?): Pair<StockResult?, List<Bar>> {
        quote?.let { patch(it) }
        val b = base()
        val today = Bj.date(); val pre = Bj.session() == Session.PRE
        val s = Bj.session(); val liveSess = s == Session.OPEN || s == Session.NOON
        if (b != null) {
            Site.bars(b, id, quote, today, pre)?.let { (bars, rec) ->
                val (_, e) = evaluateStock(bars, code, name, rec.g, indexMaps, rec.m)
                return StockResult(id, code, name.ifEmpty { rec.n }, e, quote?.amount ?: Double.NaN, quote?.turnover ?: Double.NaN, quote?.price ?: bars.last().c,
                    quote?.change ?: (bars.last().c / bars[bars.size - 2].c - 1), rec.g, app.reviewnotes.timing.engine.isTech(code, rec.g), liveSess && bars.size > rec.c.size) to bars
            }
        }
        val (nm, bars) = klineCached(id, 500)
        val ind = industry(id)
        val (_, e) = evaluateStock(bars, code, nm.ifEmpty { name }, ind, indexMaps)
        val last = bars.lastOrNull()
        return StockResult(id, code, nm.ifEmpty { name }, e, last?.a ?: Double.NaN, Double.NaN, last?.c ?: Double.NaN,
            if (bars.size > 1) bars.last().c / bars[bars.size - 2].c - 1 else Double.NaN, ind, app.reviewnotes.timing.engine.isTech(code, ind), liveSess && last?.t == today) to bars
    }
}
