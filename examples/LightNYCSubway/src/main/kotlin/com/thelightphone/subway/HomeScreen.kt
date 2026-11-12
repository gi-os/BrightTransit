package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.StationBoard
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.subway.ui.rememberNow
import com.thelightphone.subway.ui.searchButton
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val saved: SavedStore,
    private val catalog: suspend () -> Catalog,
) : LightViewModel<Unit>() {

    val live = LiveBoards(viewModelScope, catalog)

    /** False until the saved list has been read once, so the blank state never flashes first. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    val favorites: StateFlow<Set<String>> =
        saved.favoriteRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { saved.bringUpToDate(catalog()) }
            saved.stationIds.collect { ids ->
                val cat = catalog()
                live.follow(ids.mapNotNull { cat.station(it) })
                _ready.value = true
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        live.resume()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        live.pause()
    }

    override fun onAppPause() {
        super.onAppPause()
        live.pause()
    }
}

/** The tool opens here: the stations you saved, with the next trains at each. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel> get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel {
        CrashReporter.install(lightContext.filesDir)
        val readAsset = lightContext::readAsset
        return HomeViewModel(Subway.saved(lightContext.dataStore)) { Subway.catalog(readAsset) }
    }

    private val wheel = WheelBus()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val lastCrash = remember { CrashReporter.lastCrash() }
        var showCrash by remember { mutableStateOf(lastCrash != null) }
        if (showCrash && lastCrash != null) {
            CrashReport(lastCrash) { CrashReporter.clear(); showCrash = false }
            return
        }

        val ready by viewModel.ready.collectAsState()
        val boards by viewModel.live.boards.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val now by rememberNow()

        SubwayScreen(
            title = "Subway Times",
            wheel = wheel,
            bottom = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.DIRECTIONS_ARRIVAL,
                    onClick = { navigateTo(::NearbyScreen) },
                    contentDescription = "Nearby stations",
                ),
                searchButton { navigateTo(::SearchScreen) },
                refreshButton { viewModel.live.refreshNow() },
            ),
            refreshing = refreshing,
            message = if (ready && boards.isEmpty()) {
                "No saved stations yet.\n\nSearch to find your favorite stops and save them for easy access."
            } else {
                null
            },
        ) {
            boards.forEachIndexed { index, board ->
                key(board.station.id) {
                    FadeIn(order = index) {
                        StationBoard(
                            board = board,
                            nowSeconds = now,
                            favorites = favorites,
                            onOpen = { navigateTo({ StationScreen(it, board.station.id) }) },
                        )
                    }
                }
            }
        }
    }

    /** The last crash, readable on the phone itself, which has no easy way to pull a log. */
    @Composable
    private fun CrashReport(trace: String, onDismiss: () -> Unit) {
        SubwayScreen(
            title = "Last error",
            wheel = wheel,
            bottom = listOf(LightBarButton.Text(text = "DISMISS", onClick = onDismiss)),
        ) {
            LightText(text = trace, variant = LightTextVariant.Detail)
        }
    }
}
