package app.reviewnotes.timing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reviewnotes.timing.engine.StockResult
import app.reviewnotes.timing.ui.glass.GlassNavBar
import app.reviewnotes.timing.ui.glass.GlassPullRefresh
import app.reviewnotes.timing.ui.glass.LensState
import app.reviewnotes.timing.ui.glass.Stage
import app.reviewnotes.timing.ui.glass.liquidLensContent
import app.reviewnotes.timing.ui.screens.DetailScreen
import app.reviewnotes.timing.ui.screens.HomeScreen
import app.reviewnotes.timing.ui.screens.ScanScreen
import app.reviewnotes.timing.ui.screens.WatchScreen
import app.reviewnotes.timing.ui.theme.AppTheme
import app.reviewnotes.timing.ui.theme.Ink

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { AppRoot(vm) }
    }

    override fun onStart() { super.onStart(); vm.start() }
    override fun onStop() { vm.stop(); super.onStop() }
}

@OptIn(ExperimentalSharedTransitionApi::class)
class SharedScopes(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalShared = staticCompositionLocalOf<SharedScopes?> { null }

/** The list card and the detail header are the same element: the card grows into the page. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(key: String): Modifier {
    val s = LocalShared.current ?: return this
    return with(s.shared) {
        this@sharedCard.sharedBounds(
            rememberSharedContentState("card-$key"), s.visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 320f) },
        )
    }
}

private val TABS = listOf("大势", "选股", "自选")

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lens = remember { LensState() }
    // list positions survive opening and closing a stock
    val homeList = rememberLazyListState(); val scanList = rememberLazyListState(); val watchList = rememberLazyListState()
    val glow = when { ui.market == null -> Ink.CoolLight; ui.market!!.dsOk -> Ink.Up; else -> Ink.Down }
    val status = WindowInsets.statusBars.asPaddingValues()
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val listPadding = PaddingValues(top = status.calculateTopPadding() + 12.dp, bottom = nav.calculateBottomPadding() + 100.dp)
    val open: (StockResult) -> Unit = { vm.openDetail(it.id, it.code, it.name) }
    val star: (StockResult) -> Unit = { vm.toggleWatch(it.id, it.code, it.name) }

    // While the detail page animates out, ui.detail is already null: the leaving page keeps
    // drawing the detail it had, so the header can shrink back into its card (and the list
    // with its nav bar is composed only once).
    val lastDetail = remember { arrayOfNulls<DetailState>(1) }
    ui.detail?.let { lastDetail[0] = it }

    BackHandler(enabled = ui.detail != null) { vm.closeDetail() }

    AppTheme(glow) {
        Stage(Modifier.fillMaxSize()) {
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = ui.detail?.id,
                    transitionSpec = {
                        (fadeIn(tween(260)) + scaleIn(initialScale = 0.98f)) togetherWith (fadeOut(tween(200)) + scaleOut(targetScale = 0.98f))
                    },
                    label = "page",
                ) { detailId ->
                    CompositionLocalProvider(LocalShared provides SharedScopes(this@SharedTransitionLayout, this@AnimatedContent)) {
                        val d = if (detailId == null) null else (ui.detail ?: lastDetail[0])?.takeIf { it.id == detailId }
                        if (d != null) {
                            DetailScreen(
                                d, ui.watch.any { it.id == d.id }, listPadding,
                                onBack = { vm.closeDetail() }, onStar = { vm.toggleWatch(d.id, d.code, d.name) },
                                headerModifier = Modifier.sharedCard(d.id),
                            )
                        } else {
                            Box(Modifier.fillMaxSize()) {
                                GlassPullRefresh(ui.refreshing, { vm.refreshNow() }, Modifier.fillMaxSize().liquidLensContent(lens)) {
                                    AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tab") { t ->
                                        when (t) {
                                            0 -> HomeScreen(ui, listPadding, homeList) { vm.refreshNow() }
                                            1 -> ScanScreen(ui, listPadding, { vm.setMode(it) }, open, star, { id -> ui.watch.any { it.id == id } }, { Modifier.sharedCard(it.id) }, scanList)
                                            else -> WatchScreen(ui, listPadding, { vm.search(it) }, { h -> vm.openDetail(h.id, h.code, h.name) }, open, star, { Modifier.sharedCard(it.id) }, watchList)
                                        }
                                    }
                                }
                                GlassNavBar(TABS, tab, { tab = it }, lens, Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
            }
        }
    }
}
