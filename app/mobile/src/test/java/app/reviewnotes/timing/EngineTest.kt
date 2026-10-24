package app.reviewnotes.timing

import app.reviewnotes.timing.data.Bar
import app.reviewnotes.timing.data.Json
import app.reviewnotes.timing.data.arr
import app.reviewnotes.timing.data.get
import app.reviewnotes.timing.data.num
import app.reviewnotes.timing.data.str
import app.reviewnotes.timing.engine.evalAt
import app.reviewnotes.timing.engine.prep
import org.junit.Assert.assertEquals
import org.junit.Test

/** The fixture was produced by the page's own rule engine (index.html); every evaluation must match it. */
class EngineTest {
    @Test
    fun matchesThePage() {
        val text = javaClass.classLoader!!.getResource("engine_fixture.json")!!.readText()
        val j = Json.parse(text)
        val ix = HashMap<String, Double>()
        (j["ix"].arr() ?: emptyList()).forEach { ix[it[0].str()!!] = it[1].num() }
        var bad = 0; var tot = 0
        for (s in j["stocks"].arr()!!) {
            val code = s["code"].str()!!
            val tech = s["tech"] == true
            val bars = s["bars"].arr()!!.map { Bar(it["t"].str()!!, it["o"].num(), it["h"].num(), it["l"].num(), it["c"].num(), it["v"].num()) }
            val P = prep(bars, ix)
            s["res"].arr()!!.forEachIndexed { k, r ->
                val e = evalAt(bars, P, 64 + k, code, "", tech)!!
                val got = listOf(e.state.name.lowercase(), e.fails, e.trig.burst, e.trig.dip, e.trig.fanbao, e.trig.reclaim, e.trig.newHigh, e.rs, e.kd, e.lead, e.extreme, e.sell.joinToString("|"), e.warn.joinToString("|"))
                val exp = listOf(r[0].str(), r[1].num().toInt(), r[2].num() == 1.0, r[3].num() == 1.0, r[4].num() == 1.0, r[5].num() == 1.0, r[6].num() == 1.0, r[7].num() == 1.0, r[8].num() == 1.0, r[9].num() == 1.0, r[10].num() == 1.0, r[11].str(), r[12].str())
                tot++
                if (got != exp) bad++
            }
        }
        assertEquals("mismatches out of $tot", 0, bad)
    }
}
