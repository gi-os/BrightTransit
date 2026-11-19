package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.Bullet
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.RouteBadge
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.dimWhileRefreshing
import com.thelightphone.subway.ui.notLive
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A ride in progress: which train, and which stop. What becomes of it is [LiveRide]'s to
 * say. Leaving the screen ends it.
 */
class RideViewModel(
    saved: SavedStore,
    catalog: suspend () -> Catalog,
    start: Ride,
    resumed: Boolean,
) : LightViewModel<Unit>() {

    val ride = LiveRide(viewModelScope, catalog, saved, start, resumed)

    private val _names = MutableStateFlow<Catalog?>(null)
    val names: StateFlow<Catalog?> = _names

    init {
        viewModelScope.launch { _names.value = catalog() }
    }

    /** The rider says they are on [train]. */
    fun switchTo(train: Train) {
        viewModelScope.launch { ride.switchTo(train) }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        ride.shown()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        ride.hidden()
    }

    override fun onAppPause() {
        super.onAppPause()
        ride.paused()
    }
}

/**
 * One statement about one train: how many stops until the rider gets off. It is made to be
 * read in a glance and put away again, so it never scrolls and has one thing in large type.
 *
 * The tool cannot keep the screen on, buzz, or know which train the rider is really on. It
 * is right when looked at, says how old its facts are, and leaves the rest to the rider.
 *
 * [resumed] is set for a ride the tool was closed on and has opened on again.
 */
class RideScreen(
    sealedActivity: SealedLightActivity,
    private val start: Ride,
    private val resumed: Boolean = false,
) : LightScreen<Unit, RideViewModel>(sealedActivity) {

    override val viewModelClass: Class<RideViewModel> get() = RideViewModel::class.java

    override fun createViewModel(): RideViewModel {
        val readAsset = lightContext::readAsset
        return RideViewModel(Subway.saved(lightContext.dataStore), { Subway.catalog(readAsset) }, start, resumed)
    }

    private val wheel = WheelBus()
    private val page = Page()

    /**
     * Leaving the screen ends the ride, by the arrow or by the phone's own back. This is
     * not called when LightOS closes the tool with the ride still showing, which is the
     * case the ride is saved for.
     */
    override fun onScreenDestroy() {
        viewModel.ride.end()
    }

    @Composable
    override fun Content() {
        val ride by viewModel.ride.ride.collectAsState()
        val showing by viewModel.ride.showing.collectAsState()
        val refreshing by viewModel.ride.live.refreshing.collectAsState()
        val now by viewModel.ride.live.now.collectAsState()
        val catalog by viewModel.names.collectAsState()
        val names = catalog

        // A ride that ran out while the tool was away is not shown again.
        LaunchedEffect(showing) {
            if (showing == LiveRide.Showing.OVER) goBack()
        }

        SubwayScreen(
            title = names?.let { RideWords.name(ride.train, it) }.orEmpty(),
            subtitle = names?.let { "from ${it.stopName(ride.from)}" }.orEmpty(),
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            refreshing = refreshing,
            still = true,
            bottom = listOf(
                null,
                LightBarButton.Text(
                    text = "OTHER TRAIN",
                    onClick = { navigateTo<Train>({ OtherTrainScreen(it, ride) }, { chosen -> viewModel.switchTo(chosen) }) },
                ),
                refreshButton { viewModel.ride.live.refreshNow() },
            ),
            // While the train of a ride that looks over is asked about, nothing of the ride is said.
            message = if (showing == LiveRide.Showing.ASKING) Board.View.Loading.text else null,
        ) {
            if (names != null && showing == LiveRide.Showing.RIDE) RideStatement(RideWords.statement(ride, now, names))
        }
    }
}

/**
 * The statement, largest first. With a figure, the figure is the size of the LightOS clock
 * and the headline finishes its sentence. Without one the headline is the statement.
 */
@Composable
private fun RideStatement(says: RideWords.Statement) {
    Column(modifier = Modifier.fillMaxWidth().dimWhileRefreshing()) {
        if (says.figure != null) {
            LightText(text = says.figure, variant = LightTextVariant.Title, lighten = !says.sure, maxLines = 1)
            LightText(text = says.headline, variant = LightTextVariant.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            LightText(text = says.headline, variant = LightTextVariant.Subtitle, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
        // The page does not scroll, and long names can ask for more room than it has. The
        // sentences take what the note leaves, so it is the last grey aside that is cut
        // short and never the note: a grey count with nothing under it to say why is worse.
        Column(modifier = Modifier.weight(1f, fill = false)) {
            says.body.forEach { LightText(text = it, variant = LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            says.aside.forEach { LightText(text = it, variant = LightTextVariant.Copy, lighten = true, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (says.note != null) Note(says.note, Modifier.padding(top = 0.75f.gridUnitsAsDp()))
    }
}

/** The list of other trains for a ride. What it holds and when it asks again is [LiveOtherTrains]'s to say. */
class OtherTrainViewModel(
    catalog: suspend () -> Catalog,
    ride: Ride,
) : LightViewModel<Train>() {

    val live = LiveOtherTrains(viewModelScope, catalog, ride)

    private val _names = MutableStateFlow<Catalog?>(null)
    val names: StateFlow<Catalog?> = _names

    init {
        viewModelScope.launch { _names.value = catalog() }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Train>) {
        super.onScreenShow(screen)
        live.resume()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Train>) {
        super.onScreenHide(screen)
        live.pause()
    }

    override fun onAppPause() {
        super.onAppPause()
        live.pause()
    }
}

/** Which trains a rider could be on instead of the one they are following. */
object OtherTrains {
    /** No list of trains to pick from is longer than this. */
    const val MOST = 8

    /** How many trains ahead of the one being followed are offered before it. */
    private const val AHEAD = 3

    /**
     * A train due at the stop this long ago and still listing it is a record the feed
     * forgot to clear. Not sooner than that: on the numbered lines a train's times stand
     * still while it is held, so one a few minutes past its time is a train stuck short of
     * the stop, and may be the very one the rider is sitting on.
     */
    private const val STALE = 20 * 60L

    /** A train standing at the rider's stop this long past its time there is one that got in, not one on its way. */
    private const val ARRIVED = 120L

    /**
     * What the list has to show: [trains] once a feed is in, as the MTA built it at [asOf],
     * and until then whether it is on its way or could not be had. [awaited] is set while
     * an answer the screen is watching for is on its way.
     */
    data class Choice(val trains: List<Train>? = null, val asOf: Long = 0, val failed: Boolean = false, val awaited: Boolean = false) {
        sealed interface View {
            data object Loading : View

            data object Unavailable : View

            /** The trains to choose from, which can be none. With [live] false they are old enough to say so. */
            data class Trains(val trains: List<Train>, val live: Boolean) : View
        }

        /**
         * What to show at [now]. A train is told by its next stop, and a list too old to
         * count from has the wrong ones, so it is not shown. While an answer is on its way
         * that is "loading" and not "unavailable": nothing has failed yet.
         */
        fun view(now: Long): View {
            val current = trains?.takeIf { now - asOf <= Train.RECKON_FOR }
            return when {
                current != null -> View.Trains(current, live = now - asOf <= Train.LIVE_FOR)
                awaited || (trains == null && !failed) -> View.Loading
                else -> View.Unavailable
            }
        }
    }

    /**
     * The trains in [feed] on their way to the rider's stop, nearest to it first. A rider
     * on the wrong train is nearly always on the one just ahead of the train they tapped
     * or the one just behind, so where there are too many to list, the list is the few
     * ahead of the followed train, that train, and the ones behind it. The followed train
     * is in it for as long as the feed lists it for the stop, whatever its times.
     *
     * [known] are the trains as the list last had them. A train is read afresh from every
     * download, and they are how it is known to have been at one stop for a while.
     */
    fun of(ride: Ride, feed: GtfsRealtime.Feed, catalog: Catalog, now: Long, known: List<Train> = emptyList()): List<Train> {
        val stop = ride.train.stop(ride.to)?.id ?: return emptyList()
        val before = known.associateBy { it.id }
        val trains = Train.callingAt(ride.train.feed, feed, stop, catalog)
            .filter { train ->
                val time = train.stops.first { it.id == stop }.time
                val arrived = train.standing && train.stops.first().id == stop
                train.runsAs(ride.train.id) || time == 0L || time > now - if (arrived) ARRIVED else STALE
            }
            .map { train -> train.since(before[train.id]).let { if (it.runsAs(ride.train.id)) it.since(ride.train) else it } }
        val followed = trains.indexOfFirst { it.runsAs(ride.train.id) }
        val first = (followed - AHEAD).coerceIn(0, maxOf(0, trains.size - MOST))
        return trains.drop(first).take(MOST)
    }
}

/**
 * "I'm on a different train." The trains heading for the rider's stop, each told by its
 * next stop, which is the one thing a rider can check from inside a train: it is what the
 * announcement says and what the next platform's signs will say.
 */
class OtherTrainScreen(
    sealedActivity: SealedLightActivity,
    private val ride: Ride,
) : LightScreen<Train, OtherTrainViewModel>(sealedActivity) {

    override val viewModelClass: Class<OtherTrainViewModel> get() = OtherTrainViewModel::class.java

    override fun createViewModel(): OtherTrainViewModel {
        val readAsset = lightContext::readAsset
        return OtherTrainViewModel({ Subway.catalog(readAsset) }, ride)
    }

    private val wheel = WheelBus()
    private val page = Page()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val choice by viewModel.live.choice.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val now by viewModel.live.now.collectAsState()
        val catalog by viewModel.names.collectAsState()
        val names = catalog
        val view = choice.view(now)
        val trains = (view as? OtherTrains.Choice.View.Trains)?.trains
        val to = names?.stopName(ride.to).orEmpty()

        SubwayScreen(
            title = "Which train?",
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = listOf(null, null, refreshButton { viewModel.live.refreshNow() }),
            message = if (names == null) "" else OtherTrainWords.instead(view, to),
        ) {
            if (names != null && view is OtherTrains.Choice.View.Trains && trains != null) {
                FadeIn {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Note(text = OtherTrainWords.pick(to), modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()))
                        if (!view.live) Note(notLive(choice.asOf, "Trains"), Modifier.padding(bottom = 0.4f.gridUnitsAsDp()))
                        Column(modifier = Modifier.dimWhileRefreshing()) {
                            trains.forEach { train ->
                                key(train.id) {
                                    OtherTrainRow(
                                        train = train,
                                        asOf = choice.asOf,
                                        names = names,
                                        following = train.runsAs(ride.train.id),
                                        onClick = { goBack(train) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The sentences on the list of other trains. */
internal object OtherTrainWords {
    /** What stands in place of the list, or null when there are trains to list. [to] is the rider's stop. */
    fun instead(view: OtherTrains.Choice.View, to: String): String? = when (view) {
        OtherTrains.Choice.View.Loading -> Board.View.Loading.text
        OtherTrains.Choice.View.Unavailable -> Board.View.Unavailable.text
        is OtherTrains.Choice.View.Trains -> if (view.trains.isEmpty()) "No trains on their way to $to right now." else null
    }

    fun pick(to: String): String = "Trains on their way to $to. Pick the one whose next stop matches what you hear or see."

    const val FOLLOWED = "the one you are following"
}

@Composable
private fun OtherTrainRow(train: Train, asOf: Long, names: Catalog, following: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = 0.75f.gridUnitsAsDp()),
    ) {
        RouteBadge(Routes.display(train.route), Bullet.Title)
        Column(modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp())) {
            LightText(text = RideWords.whereabouts(train, asOf, names), variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Note(RideWords.toward(train, names) + if (following) "  ·  ${OtherTrainWords.FOLLOWED}" else "")
        }
    }
}
