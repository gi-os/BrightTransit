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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Arrival
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.StopRow
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.clockLabel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One train on a station page's row, as much as is needed to find it again: the feed it
 * rides in, its trip there, and the platform it was listed at. [name] is what the row
 * called it, for the title until the train itself is in hand.
 */
class TrainRef(val feed: String, val trip: String, val platform: String, val name: String) {
    /** The ride a tap on [stop] starts: on [train] from this row's platform to that stop. */
    fun rideTo(train: Train, stop: Train.Stop): Ride = Ride(train, from = platform, to = stop.platform)

    companion object {
        /** The trains of one row that can be followed, soonest first. A feed that gives its trips no ids has none. */
        fun of(trains: List<Arrival>): List<TrainRef> = trains.filter { it.trip.isNotEmpty() && it.feed.isNotEmpty() }.map {
            val line = if (Routes.isExpress(it.route)) "${Routes.glyph(it.route)} express" else it.route
            TrainRef(it.feed, it.trip, it.stop, "$line to ${it.destination}")
        }
    }
}

class TrainViewModel(
    private val saved: SavedStore,
    private val catalog: suspend () -> Catalog,
    private val ref: TrainRef,
) : LightViewModel<Unit>() {

    val live = LiveTrain(viewModelScope, catalog)

    /** The stations, once parsed. They are what turns a stop's id into its name. */
    private val _names = MutableStateFlow<Catalog?>(null)
    val names: StateFlow<Catalog?> = _names

    init {
        // As on a station page: started on the main thread and, with the feed already in
        // memory, finished there before the first frame, so the stops are up at once.
        viewModelScope.launch {
            _names.value = catalog()
            live.follow(ref.feed, ref.trip)
        }
        viewModelScope.launch(Dispatchers.Default) { runCatching { saved.noteTrainOpened() } }
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

/** The sentences on a train's page that are not about where the train is. */
internal object TrainPageWords {
    const val TAP = "Tap your stop to count down to it."

    /**
     * What the page says in place of the train's stops, or null when it has stops to
     * show. [sighting] is how the train in hand stands, when there is one.
     *
     * A train not there to be found when its page first asks has most likely finished:
     * that is how nearly every train leaves. And a train in hand that is too old to say
     * anything of is "loading" while it is being asked about. The connection is only
     * blamed once it has been tried.
     */
    fun instead(following: LiveTrain.Following, sighting: Sighting?): String? = when {
        following.train == null && following.loading -> Board.View.Loading.text
        following.train == null && following.failed -> Board.View.Unavailable.text
        following.train == null -> RideWords.FINISHED_UNSEEN
        sighting is Sighting.Unknown && following.loading -> Board.View.Loading.text
        else -> sighting?.let(RideWords::instead)
    }

    /**
     * The line under the top two that dates a change to the train's stops, or null. A
     * train that no longer stops here has said so already: "Its stops have changed."
     */
    fun changed(train: Train, sighting: Sighting): String? =
        if (train.changed > 0 && sighting !is Sighting.Skipping) RideWords.changed(train.changed) else null
}

/**
 * Where one train goes from the platform it was tapped at, and when it gets to each stop.
 * Tapping a stop starts a ride to it. [trains] are the trains of the row that was tapped
 * and [index] the one shown; the button in the middle moves on to the one after it, and
 * back returns to the one before.
 */
class TrainScreen(
    sealedActivity: SealedLightActivity,
    private val trains: List<TrainRef>,
    private val index: Int = 0,
) : LightScreen<Unit, TrainViewModel>(sealedActivity) {

    private val ref = trains[index]

    override val viewModelClass: Class<TrainViewModel> get() = TrainViewModel::class.java

    override fun createViewModel(): TrainViewModel {
        val readAsset = lightContext::readAsset
        return TrainViewModel(Subway.saved(lightContext.dataStore), { Subway.catalog(readAsset) }, ref)
    }

    private val wheel = WheelBus()
    private val page = Page()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val following by viewModel.live.following.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val now by viewModel.live.now.collectAsState()
        val catalog by viewModel.names.collectAsState()
        val train = following.train
        val names = catalog
        val here = names?.stopName(ref.platform).orEmpty()
        val sighting = train?.seenFrom(ref.platform, now)

        SubwayScreen(
            title = if (train != null && names != null) RideWords.name(train, names) else ref.name,
            subtitle = "from $here",
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = listOf(
                null,
                if (index < trains.lastIndex) {
                    LightBarButton.Text(text = "LATER TRAIN", onClick = { navigateTo({ TrainScreen(it, trains, index + 1) }) })
                } else {
                    null
                },
                refreshButton { viewModel.live.refreshNow() },
            ),
            message = if (names == null) "" else TrainPageWords.instead(following, sighting),
        ) {
            if (train != null && names != null && sighting != null) {
                TrainPage(
                    train = train,
                    sighting = sighting,
                    here = here,
                    now = now,
                    names = names,
                    onStop = { stop -> navigateTo({ RideScreen(it, ref.rideTo(train, stop)) }) },
                )
            }
        }
    }

    @Composable
    private fun TrainPage(train: Train, sighting: Sighting, here: String, now: Long, names: Catalog, onStop: (Train.Stop) -> Unit) {
        val top = RideWords.top(sighting, here, now, names)
        val live = train.grade(now) == Train.Grade.LIVE
        val stops = train.stopsAfter(ref.platform)
        FadeIn {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!live) Note(notLive(train.asOf), Modifier.padding(bottom = 0.4f.gridUnitsAsDp()))
                Column(modifier = Modifier.dimWhileRefreshing()) {
                    LightText(text = top.head, variant = LightTextVariant.Heading, lighten = !top.sure)
                    if (top.detail.isNotEmpty()) Note(top.detail)
                    TrainPageWords.changed(train, sighting)?.let { Note(it) }
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                    if (stops.isNotEmpty()) Note(TrainPageWords.TAP, Modifier.padding(bottom = 0.25f.gridUnitsAsDp()))
                    stops.forEach { stop ->
                        key(stop.id) {
                            StopRow(
                                name = names.stopName(stop.platform),
                                time = if (stop.time > 0) clockLabel(stop.time) else null,
                                lighten = !live || !train.assigned,
                                onClick = { onStop(stop) },
                            )
                        }
                    }
                }
            }
        }
    }
}
