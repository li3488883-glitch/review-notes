package app.reviewnotes.timing.engine

import app.reviewnotes.timing.data.Bar
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/* 大势: the three 同花顺 lines against their 10-day line, the 科技 gate, and the two-market turnover tiers. */

object Bj {
    private val zone = ZoneId.of("Asia/Shanghai")
    fun now(): ZonedDateTime = ZonedDateTime.now(zone)
    fun date(): String = now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    fun hms(): String = now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    fun year(): Int = now().year
    /** pre / open / noon / closed, by Beijing time on weekdays */
    fun session(): Session {
        val n = now()
        if (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) return Session.CLOSED
        val m = n.hour * 60 + n.minute
        return when {
            m < 9 * 60 + 15 -> Session.PRE
            m < 11 * 60 + 30 -> Session.OPEN
            m < 13 * 60 -> Session.NOON
            m < 15 * 60 -> Session.OPEN
            else -> Session.CLOSED
        }
    }
}

enum class Session(val label: String) { PRE("盘前"), OPEN("交易中"), NOON("午间休市"), CLOSED("已收盘") }

class LineStat(val t: String, val c: Double, val chg: Double, val m10: Double, val m5: Double, val spark: List<Pair<Double, Double>>, val bars: List<Bar>) {
    val above: Boolean get() = c >= m10
    val above5: Boolean get() = c >= m5
    val dist: Double get() = c / m10 - 1
    /** 2 = 在 10 日线上（共振），1 = 只在 5 日线上，0 = 5 日线下 */
    val level: Int get() = if (above) 2 else if (above5) 1 else 0
}

fun lineStat(bars: List<Bar>): LineStat? {
    val n = bars.size
    if (n < 12) return null
    val C = bars.map { it.c }
    fun ma(k: Int, i: Int): Double { var s = 0.0; for (j in i - k + 1..i) s += C[j]; return s / k }
    val c = C[n - 1]
    val spark = if (n >= 50) (0 until 40).map { k -> C[n - 40 + k] to ma(10, n - 40 + k) } else emptyList()
    return LineStat(bars[n - 1].t, c, c / C[n - 2] - 1, ma(10, n - 1), ma(5, n - 1), spark, bars)
}

object Amount {
    val tiers = listOf(
        Triple(0.0, "不到 2 万亿", "休息：2 万亿以下不开仓"),
        Triple(2e12, "2 万亿到 2.5 万亿", "局部机会，只做局部放量的方向，不重仓"),
        Triple(2.5e12, "2.5 万亿以上", "容量票有重仓机会"),
        Triple(3e12, "接近 3 万亿以上", "主线走得出来的量"),
    )
    fun level(a: Double): Int = when { a >= 3e12 -> 3; a >= 2.5e12 -> 2; a >= 2e12 -> 1; else -> 0 }
}

val LV_TXT = listOf("在 5 日线下", "只在 5 日线上（最低要求）", "在 10 日线上（共振）")

enum class Tone { OK, PART, NO }

/** The gate is per kind of stock: 科技 looks at 科创50 / 创业板指, the rest at the three 同花顺 lines. */
fun gateText(nonTechOk: Boolean?, techLv: Int, lv: Int, amtTxt: String): Pair<Tone, String> {
    val lvT = when (lv) {
        0 -> "成交额$amtTxt，不到 2 万亿，原规则休息"
        1 -> "成交额$amtTxt，只算局部机会，不重仓"
        2 -> "成交额$amtTxt，容量票可以重仓"
        else -> "成交额$amtTxt，主线走得出来的量"
    }
    val a = if (nonTechOk == null) "" else "非科技：三条线" + (if (nonTechOk) "都在 10 日线上方，大势符合" else "没有都在 10 日线上方，大势不符合") + "。"
    val b = if (techLv < 0) "" else "科技：科创50 / 创业板指" + LV_TXT[techLv] + (if (techLv == 2) "，科技可以做" else if (techLv == 1) "，只够试错" else "，科技不做") + "。"
    val okAny = (nonTechOk == true || techLv == 2) && lv >= 1
    val part = !okAny && (techLv == 1 || nonTechOk == true) && lv >= 1
    val tone = if (okAny) (if (lv >= 2) Tone.OK else Tone.PART) else if (part) Tone.PART else Tone.NO
    return tone to (a + b + lvT + "。" + if (okAny) "" else "按原规则这天下面只当观察，不是买点。")
}

/** 两个温度计：昨成交 and 热股 against their 5-day lines. */
fun thermo(y: Boolean, h: Boolean): Pair<String, String> = when {
    y && h -> "强强" to "两个温度计同频，行情级别最高：容量中军和持续大涨的强势股都可以跟"
    y -> "强弱" to "大容量票是行情主体：做成交前排的中军"
    h -> "弱强" to "大票走弱、小票走强：小票和情绪方向，轻仓"
    else -> "弱弱" to "两个都在走弱：休息"
}

fun yi(a: Double): String = when {
    a.isNaN() -> "—"
    a >= 1e12 -> String.format("%.2f 万亿", a / 1e12)
    a >= 1e8 -> String.format("%.1f 亿", a / 1e8)
    else -> String.format("%.0f 万", a / 1e4)
}

fun pct(x: Double, digits: Int = 2): String = if (x.isNaN()) "—" else (if (x > 0) "+" else if (x < 0) "−" else "") + String.format("%.${digits}f%%", kotlin.math.abs(x) * 100)

/** 东方财富 board names used on the chart chips */
val IND_NAME = mapOf(
    "C39" to "电子/通信", "I63" to "电信", "I64" to "互联网", "I65" to "软件", "C35" to "专用设备", "C38" to "电气/电池", "C34" to "通用设备",
    "C40" to "仪器仪表", "C26" to "化工", "C27" to "医药", "C36" to "汽车", "C37" to "军工/交运设备", "C32" to "有色", "C30" to "建材",
    "J66" to "银行", "J67" to "证券", "J68" to "保险", "C15" to "酒饮料", "C13" to "农副食品", "B06" to "煤炭", "B07" to "石油",
    "D44" to "电力", "C33" to "金属制品", "C29" to "塑料橡胶", "C31" to "钢铁", "K70" to "地产", "M73" to "研发服务", "R86" to "传媒",
    "F51" to "批发", "F52" to "零售", "G54" to "道路运输", "G55" to "航运", "G56" to "航空", "E48" to "建筑",
)
