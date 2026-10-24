package app.reviewnotes.timing.data

import java.util.concurrent.ConcurrentHashMap

/*
 * Quote sources, ported from the page (same URLs and parameters).
 * 同花顺: board / index lines (全A, 昨日成交前十, 热股) and their minute data.
 * 东方财富: turnover ranking, quotes, daily bars, 分时, search. It rate-limits; we fall back to 腾讯 / 新浪.
 */

fun ymd(s: String): String = if (s.length >= 8) s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8) else s

private fun rows(data: Any?): List<List<String>> =
    (data.str() ?: "").split(';').filter { it.isNotBlank() }.map { it.split(',') }

/** Market of a secid for 腾讯 / 新浪 symbols. */
fun sym(secid: String): String {
    val (m, code) = secid.split('.').let { it[0] to it[1] }
    val bj = Regex("^(8|4|92)").containsMatchIn(code) && code.length == 6 && !code.startsWith("399")
    return (if (m == "1") "sh" else if (bj) "bj" else "sz") + code
}

object Ths {
    private const val ROOT = "https://d.10jqka.com.cn/"

    private suspend fun load(path: String): Any? {
        val p = if (Regex("^v\\d/").containsMatchIn(path)) path else "v4/line/$path"
        return Json.parseLoose(Http.getR(ROOT + p + "?_=" + bust()))
    }

    /** Daily bars of a 同花顺 board/index (e.g. 883957 全A), with today's bar merged in. */
    suspend fun daily(code: String): List<Bar> {
        val last = load("bk_$code/01/last.js")
        val bars = rows(last["data"]).map { x -> Bar(ymd(x[0]), x[1].num(), x[2].num(), x[3].num(), x[4].num(), x[5].num(), x.getOrNull(6).num()) }.toMutableList()
        runCatching { today(code) }.getOrNull()?.let { bar ->
            if (bars.isNotEmpty() && bars.last().t == bar.t) bars[bars.size - 1] = bar
            else if (bars.isEmpty() || bars.last().t < bar.t) bars.add(bar)
        }
        return bars
    }

    suspend fun today(code: String): Bar? {
        val q = load("bk_$code/01/today.js")["bk_$code"] ?: return null
        val c = q["11"].num()
        if (q["1"] == null || c.isNaN()) return null
        return Bar(ymd(q["1"].str()!!), q["7"].num(), q["8"].num(), q["9"].num(), c, q["13"].num(), q["19"].num())
    }

    private val yearCache = ConcurrentHashMap<String, List<Bar>>()

    /** One calendar year of daily closes and turnover (for past days). Past years are kept in memory. */
    suspend fun year(code: String, y: Int, currentYear: Int): List<Bar> {
        val key = "$code-$y"
        yearCache[key]?.let { return it }
        val d = load("bk_$code/01/$y.js")
        val bars = rows(d["data"]).map { x -> Bar(ymd(x[0]), x[1].num(), x[2].num(), x[3].num(), x[4].num(), x[5].num(), x.getOrNull(6).num()) }
        if (y < currentYear && bars.isNotEmpty()) yearCache[key] = bars
        return bars
    }

    /** 30- or 60-minute bars of a board. t = "YYYY-MM-DD HH:mm". */
    suspend fun minute(code: String, scale: Int): List<Bar> {
        val p = if (scale == 30) "42" else "52"
        val d = load("v4/line/bk_$code/$p/last.js")
        return rows(d["data"]).map { x ->
            val z = x[0]
            Bar("${ymd(z)} ${z.substring(8, 10)}:${z.substring(10, 12)}", x[1].num(), x[2].num(), x[3].num(), x[4].num(), x[5].num(), x.getOrNull(6).num())
        }
    }

    /** 分时 of a board. The index line has no average price, so its running mean is used. */
    suspend fun fenshi(code: String): Fenshi {
        val q = load("v4/time/bk_$code/last.js")["bk_$code"] ?: error("无数据")
        var sum = 0.0
        val pts = rows(q["data"]).mapIndexed { k, x ->
            val p = x[1].num(); sum += p
            TickPoint("${x[0].substring(0, 2)}:${x[0].substring(2, 4)}", p, sum / (k + 1), x.getOrNull(4).num())
        }
        return Fenshi(ymd(q["date"].str() ?: ""), q["pre"].num(), pts, "均值线")
    }

    /** 分时 of a stock (fallback when 东方财富 refuses). */
    suspend fun stockFenshi(code: String): Fenshi {
        val q = load("v6/time/hs_$code/last.js")["hs_$code"] ?: error("无数据")
        val pts = rows(q["data"]).map { x -> TickPoint("${x[0].substring(0, 2)}:${x[0].substring(2, 4)}", x[1].num(), x.getOrNull(3).num(), x.getOrNull(4).num()) }
        return Fenshi(ymd(q["date"].str() ?: ""), q["pre"].num(), pts, "均价线")
    }
}

object Em {
    @Volatile private var klineDownUntil = 0L
    @Volatile private var topDownUntil = 0L
    private const val FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"

    private fun quoteOf(r: Any?): Quote {
        val m = r["f13"].num().toInt()
        val code = r["f12"].str() ?: ""
        val c = r["f2"].num()
        val chg = r["f3"].num()
        val pre = r["f18"].num().let { if (it > 0) it else if (!chg.isNaN()) c / (1 + chg / 100) else Double.NaN }
        return Quote("$m.$code", code, r["f14"].str() ?: code, c, pre, r["f17"].num(), r["f15"].num(), r["f16"].num(), r["f5"].num(), r["f6"].num(), r["f8"].num(), r["f21"].num())
    }

    /** Turnover ranking page (100 rows). 东方财富, or 新浪 for five minutes after it refuses. */
    suspend fun top(page: Int): List<Quote> {
        if (System.currentTimeMillis() > topDownUntil) {
            try {
                val d = Json.parseLoose(Http.get("https://push2.eastmoney.com/api/qt/clist/get?pn=$page&pz=100&po=1&np=1&fltt=2&invt=2&fid=f6&fs=$FS&fields=f2,f3,f5,f6,f8,f12,f13,f14,f15,f16,f17,f18,f21&_=${bust()}"))
                val diff = d["data"]["diff"].arr()
                if (!diff.isNullOrEmpty()) return diff.map { quoteOf(it) }
                throw IllegalStateException("空")
            } catch (e: Exception) { topDownUntil = System.currentTimeMillis() + 300_000 }
        }
        return Sina.top(page)
    }

    /** Daily bars (前复权). 东方财富 first; 腾讯 for five minutes after it refuses. */
    suspend fun kline(secid: String, limit: Int): Pair<String, List<Bar>> {
        if (System.currentTimeMillis() > klineDownUntil) {
            try {
                val d = Json.parseLoose(Http.get("https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=$secid&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57&klt=101&fqt=1&lmt=$limit&end=20500101"))
                val k = d["data"]["klines"].arr()
                if (!k.isNullOrEmpty()) {
                    return (d["data"]["name"].str() ?: "") to k.map { s -> val x = (s as String).split(','); Bar(x[0], x[1].num(), x[3].num(), x[4].num(), x[2].num(), x[5].num(), x[6].num()) }
                }
                throw IllegalStateException("空")
            } catch (e: Exception) { klineDownUntil = System.currentTimeMillis() + 300_000 }
        }
        return Qq.kline(secid, limit)
    }

    /** Quotes of a few stocks: 腾讯 first (one request), 东方财富 as the fallback. */
    suspend fun snap(ids: List<String>): Map<String, Quote> {
        if (ids.isEmpty()) return emptyMap()
        runCatching { Qq.quotes(ids) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val d = Json.parseLoose(Http.getR("https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&secids=${ids.joinToString(",")}&fields=f2,f3,f5,f6,f8,f12,f13,f14,f15,f16,f17,f18&_=${bust()}"))
        return (d["data"]["diff"].arr() ?: emptyList()).map { quoteOf(it) }.associateBy { it.id }
    }

    suspend fun fenshi(secid: String, isIndex: Boolean): Fenshi {
        try {
            val d = Json.parseLoose(Http.getR("https://push2.eastmoney.com/api/qt/stock/trends2/get?secid=$secid&fields1=f1,f2,f3,f4,f5,f6,f7,f8&fields2=f51,f52,f53,f54,f55,f56,f57,f58&iscr=0&ndays=1&_=${bust()}"))
            val tr = d["data"]["trends"].arr()
            if (tr.isNullOrEmpty()) error("无数据")
            val pts = tr.map { s -> val x = (s as String).split(','); TickPoint(x[0].substring(11, 16), x[2].num(), x.getOrNull(7).num(), x[5].num()) }
            return Fenshi((tr[0] as String).substring(0, 10), d["data"]["preClose"].num(), pts, if (isIndex) "均值线" else "均价线")
        } catch (e: Exception) {
            if (isIndex) throw e
            return Ths.stockFenshi(secid.substringAfter('.'))
        }
    }

    data class Hit(val id: String, val code: String, val name: String, val type: String)

    suspend fun suggest(q: String): List<Hit> {
        val d = Json.parseLoose(Http.get("https://searchapi.eastmoney.com/api/suggest/get?input=${java.net.URLEncoder.encode(q, "UTF-8")}&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=8", timeoutMs = 6000))
        return (d["QuotationCodeTable"]["Data"].arr() ?: emptyList()).filter {
            val t = it["SecurityTypeName"].str() ?: ""
            Regex("^(沪A|深A|北A|京A)$").matches(t) || it["Classify"].str() == "AStock"
        }.map {
            val code = it["Code"].str() ?: ""
            Hit(it["QuoteID"].str() ?: ((if (it["MktNum"].str() == "1") "1." else "0.") + code), code, it["Name"].str() ?: code, it["SecurityTypeName"].str() ?: "")
        }
    }
}

object Qq {
    suspend fun kline(secid: String, limit: Int): Pair<String, List<Bar>> {
        val s = sym(secid)
        val v = "rtqq_" + bust()
        val text = Http.getR("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?_var=$v&param=$s,day,,,${minOf(limit, 800)},qfq&r=${Math.random()}")
        val d = Json.parseLoose(text)
        val k = d["data"][s]
        val rws = (k["qfqday"].arr() ?: k["day"].arr()) ?: error("无数据")
        val name = k["qt"][s][1].str() ?: ""
        return name to rws.map { x -> val c = x[2].num(); val vol = x[5].num(); Bar(x[0].str()!!, x[1].num(), x[3].num(), x[4].num(), c, vol, vol * 100 * c) }
    }

    /** 腾讯 quotes, GBK text: v_sh600519="1~贵州茅台~600519~price~prev~open~vol~...". */
    suspend fun quotes(ids: List<String>): Map<String, Quote> {
        val syms = ids.map { sym(it) }
        val text = Http.get("https://qt.gtimg.cn/q=${syms.joinToString(",")}&r=${Math.random()}", Http.GBK, 8000)
        val out = HashMap<String, Quote>()
        val re = Regex("v_(\\w+)=\"([^\"]*)\"")
        val bySym = re.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        ids.forEachIndexed { k, id ->
            val a = (bySym[syms[k]] ?: return@forEachIndexed).split('~')
            if (a.size < 40) return@forEachIndexed
            val d = a.getOrNull(30) ?: ""
            out[id] = Quote(
                id, a[2], a[1], a[3].num(), a[4].num(), a[5].num(), a[33].num(), a[34].num(), a[6].num(), a[37].num() * 1e4,
                a.getOrNull(38).num(), a.getOrNull(44).num() * 1e8,
                if (d.length >= 8) ymd(d) else null,
            )
        }
        return out
    }
}

object Sina {
    suspend fun top(page: Int): List<Quote> {
        val cb = "rtsn_" + bust()
        val d = Json.parseLoose(Http.getR("https://vip.stock.finance.sina.com.cn/quotes_service/api/jsonp_v2.php/$cb/Market_Center.getHQNodeData?page=$page&num=100&sort=amount&asc=0&node=hs_a&symbol=&_s_r_a=page"))
        return (d.arr() ?: emptyList()).map { r ->
            val code = r["code"].str() ?: ""
            val m = if ((r["symbol"].str() ?: "").startsWith("sh")) 1 else 0
            Quote("$m.$code", code, r["name"].str() ?: code, r["trade"].num(), r["settlement"].num(), r["open"].num(), r["high"].num(), r["low"].num(),
                r["volume"].num() / 100, r["amount"].num(), r["turnoverratio"].num(), r["nmc"].num() * 1e4)
        }
    }

    /** 30- or 60-minute bars of a stock or index. */
    suspend fun minute(secid: String, scale: Int, len: Int = 480): List<Bar> {
        val cb = "rtsk_" + bust()
        val d = Json.parseLoose(Http.getR("https://quotes.sina.cn/cn/api/jsonp_v2.php/$cb/CN_MarketDataService.getKLineData?symbol=${sym(secid)}&scale=$scale&ma=no&datalen=$len"))
        return (d.arr() ?: emptyList()).map { r ->
            Bar((r["day"].str() ?: "").take(16), r["open"].num(), r["high"].num(), r["low"].num(), r["close"].num(), r["volume"].num(), r["amount"].num())
        }
    }
}
