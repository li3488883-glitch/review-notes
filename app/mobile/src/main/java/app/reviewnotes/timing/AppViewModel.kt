package app.reviewnotes.timing

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.reviewnotes.timing.data.Bar
import app.reviewnotes.timing.data.Em
import app.reviewnotes.timing.data.Market
import app.reviewnotes.timing.data.Repo
import app.reviewnotes.timing.data.ScanResult
import app.reviewnotes.timing.data.WatchItem
import app.reviewnotes.timing.engine.Bj
import app.reviewnotes.timing.engine.Eval
import app.reviewnotes.timing.engine.Session
import app.reviewnotes.timing.engine.StockResult
import app.reviewnotes.timing.engine.evalAll
import app.reviewnotes.timing.engine.isTech
import app.reviewnotes.timing.engine.prep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class ScanMode(val label: String) { OK("买点 + 主升"), BUY("只看买点"), NEAR("差一条"), ALL("全部候选") }

class DetailState(
    val id: String, val code: String, val name: String,
    val bars: List<Bar>, val evals: List<Eval?>, val result: StockResult?, val tech: Boolean,
    val loading: Boolean = false, val error: String? = null,
)

data class UiState(
    val market: Market? = null,
    val marketError: String? = null,
    val scan: ScanResult? = null,
    val scanError: String? = null,
    val scanMode: ScanMode = ScanMode.OK,
    val watch: List<WatchItem> = emptyList(),
    val watchResults: Map<String, StockResult> = emptyMap(),
    val detail: DetailState? = null,
    val refreshing: Boolean = false,
    val updatedAt: String = "",
    val session: Session = Bj.session(),
    val status: String = "",
    val hits: List<Em.Hit> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo()
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(UiState(watch = loadWatch(), scanMode = runCatching { ScanMode.valueOf(prefs.getString("mode", "OK")!!) }.getOrDefault(ScanMode.OK)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val busy = Mutex()
    private var lastScan = 0L
    private var loop: Job? = null

    private fun loadWatch(): List<WatchItem> = (prefs.getString("watch", "") ?: "").split(';').filter { it.isNotBlank() }.mapNotNull {
        val p = it.split('|'); if (p.size >= 2 && Regex("^[01]\\.\\d{6}$").matches(p[0])) WatchItem(p[0], p[0].substring(2), p.getOrElse(1) { p[0].substring(2) }) else null
    }
    private fun saveWatch(w: List<WatchItem>) = prefs.edit().putString("watch", w.joinToString(";") { it.id + "|" + it.name.replace("|", "").replace(";", "") }).apply()

    fun inWatch(id: String) = _ui.value.watch.any { it.id == id }
    fun toggleWatch(id: String, code: String, name: String) {
        val w = _ui.value.watch
        val next = if (w.any { it.id == id }) w.filter { it.id != id } else w + WatchItem(id, code, name)
        saveWatch(next)
        _ui.update { it.copy(watch = next) }
        viewModelScope.launch { refreshWatch() }
    }

    private var searchJob: Job? = null
    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { _ui.update { it.copy(hits = emptyList()) }; return }
        searchJob = viewModelScope.launch {
            delay(250)
            val hits = runCatching { Em.suggest(q.trim()) }.getOrDefault(emptyList())
            _ui.update { it.copy(hits = hits) }
        }
    }

    fun setMode(m: ScanMode) { prefs.edit().putString("mode", m.name).apply(); _ui.update { it.copy(scanMode = m) } }

    /** Called when the app comes to the foreground: refresh now, then every 10 seconds while the market is open. */
    fun start() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            refresh(full = true)
            while (isActive) {
                delay(10_000)
                if (Bj.session() == Session.OPEN) refresh(full = false) else _ui.update { it.copy(session = Bj.session()) }
            }
        }
    }
    fun stop() { loop?.cancel(); loop = null }

    fun refreshNow() { viewModelScope.launch { refresh(full = true) } }

    private suspend fun refresh(full: Boolean) {
        if (!busy.tryLock()) return
        _ui.update { it.copy(refreshing = true) }
        val errs = mutableListOf<String>()
        try {
            try { val m = withContext(Dispatchers.Default) { repo.market() }; _ui.update { it.copy(market = m, marketError = null) } } catch (e: Exception) { errs += "大势"; _ui.update { it.copy(marketError = e.message ?: "没拉到") } }
            try { withContext(Dispatchers.Default) { refreshWatch() } } catch (e: Exception) { errs += "自选" }
            if (full || System.currentTimeMillis() - lastScan > 28_000) {
                try { val s = withContext(Dispatchers.Default) { repo.scan() }; lastScan = System.currentTimeMillis(); _ui.update { it.copy(scan = s, scanError = null) } }
                catch (e: Exception) { errs += "选股"; _ui.update { it.copy(scanError = e.message ?: "没拉到") } }
            }
            _ui.value.detail?.let { d -> runCatching { withContext(Dispatchers.Default) { loadDetail(d.id, d.code, d.name, quiet = true) } } }
        } finally {
            val s = Bj.session()
            _ui.update {
                it.copy(refreshing = false, updatedAt = Bj.hms(), session = s,
                    status = (if (errs.isNotEmpty()) "部分数据没拉到（" + errs.joinToString("、") + "）· " else "") + "更新于北京时间 " + Bj.hms() + " · " + s.label)
            }
            busy.unlock()
        }
    }

    private suspend fun refreshWatch() {
        val w = _ui.value.watch
        if (w.isEmpty()) { _ui.update { it.copy(watchResults = emptyMap()) }; return }
        val snap = runCatching { Em.snap(w.map { it.id }) }.getOrDefault(emptyMap())
        val out = HashMap<String, StockResult>()
        for (item in w) {
            runCatching { repo.evaluate(item.id, item.code, item.name, snap[item.id]).first }.getOrNull()?.let { out[item.id] = it }
        }
        _ui.update { it.copy(watchResults = out) }
    }

    fun openDetail(id: String, code: String, name: String) {
        _ui.update { it.copy(detail = DetailState(id, code, name, emptyList(), emptyList(), null, isTech(code, repo.industry(id)), loading = true)) }
        viewModelScope.launch { runCatching { withContext(Dispatchers.Default) { loadDetail(id, code, name, quiet = false) } }.onFailure { e -> _ui.update { s -> s.copy(detail = s.detail?.let { DetailState(it.id, it.code, it.name, it.bars, it.evals, it.result, it.tech, false, "这只股票的行情暂时没拉到（${e.message}），稍后再试。") }) } } }
    }
    fun closeDetail() { _ui.update { it.copy(detail = null) } }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun loadDetail(id: String, code: String, name: String, quiet: Boolean) {
        val q = runCatching { Em.snap(listOf(id))[id] }.getOrNull()
        q?.let { repo.patch(it) }
        val (nm, bars) = repo.klineCached(id, 750)
        val ind = repo.industry(id)
        val tech = isTech(code, ind)
        val maps = repo.indexMaps
        val P = prep(bars, if (tech) maps.tech else maps.other)
        val evals = evalAll(bars, P, code, nm.ifEmpty { name }, tech)
        val (res, _) = repo.evaluate(id, code, nm.ifEmpty { name }, q)
        if (_ui.value.detail?.id != id) return
        _ui.update { it.copy(detail = DetailState(id, code, nm.ifEmpty { name }, bars, evals, res, tech)) }
    }
}
