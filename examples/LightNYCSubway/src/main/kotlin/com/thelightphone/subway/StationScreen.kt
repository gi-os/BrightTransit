package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Change
import com.thelightphone.subway.data.Direct
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Planner
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.ServiceAlert
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.BoardNotes
import com.thelightphone.subway.ui.ChangeRow
import com.thelightphone.subway.ui.DirectRow
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.StationLabel
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.TrainRow
import com.thelightphone.subway.ui.clockLabel
import com.thelightphone.subway.ui.dimWhileRefreshing
import com.thelightphone.subway.ui.namesScheduledTrains
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.subway.ui.rowsWith
import com.thelightphone.subway.ui.searchButton
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StationViewModel(
    private val saved: SavedStore,
    private val catalog: suspend () -> Catalog,
    private val stationId: String,
    from: String? = null,
) : LightViewModel<Unit>() {

    val live = LiveBoards(viewModelScope, catalog)

    /**
     * The way here from the station this one was searched for from. Opened any other way,
     * a station's page has no such thing and is as it always was.
     */
    val way: LivePlan? = from?.takeIf { it != stationId }?.let { LivePlan(viewModelScope, catalog) }

    /** The stations, once parsed. The way here names its platforms from them, and the station it starts at. */
    private val _names = MutableStateFlow<Catalog?>(null)
    val names: StateFlow<Catalog?> = _names

    /** The station the way here starts at, by id, or null for a page that has no such thing. */
    val origin: String? = from.takeIf { way != null }

    val isSaved: StateFlow<Boolean> =
        saved.stationIds.map { stationId in it }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val favorites: StateFlow<Set<String>> =
        saved.favoriteRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Taken as true until read, so the hint about trains does not flash up for someone who has no need of it. */
    val openedATrain: StateFlow<Boolean> =
        saved.openedATrain.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // Started on the main thread and, with the stations already parsed, finished there
        // before the page is first drawn: a station opened from Home has Home's times on
        // its first frame. The work is picking one station's trains out of feeds already
        // in memory, and nothing here waits on the network.
        viewModelScope.launch {
            live.follow(listOfNotNull(catalog().station(stationId)))
        }
        // The same goes for the way here: worked out from feeds already in memory when they
        // are enough, and otherwise left until they have been fetched.
        if (way != null && from != null) {
            viewModelScope.launch {
                _names.value = catalog()
                way.follow(from, stationId)
            }
        }
    }

    /** The refresh button: the trains leaving here and the way here are fetched again together. */
    fun refresh() {
        live.refreshNow()
        way?.refreshNow()
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
        way?.resume()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        live.pause()
        way?.pause()
    }

    override fun onAppPause() {
        super.onAppPause()
        live.pause()
        way?.pause()
    }
}

/**
 * Every train due at one station, listed under the direction it is going.
 *
 * [from] is the station whose page the rider searched from to find this one, if that is
 * how they came. Then the page opens with the way here from there, when a train runs
 * direct, and a search made from this page hands this station on in turn.
 */
class StationScreen(
    sealedActivity: SealedLightActivity,
    private val stationId: String,
    private val from: String? = null,
) : LightScreen<Unit, StationViewModel>(sealedActivity) {

    override val viewModelClass: Class<StationViewModel> get() = StationViewModel::class.java

    override fun createViewModel(): StationViewModel {
        val readAsset = lightContext::readAsset
        return StationViewModel(Subway.saved(lightContext.dataStore), { Subway.catalog(readAsset) }, stationId, from)
    }

    private val wheel = WheelBus()
    private val page = Page()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val boards by viewModel.live.boards.collectAsState()
        val isSaved by viewModel.isSaved.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val openedATrain by viewModel.openedATrain.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val snapshot by viewModel.live.alerts.collectAsState()
        val now by viewModel.live.now.collectAsState()
        val names by viewModel.names.collectAsState()
        val board = boards.firstOrNull()
        val way = viewModel.way
        val following = way?.following?.collectAsState()?.value
        val wayNow = way?.now?.collectAsState()?.value
        val origin = viewModel.origin?.let { names?.station(it) }

        SubwayScreen(
            title = board?.station?.name ?: "",
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = listOf(
                searchButton { navigateTo({ SearchScreen(it, from = stationId) }) },
                LightBarButton.Text(
                    text = if (isSaved) "UNSAVE" else "SAVE STATION",
                    onClick = { viewModel.toggleSaved() },
                ),
                refreshButton { viewModel.refresh() },
            ),
        ) {
            if (board != null) {
                val station = board.station
                val catalog = names
                // Worked out again only when the plan or its clock changes, like the alerts below.
                val said = remember(following, wayNow) { if (wayNow != null) WayHere.of(following, wayNow) else null }
                val wayHere = if (said != null && origin != null && catalog != null && wayNow != null) {
                    WayOnPage(said, origin, station, following?.plan, wayNow, catalog, page) { navigateTo({ DirectScreen(it, origin.id, station.id) }) }
                } else {
                    null
                }
                // Worked out again only when the alerts or the clock change. The clock moves
                // every ten seconds, which is what starts and ends an alert on screen.
                val alerts = remember(snapshot, station.id, now) { snapshot.at(station, now) }
                StationPage(
                    board = board,
                    alerts = alerts,
                    alertsAsOf = snapshot.checkedAt.takeIf { snapshot.stale(now) },
                    now = now,
                    favorites = favorites,
                    hint = StationHint.of(station.boroLabel, favorites.isNotEmpty(), openedATrain),
                    onRoute = { viewModel.toggleFavorite(it) },
                    onTrains = { trains -> navigateTo({ TrainScreen(it, trains) }) },
                    wayHere = wayHere,
                )
            }
        }
    }
}

/**
 * The grey line under a station's bullets: its borough, and what on the page can be tapped.
 * Each half of the hint has done its work once the thing has been tapped, and is not said
 * again: a line to make it a favorite, a train to see its stops and follow it.
 */
internal object StationHint {
    fun of(borough: String, hasFavorites: Boolean, openedATrain: Boolean): String = when {
        !hasFavorites && !openedATrain -> "$borough  ·  tap a line to favorite, a train to follow"
        !hasFavorites -> "$borough  ·  tap a line to favorite"
        !openedATrain -> "$borough  ·  tap a train to follow it"
        else -> borough
    }
}

/**
 * What a station's page says about getting to it from the station the rider searched from.
 * It is the one thing on the page that is not about trains leaving here. So it opens with
 * where it starts, and the trains below it carry a line of their own that says they start
 * from this station.
 *
 * A page opened from another station always says one of these, from its first frame: the
 * search screen has just told the rider that it will.
 */
internal sealed interface WayHere {
    /**
     * The first train of those worth choosing from. [others] is how many more there are,
     * ways with a change included, and [unchecked] the lines that come here too and could
     * not be looked at.
     */
    data class Trains(val first: Direct, val others: Int, val unchecked: List<String>) : WayHere

    /**
     * No direct train to name, and a way with a change to name instead: the first of
     * those worth choosing from. [others] and [unchecked] are as for [Trains]. [checked]
     * is true only when every feed a direct train could ride in was looked in, which is
     * the only time "no direct train" can be said.
     */
    data class Changed(val first: Change, val others: Int, val unchecked: List<String>, val checked: Boolean) : WayHere

    /** No train to name, because not everything could be looked at. [lines] are the ones that come here, where any are known to. */
    data class Unchecked(val lines: List<String>) : WayHere

    /** No train runs here direct right now. */
    data object NoTrain : WayHere

    /** The answer is on its way. */
    data object Checking : WayHere

    companion object {
        /**
         * Null only for a page that was not opened from another station. A plan too old to
         * offer anything is being checked again while a new one is on its way: it has not
         * failed to check a line, it has not been asked yet. "No direct train" stays said
         * through a refresh, as a train does.
         */
        fun of(following: LivePlan.Following?, now: Long): WayHere? {
            following ?: return null
            val changes = following.plan?.changes(now).orEmpty()
            return when (val view = following.plan?.view(now)) {
                is Plan.View.Trains -> Trains(view.choices.first(), view.choices.size - 1 + changes.size, view.unchecked)
                is Plan.View.Unchecked -> when {
                    following.loading -> Checking
                    changes.isNotEmpty() -> Changed(changes.first(), changes.size - 1, view.lines, checked = false)
                    else -> Unchecked(view.lines)
                }
                Plan.View.None -> if (changes.isNotEmpty()) Changed(changes.first(), changes.size - 1, emptyList(), checked = true) else NoTrain
                null -> Checking
            }
        }
    }
}

/**
 * What it takes to draw the way here: what to say, the stations it runs between, the plan
 * and time it was said of, and what a tap on it does. [page] keeps the room the block took.
 */
private class WayOnPage(
    val said: WayHere,
    val from: Station,
    val to: Station,
    val plan: Plan?,
    val now: Long,
    val names: Catalog,
    val page: Page,
    val onOpen: () -> Unit,
)

/**
 * The way here, as one block that opens the list of trains behind it.
 *
 * The block keeps its room, so the alerts and trains under it stay where they are while it
 * is being worked out, and when a line cannot be checked for a moment. Once it has named a
 * train it is never shorter than it was then. Before that, between two stations that share
 * a line, it holds the room one train takes: a train is what will most likely fill it.
 * With no train to name after all, it gives the room up.
 */
@Composable
private fun WayHereBlock(way: WayOnPage) {
    val bullets = way.names.hasNamesake(way.from)
    val room = way.page.wayRoom
    val held = with(LocalDensity.current) { (room ?: 0).toDp() }
    FadeIn {
        when (val said = way.said) {
            is WayHere.Trains -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Measured with the space under it, which is part of the room it takes on the page.
                    .onSizeChanged { way.page.wayRoom = it.height }
                    .lightClickable(onClick = way.onOpen)
                    .padding(bottom = 0.75f.gridUnitsAsDp()),
            ) {
                StationLabel(PlanWords.from(way.from), way.from, bullets)
                Column(modifier = Modifier.dimWhileRefreshing()) {
                    if (way.plan != null) DirectRow(said.first.route, PlanWords.row(said.first, way.plan, way.now, way.names, here = true))
                    if (said.others > 0) Note(PlanWords.others(said.others))
                    if (said.unchecked.isNotEmpty()) Note(PlanWords.unchecked(said.unchecked))
                }
                // On a line of its own. Beside a name that is long or carries its bullets it would run over.
                Note(PlanWords.TAP)
            }
            is WayHere.Changed -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { way.page.wayRoom = it.height }
                    .lightClickable(onClick = way.onOpen)
                    .padding(bottom = 0.75f.gridUnitsAsDp()),
            ) {
                StationLabel(PlanWords.from(way.from), way.from, bullets)
                Note(if (said.checked) PlanWords.NONE_HERE else PlanWords.unchecked(said.unchecked))
                Column(modifier = Modifier.dimWhileRefreshing()) {
                    if (way.plan != null) ChangeRow(said.first.first.route, said.first.second.route, PlanWords.change(said.first, way.plan, way.now, way.names, here = true))
                    if (said.others > 0) Note(PlanWords.others(said.others))
                }
                Note(PlanWords.TAP)
            }
            WayHere.NoTrain -> Column(modifier = Modifier.fillMaxWidth().padding(bottom = 0.75f.gridUnitsAsDp())) {
                SideEffect { way.page.wayRoom = 0 }
                StationLabel(PlanWords.from(way.from), way.from, bullets)
                Note(PlanWords.NONE_HERE)
            }
            is WayHere.Unchecked, WayHere.Checking -> Box(modifier = Modifier.fillMaxWidth().heightIn(min = held)) {
                // Drawn but not seen: one train's worth of room, in whatever typeface the phone has.
                if (room == null && Planner.shared(way.from, way.to).isNotEmpty()) WayHereRoom(way.from, bullets)
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 0.75f.gridUnitsAsDp())) {
                    StationLabel(PlanWords.from(way.from), way.from, bullets)
                    Note(if (said is WayHere.Unchecked) PlanWords.unchecked(said.lines) else Board.View.Loading.text)
                }
            }
        }
    }
}

/** The room a block that names one train takes, with nothing in it to read. */
@Composable
private fun WayHereRoom(from: Station, bullets: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 0.75f.gridUnitsAsDp()).alpha(0f).clearAndSetSemantics { }) {
        StationLabel(PlanWords.from(from), from, bullets)
        DirectRow(from.routes.firstOrNull().orEmpty(), PlanWords.Row(leaves = "", arrives = "", detail = "", warning = null, sure = true))
        Note("")
    }
}

/**
 * [alerts] are the ones in effect at this station, worst first. [alertsAsOf] is when they
 * were fetched, set only once that is long enough ago to be worth saying. [onTrains] is
 * handed the trains of a row that was tapped. [wayHere] is the way here from another
 * station, when there is one to show.
 */
@Composable
private fun StationPage(
    board: Board,
    alerts: List<ServiceAlert>,
    alertsAsOf: Long?,
    now: Long,
    favorites: Set<String>,
    hint: String,
    onRoute: (String) -> Unit,
    onTrains: (List<TrainRef>) -> Unit,
    wayHere: WayOnPage?,
) {
    val station = board.station
    RouteBadgeRow(routes = station.routes, favorites = favorites, onRouteClick = onRoute)
    Note(text = hint, modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()))
    if (wayHere != null) WayHereBlock(wayHere)

    // Alerts sit outside the choice below, so they read the same whether the times are
    // loading, missing or all there. A dead board is when "no L between..." matters most.
    val placed = AlertPlaces.of(alerts)
    val asOf = alertsAsOf?.let { "Alerts as of ${clockLabel(it)}." }
    if (placed.above.isNotEmpty()) {
        AlertParagraphs(placed.above)
        val more = placed.below.size
        if (more > 0) Note(if (more == 1) "1 more alert below." else "$more more alerts below.")
        if (asOf != null) Note(asOf)
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
    }

    // Under a block about getting here, the trains leaving here are named as that.
    if (wayHere != null) {
        StationLabel(PlanWords.from(station), station, wayHere.names.hasNamesake(station), Modifier.padding(bottom = 0.25f.gridUnitsAsDp()))
    }
    when (val view = board.view(now)) {
        is Board.View.Message -> Note(view.text)
        is Board.View.Trains -> FadeIn {
            Column(modifier = Modifier.fillMaxWidth()) {
                BoardNotes(view)
                Column(modifier = Modifier.dimWhileRefreshing()) {
                    view.groups.forEach { group ->
                        key(group.heading) {
                            LightText(text = group.heading, variant = LightTextVariant.Heading)
                            group.rowsWith(favorites).forEach { row ->
                                key(row.route, row.destination) {
                                    // A row opens its next train. The ones after it are a button away from there.
                                    val trains = TrainRef.of(row.arrivals)
                                    TrainRow(row, group.heading, now, view.stale, onClick = if (trains.isEmpty()) null else ({ onTrains(trains) }))
                                }
                            }
                            Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                        }
                    }
                }
                if (view.namesScheduledTrains()) {
                    Note("Lighter times are from the timetable.", Modifier.padding(bottom = 0.5f.gridUnitsAsDp()))
                }
            }
        }
    }

    if (placed.below.isNotEmpty()) {
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
        AlertParagraphs(placed.below)
        if (placed.above.isEmpty() && asOf != null) Note(asOf)
    }
}

/**
 * Where a station's alerts go on its page: [above] the times or [below] them. Only the
 * first two go above, so the times stay near the top; the rest follow the times, where the
 * wheel reaches them. A station notice is never one of the two. A closed entrance can stay
 * posted for months, and would hold the times down the whole while. Both keep the order
 * the alerts came in, worst first.
 */
internal class AlertPlaces(val above: List<ServiceAlert>, val below: List<ServiceAlert>) {
    companion object {
        /** How many alerts go above the train times. */
        const val ABOVE = 2

        fun of(alerts: List<ServiceAlert>): AlertPlaces {
            val above = alerts.filterNot { it.notice }.take(ABOVE)
            return AlertPlaces(above, alerts.filter { it !in above })
        }
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
