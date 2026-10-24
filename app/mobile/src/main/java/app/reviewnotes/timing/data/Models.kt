package app.reviewnotes.timing.data

/** One daily (or intraday) bar. [t] is "YYYY-MM-DD" for daily bars, "YYYY-MM-DD HH:mm" for intraday bars. */
data class Bar(
    val t: String,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
    val a: Double = Double.NaN,
)

/** A live quote snapshot. */
data class Quote(
    val id: String,          // "1.600519" (1 = 沪, 0 = 深/北)
    val code: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Double,      // 手
    val amount: Double,      // 元
    val turnover: Double = Double.NaN,   // 换手率 %
    val floatCap: Double = Double.NaN,   // 流通市值 元
    val date: String? = null,            // "YYYY-MM-DD" when known
) {
    val change: Double get() = if (prevClose > 0) price / prevClose - 1 else Double.NaN
    fun toBar(day: String): Bar = Bar(day, open, high, low, price, volume, amount)
}

/** One row of the turnover ranking (候选池). */
data class RankRow(
    val id: String,
    val code: String,
    val name: String,
    val quote: Quote,
)

/** A point of a 分时 line. [time] is "HH:mm". */
data class TickPoint(val time: String, val price: Double, val avg: Double, val volume: Double)

data class Fenshi(val date: String, val prevClose: Double, val points: List<TickPoint>, val avgLabel: String)

/** A stock the user keeps in 自选. */
data class WatchItem(val id: String, val code: String, val name: String)

fun secidOf(code: String): String = (if (code.startsWith("6") || code.startsWith("9")) "1." else "0.") + code
