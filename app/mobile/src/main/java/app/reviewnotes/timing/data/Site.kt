package app.reviewnotes.timing.data

/*
 * Files the site's scheduled job writes after each close (see pages.yml / tools/make_data.py):
 *   base.json      the top 1800 stocks by turnover, last 66 trading days, packed as cent deltas
 *   picks/YYYY.json  per-day screening records since 2010 (picks/index.json lists the years)
 *   names.json     code -> name
 */
object Site {
    const val ROOT = "https://li3488883-glitch.github.io/review-notes/"

    class BaseRec(
        val c: IntArray,          // cents, first absolute then deltas
        val h: IntArray,          // high - close, cents
        val o: IntArray,          // open - close, cents (last NO days)
        val v: DoubleArray,       // volume (last NV days)
        val l: Int,               // low of the last day, cents
        val m: Double,            // 500-day max of the 5-day average volume
        val n: String,            // name
        val g: String,            // industry code (证监会), e.g. C39
        val d: List<String>?,     // own dates when they differ from the shared calendar
    )

    class Base(val date: String, val dates: List<String>, val stocks: Map<String, BaseRec>, val ix: Map<String, Map<String, Double>>, val version: Int)

    suspend fun base(cacheKey: String): Base {
        val j = Json.parse(Http.getR(ROOT + "base.json?d=" + cacheKey, timeoutMs = 20000))
        val dates = (j["dates"].arr() ?: emptyList()).map { it.str()!! }
        val s = HashMap<String, BaseRec>()
        (j["s"].obj() ?: emptyMap()).forEach { (id, r) ->
            fun ints(k: String) = (r[k].arr() ?: emptyList()).map { it.num().toInt() }.toIntArray()
            s[id] = BaseRec(
                ints("c"), ints("h"), ints("o"),
                (r["v"].arr() ?: emptyList()).map { it.num() }.toDoubleArray(),
                r["l"].num().toInt(), r["m"].num(), r["n"].str() ?: id.substring(2), r["g"].str() ?: "",
                r["d"].arr()?.map { it.str()!! },
            )
        }
        val ix = HashMap<String, Map<String, Double>>()
        (j["ix"].obj() ?: emptyMap()).forEach { (k, arr) ->
            val m = HashMap<String, Double>()
            (arr.arr() ?: emptyList()).forEachIndexed { q, v -> val x = v.num(); if (!x.isNaN() && q < dates.size) m[dates[q]] = x }
            ix[k] = m
        }
        return Base(j["d"].str() ?: "", dates, s, ix, j["v"].num().let { if (it.isNaN()) 1 else it.toInt() })
    }

    /** History bars from the base file, plus the live bar when it is a newer day. Returns null when the stock isn't in the file. */
    fun bars(b: Base, id: String, live: Quote?, today: String, preOpen: Boolean): Pair<List<Bar>, BaseRec>? {
        val rec = b.stocks[id] ?: return null
        val n = rec.c.size
        val dates = if (rec.d != null && rec.d.size == n) rec.d else b.dates.takeLast(n)
        val out = ArrayList<Bar>(n + 1)
        var c = 0
        for (j in 0 until n) {
            c += rec.c[j]
            val oi = j - (n - rec.o.size); val vi = j - (n - rec.v.size)
            out.add(Bar(
                dates.getOrElse(j) { "" }, if (oi >= 0) (c + rec.o[oi]) / 100.0 else c / 100.0, (c + rec.h[j]) / 100.0,
                if (j == n - 1) rec.l / 100.0 else Double.NaN, c / 100.0, if (vi >= 0) rec.v[vi] else Double.NaN,
            ))
        }
        val last = out.last()
        if (live != null && live.price > 0 && live.volume > 0) {
            val same = kotlin.math.abs(live.price - last.c) < 0.006 && kotlin.math.abs(live.volume - last.v) / last.v < 0.03
            if (!same && (!preOpen || today > last.t)) {
                val t = live.date?.takeIf { it > last.t } ?: if (today > last.t) today else last.t + "+"
                out.add(Bar(t, live.open, live.high, live.low, live.price, live.volume, live.amount))
            }
        }
        return out to rec
    }

    suspend fun names(): Map<String, String> {
        val j = Json.parse(Http.getR(ROOT + "names.json?d=" + bust().take(8), timeoutMs = 20000))
        return (j.obj() ?: emptyMap()).mapValues { it.value.str() ?: "" }
    }

    class PicksIndex(val years: List<String>, val first: String, val last: String, val bits: List<String>)

    suspend fun picksIndex(): PicksIndex {
        val j = Json.parse(Http.getR(ROOT + "picks/index.json?d=" + bust().take(8)))
        return PicksIndex((j["years"].arr() ?: emptyList()).map { it.str()!! }, j["first"].str() ?: "", j["last"].str() ?: "", (j["bits"].arr() ?: emptyList()).map { it.str()!! })
    }

    /** One stored record: [id, chg%, amount 亿, turnover %, flags, rank20, fails, extra bits]. */
    class PickRec(val id: String, val chg: Double, val amt: Double, val turn: Double, val flags: Int, val rank20: Int, val fails: Int, val extra: Int)
    class PickDay(val n: Int, val recs: List<PickRec>, val gates: Map<String, Pair<Int, Double>>, val amt: Double, val techShare: Double, val up100: Int, val mainInd: List<Pair<String, Int>>)

    suspend fun picksYear(y: String): Map<String, PickDay> {
        val j = Json.parse(Http.getR(ROOT + "picks/$y.json?d=" + bust().take(8), timeoutMs = 30000))
        val out = LinkedHashMap<String, PickDay>()
        (j.obj() ?: emptyMap()).forEach { (day, v) ->
            val recs = (v["r"].arr() ?: emptyList()).map { r ->
                PickRec(r[0].str()!!, r[1].num(), r[2].num(), r[3].num(), r[4].num().toInt(), r[5].num().toInt(), r[6].num().toInt(), r[7].num().let { if (it.isNaN()) 0 else it.toInt() })
            }
            val g = (v["g"].obj() ?: emptyMap()).mapValues { (_, a) -> a[0].num().toInt() to a[1].num() }
            val m = v["m"]
            out[day] = PickDay(v["n"].num().toInt(), recs, g, m["amt"].num() * 1e8, m["ts"].num(), m["u100"].num().let { if (it.isNaN()) -1 else it.toInt() },
                (v["ms"].arr() ?: emptyList()).map { it[0].str()!! to it[1].num().toInt() })
        }
        return out
    }
}
