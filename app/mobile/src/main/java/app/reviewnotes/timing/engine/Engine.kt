package app.reviewnotes.timing.engine

import app.reviewnotes.timing.data.Bar
import kotlin.math.abs
import kotlin.math.max

/*
 * The screening rules. This is a line-by-line port of the page's rule engine (index.html, between
 * "ENGINE START" and "ENGINE END") and must keep giving the same answers as it and as tools/make_data.py.
 * NaN behaves as in JavaScript: every comparison with NaN is false.
 */

fun limitOf(code: String, name: String?): Double = when {
    name != null && name.contains("ST") -> 0.05
    Regex("^(688|689|300|301)").containsMatchIn(code) -> 0.2
    Regex("^(8|4|92)").containsMatchIn(code) -> 0.3
    else -> 0.1
}

/** 科技：科创板，或者证监会行业是电子/通信（C39）、软件和信息技术服务（I63–I65）；创业板里的专用设备、仪器仪表也算 */
fun isTech(code: String, ind: String?): Boolean {
    val i = ind ?: ""
    return Regex("^(688|689)").containsMatchIn(code) ||
        Regex("^(C39|I63|I64|I65)").containsMatchIn(i) ||
        (Regex("^(300|301)").containsMatchIn(code) && Regex("^(C35|C40)").containsMatchIn(i))
}

private fun ok(x: Double) = !x.isNaN() && !x.isInfinite()

class Hold(val ma5: Boolean, val high: Boolean, val vol: Boolean, val trend: Boolean) {
    val all: Boolean get() = ma5 && high && vol && trend
    operator fun get(k: String): Boolean = when (k) { "ma5" -> ma5; "high" -> high; "vol" -> vol; else -> trend }
}

class Prepared(
    val n: Int,
    val C: DoubleArray, val V: DoubleArray, val H: DoubleArray, val L: DoubleArray, val O: DoubleArray, val A: DoubleArray,
    val m5: DoubleArray, val m10: DoubleArray, val m20: DoubleArray, val v5: DoubleArray, val a10: DoubleArray, val a60: DoubleArray,
    val vmax: DoubleArray, val I: DoubleArray,
) {
    /** set when the 500-day volume maximum comes from the base file rather than from these bars */
    var vmLong: Boolean = false
    internal val hold = arrayOfNulls<Hold>(n)
}

/** [ix]: date -> close of the stock's own gate index (科技用科创50/创业板指，其余用上证) */
fun prep(b: List<Bar>, ix: Map<String, Double>?): Prepared {
    val n = b.size
    val C = DoubleArray(n) { b[it].c }
    val V = DoubleArray(n) { b[it].v }
    val H = DoubleArray(n) { b[it].h }
    val L = DoubleArray(n) { b[it].l }
    val O = DoubleArray(n) { b[it].o }
    val A = DoubleArray(n) { V[it] * C[it] }          // 成交额的近似（量 × 收盘），日线和盘中算法一致
    fun ma(X: DoubleArray, k: Int): DoubleArray {
        val out = DoubleArray(n) { Double.NaN }
        for (i in k - 1 until n) { var s = 0.0; for (j in i - k + 1..i) s += X[j]; out[i] = s / k }
        return out
    }
    val m5 = ma(C, 5); val m10 = ma(C, 10); val m20 = ma(C, 20); val v5 = ma(V, 5); val a10 = ma(A, 10); val a60 = ma(A, 60)
    val vmax = DoubleArray(n) { Double.NaN }
    var m = Double.NEGATIVE_INFINITY
    for (i in 0 until n) { if (ok(v5[i])) m = max(m, v5[i]); vmax[i] = m }
    val I = DoubleArray(n) { ix?.get(b[it].t) ?: Double.NaN }
    return Prepared(n, C, V, H, L, O, A, m5, m10, m20, v5, a10, a60, vmax, I)
}

private fun maxOf(A: DoubleArray, a: Int, z: Int): Double {
    var m = Double.NEGATIVE_INFINITY
    for (j in max(0, a)..z) if (A[j] > m) m = A[j]
    return m
}

private fun meanOf(A: DoubleArray, a: Int, z: Int): Double {
    var s = 0.0
    for (j in a..z) s += A[j]
    return s / (z - a + 1)
}

/** days since the last 20-day high (a bar whose high tops the 19 bars before it) */
fun sinceHigh(P: Prepared, i: Int): Int {
    var j = i
    val stop = max(20, i - 30)
    while (j >= stop) { if (P.H[j] >= maxOf(P.H, j - 19, j - 1)) return i - j; j-- }
    return 30
}

/** 主升持有的四条：收盘不破 5 日线；三个交易日内刷新过阶段新高；量能堆着；20 日线向上且 5 日线在 20 日线上 */
fun holdAt(P: Prepared, i: Int): Hold {
    P.hold[i]?.let { return it }
    val h = Hold(
        ma5 = P.C[i] >= P.m5[i],
        high = sinceHigh(P, i) <= 3,
        vol = P.a10[i] >= P.a60[i],
        trend = P.m5[i] > P.m20[i] && P.m20[i] >= P.m20[i - 5],
    )
    P.hold[i] = h
    return h
}

class Trig(val burst: Boolean, val dip: Boolean, val fanbao: Boolean, val reclaim: Boolean, val newHigh: Boolean) {
    val any: Boolean get() = burst || dip || fanbao || reclaim || newHigh
    /** names of the triggers that fired, in display order */
    fun names(): List<String> = buildList {
        if (burst) add("爆发日"); if (dip) add("分歧低吸"); if (fanbao) add("反包"); if (reclaim) add("放量上5日线"); if (newHigh) add("放量新高")
    }
}

enum class State { BUY, HOLD, NO }

class Eval(
    val i: Int, val t: String, val c: Double, val chg: Double,
    val m5: Double, val m10: Double, val m20: Double, val bias: Double, val amtx: Double,
    val hold: Hold, val trig: Trig, val buy: Boolean, val state: State, val fails: Int, val notLimDn: Boolean,
    val rs: Boolean, val kd: Boolean, val lead: Boolean, val extreme: Boolean, val tech: Boolean,
    val sell: List<String>, val warn: List<String>, val ret20: Double, val noHigh: Int,
) {
    val pass: Boolean get() = state != State.NO
}

private fun roundHalfUpLikeJs(x: Double): Double = kotlin.math.floor(x + 0.5)

/** every check is computed on bar [i] (the last bar is today's, still forming while the market is open) */
fun evalAt(b: List<Bar>, P: Prepared, i: Int, code: String, name: String?, tech: Boolean): Eval? {
    if (i < 64) return null
    val C = P.C; val H = P.H; val L = P.L; val O = P.O; val A = P.A; val m5 = P.m5; val m10 = P.m10; val m20 = P.m20; val I = P.I
    val bar = b[i]; val c = C[i]
    val lim = limitOf(code, name)
    val chg = c / C[i - 1] - 1
    val limDn = c <= roundHalfUpLikeJs(C[i - 1] * (1 - lim) * 100) / 100 + 0.005
    val aPrev5 = meanOf(A, i - 5, i - 1)
    val hold = holdAt(P, i); val prevHold = holdAt(P, i - 1)
    val c20p = maxOf(C, i - 20, i - 1); val c60p = maxOf(C, i - 60, i - 1); val hi60 = maxOf(H, i - 59, i)
    val trig = Trig(
        // 爆发日：放量大阳（主板 5% / 20cm 7% 以上），突破 20 日收盘高点或从 5 日线下翻上来，收在高位
        burst = chg >= (if (lim >= 0.2) 0.07 else 0.05) && A[i] >= 1.5 * aPrev5 && (c > c20p || C[i - 1] < m5[i - 1]) && c >= bar.h * 0.97,
        // 分歧低吸：昨天还在主升里，今天不涨（≤2%），回踩到 5 日线或 10 日线附近，收盘守住 10 日线
        dip = prevHold.all && chg <= 0.02 && (L[i] <= m5[i] * 1.01 || L[i] <= m10[i] * 1.01) && c >= m10[i] && !limDn,
        // 反包：前一天大阴（跌 3% 以上的阴线），今天收回前一天的开盘价并站上 5 日线
        fanbao = C[i - 1] / C[i - 2] - 1 <= -0.03 && C[i - 1] < O[i - 1] && c > O[i - 1] && c >= m5[i],
        // 放量站上 5 日线：前一天在 5 日线下，今天站上，量至少是前一天的 1.5 倍
        reclaim = C[i - 1] <= m5[i - 1] && c > m5[i] && P.V[i] >= 1.5 * P.V[i - 1],
        // 放量新高：整理两天以上后，收盘再创 60 日新高，成交额比前 5 天均值多两成
        newHigh = c > c60p && A[i] >= 1.2 * aPrev5 && sinceHigh(P, i - 1) >= 2,
    )
    val buy = trig.any && !limDn && c >= m5[i]
    val state = if (buy) State.BUY else if (hold.all) State.HOLD else State.NO
    val fails = listOf("ma5", "high", "vol", "trend").count { !hold[it] }
    // relative strength against the stock's own gate index
    val ixOk = ok(I[i]) && ok(I[i - 20])
    fun ixChg(j: Int) = I[j] / I[j - 1] - 1
    val rs = ixOk && c / C[i - 20] - 1 > I[i] / I[i - 20] - 1
    var kd = false
    if (ixOk && c >= m5[i]) for (j in i - 9..i) if (ok(I[j]) && ok(I[j - 1]) && ixChg(j) <= -0.01 && C[j] / C[j - 1] - 1 >= -0.005) { kd = true; break }
    val lead = ixOk && c > c60p && I[i] < 0.98 * maxOf(I, i - 20, i)
    val extreme = ok(P.v5[i]) && P.v5[i] >= P.vmax[i] * 0.999 && (i >= 120 || P.vmLong)
    // sell / warning signals
    val sell = mutableListOf<String>(); val warn = mutableListOf<String>()
    if (c < m5[i]) { if (C[i - 1] < m5[i - 1]) sell.add("跌破 5 日线后没收回") else sell.add("跌破 5 日线") }
    val noHigh = sinceHigh(P, i); val ret20 = c / C[i - 20] - 1
    if (noHigh > 3) warn.add(if (noHigh >= 30) "一个多月没刷新阶段新高" else "$noHigh 个交易日没刷新阶段新高")
    else if (noHigh >= 2 && ret20 >= 0.3) warn.add("高位 $noHigh 天没新高，先做减法")
    for (j in i - 1 downTo i - 9) {
        if (C[j] / C[j - 1] - 1 >= 0.07 && C[j] > O[j]) {
            if (c < (O[j] + C[j]) / 2) warn.add(b[j].t.substring(5).replaceFirst('-', '/') + " 大阳线回吐过半")
            break
        }
    }
    if (limDn) warn.add("跌停")
    if (ok(I[i]) && ok(I[i - 1]) && ixChg(i) >= 0.015 && chg < 0) warn.add("指数大涨它不涨")
    if (c > c60p && A[i] < 0.8 * aPrev5) warn.add("缩量过前高")
    if (A[i] >= 2 * aPrev5 && abs(chg) <= 0.01 && c >= 0.95 * hi60) warn.add("放量滞涨")
    return Eval(
        i, bar.t, c, chg, m5[i], m10[i], m20[i], c / m5[i] - 1, P.a10[i] / P.a60[i],
        hold, trig, buy, state, fails, !limDn, rs, kd, lead, extreme, tech, sell, warn, ret20, noHigh,
    )
}

/** Evaluate every bar (for the 区间 bands on the chart). Index < 64 gives null. */
fun evalAll(b: List<Bar>, P: Prepared, code: String, name: String?, tech: Boolean): List<Eval?> =
    List(b.size) { if (it >= 64) evalAt(b, P, it, code, name, tech) else null }
