package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.ArrivalRow
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.dimWhileRefreshing
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.StaleNote
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.subway.ui.rememberNow
import com.thelightphone.subway.ui.searchButton
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StationViewModel(
    private val saved: SavedStore,
    private val catalog: suspend () -> Catalog,
    private val stationId: String,
) : LightViewModel<Unit>() {

    val live = LiveBoards(viewModelScope, catalog)

    val isSaved: StateFlow<Boolean> =
        saved.stationIds.map { stationId in it }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val favorites: StateFlow<Set<String>> =
        saved.favoriteRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch(Dispatchers.Default) {
            live.follow(listOfNotNull(catalog().station(stationId)))
        }
    }

    fun toggleSaved() {
        viewModelScope.launch(Dispatchers.Default) { runCatching { saved.toggleStation(stationId) } }
    }

    fun toggleFavorite(route: String) {
        viewModelScope.launch(Dispatchers.Default) { runCatching { saved.toggleRoute(route) } }
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

/** Every train due at one station, listed under the direction it is going. */
class StationScreen(
    sealedActivity: SealedLightActivity,
    private val stationId: String,
) : LightScreen<Unit, StationViewModel>(sealedActivity) {

    override val viewModelClass: Class<StationViewModel> get() = StationViewModel::class.java

    override fun createViewModel(): StationViewModel {
        val readAsset = lightContext::readAsset
        return StationViewModel(Subway.saved(lightContext.dataStore), { Subway.catalog(readAsset) }, stationId)
    }

    private val wheel = WheelBus()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val boards by viewModel.live.boards.collectAsState()
        val isSaved by viewModel.isSaved.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val now by rememberNow()
        val board = boards.firstOrNull()

        SubwayScreen(
            title = board?.station?.name ?: "",
            wheel = wheel,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = listOf(
                searchButton { navigateTo(::SearchScreen) },
                LightBarButton.Text(
                    text = if (isSaved) "UNSAVE" else "SAVE STATION",
                    onClick = { viewModel.toggleSaved() },
                ),
                refreshButton { viewModel.live.refreshNow() },
            ),
        ) {
            if (board != null) StationPage(board, now, favorites) { viewModel.toggleFavorite(it) }
        }
    }
}

@Composable
private fun StationPage(board: Board, now: Long, favorites: Set<String>, onRoute: (String) -> Unit) {
    val station = board.station
    RouteBadgeRow(
        routes = station.routes,
        favorites = favorites,
        onRouteClick = onRoute,
        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
    )
    Note(
        text = station.boroLabel + "  ·  tap a line to favorite",
        modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp(), bottom = 0.75f.gridUnitsAsDp()),
    )

    val groups = board.groups(now)
    when {
        board.failed -> Note("Times unavailable. Check your connection.")
        board.loading && board.arrivals.isEmpty() -> Note("Loading…")
        groups.isEmpty() -> Note("No trains due right now.")
        else -> {
            groups.forEachIndexed { index, group ->
                key(group.heading) {
                FadeIn(order = index) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dimWhileRefreshing()
                        .padding(bottom = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = group.heading,
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                    )
                    group.arrivals.take(PER_DIRECTION).forEach { arrival ->
                        ArrivalRow(
                            arrival = arrival,
                            nowSeconds = now,
                            favorite = Routes.plain(arrival.route) in favorites,
                            modifier = Modifier.padding(vertical = 0.25f.gridUnitsAsDp()),
                        )
                    }
                }
                }
                }
            }
            if (board.stale) StaleNote(board)
        }
    }
}

private const val PER_DIRECTION = 5
