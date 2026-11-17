package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.ServiceAlert
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.ArrivalRow
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.dimWhileRefreshing
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.StaleNote
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.clockLabel
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
        val snapshot by viewModel.live.alerts.collectAsState()
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
            if (board != null) {
                val station = board.station
                // Worked out again only when the alerts or the clock change. The clock moves
                // every ten seconds, which is what starts and ends an alert on screen.
                val alerts = remember(snapshot, station.id, now) { snapshot.at(station, now) }
                StationPage(
                    board = board,
                    alerts = alerts,
                    alertsAsOf = snapshot.checkedAt.takeIf { snapshot.stale(now) },
                    now = now,
                    favorites = favorites,
                    onRoute = { viewModel.toggleFavorite(it) },
                )
            }
        }
    }
}

/**
 * [alerts] are the ones in effect at this station, worst first. [alertsAsOf] is when they
 * were fetched, set only once that is long enough ago to be worth saying.
 */
@Composable
private fun StationPage(
    board: Board,
    alerts: List<ServiceAlert>,
    alertsAsOf: Long?,
    now: Long,
    favorites: Set<String>,
    onRoute: (String) -> Unit,
) {
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

    // Alerts sit outside the choice below, so they read the same whether the times are
    // loading, missing or all there. A dead board is when "no L between..." matters most.
    // Only the first two go above the trains, so the times stay near the top of the page.
    // The rest follow the times, where the wheel reaches them.
    if (alerts.isNotEmpty()) {
        AlertParagraphs(alerts.take(ALERTS_ON_TOP))
        val below = alerts.size - ALERTS_ON_TOP
        if (below > 0) Note(if (below == 1) "1 more alert below." else "$below more alerts below.")
        if (alertsAsOf != null) Note("Alerts as of ${clockLabel(alertsAsOf)}.")
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
    }

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

    if (alerts.size > ALERTS_ON_TOP) {
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
        AlertParagraphs(alerts.drop(ALERTS_ON_TOP))
    }
}

/**
 * Each alert in the MTA's own words, whole. They are never cut short and never dimmed with
 * the times: half a sentence about a closed line is worse than a longer page.
 */
@Composable
private fun AlertParagraphs(alerts: List<ServiceAlert>) {
    alerts.forEach { alert ->
        key(alert.id) {
            FadeIn {
                LightText(
                    text = alert.text,
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}

private const val PER_DIRECTION = 5

/** How many alerts go above the train times. */
private const val ALERTS_ON_TOP = 2
