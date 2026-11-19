package com.thelightphone.subway

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.thelightphone.subway.data.Alerts
import com.thelightphone.subway.data.AlertsRepository
import com.thelightphone.subway.data.ArrivalsRepository
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.data.Train
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The parts every screen shares: the station catalog, the feed cache, the service alerts
 * and what is saved. There is one of each, so two screens that want the same feed share
 * one download and one copy.
 */
object Subway {
    val arrivals = ArrivalsRepository()

    val alerts = AlertsRepository()

    @Volatile
    private var catalog: Catalog? = null

    @Volatile
    private var saved: SavedStore? = null

    /** Parses the 445 stations once, off the main thread, and keeps them. */
    suspend fun catalog(readAsset: (String) -> ByteArray): Catalog =
        catalog ?: withContext(Dispatchers.Default) {
            synchronized(this@Subway) {
                catalog ?: Catalog.parse(String(readAsset("stations.json"), Charsets.UTF_8)).also { catalog = it }
            }
        }

    fun saved(dataStore: DataStore<Preferences>): SavedStore =
        saved ?: synchronized(this) { saved ?: SavedStore(dataStore).also { saved = it } }
}

/**
 * The rhythm of a screen that shows live times: a pass at once when it appears, another
 * every half minute so nobody has to sit pressing refresh, and one clock that every
 * countdown on the screen is measured from. It stops as soon as the screen is covered or
 * the phone sleeps.
 *
 * Everything kept here, and everything kept by whoever owns this, is touched on one thread
 * only, the one [scope] runs on: for a view model's scope that is the main thread. The
 * calls below can be made from any thread; each hands its work to that one, and runs at
 * once when it is already there. The [clock] and the [wait] between passes are passed in so
 * tests can stand in for time.
 *
 * [load] is one pass, forced or not. [afterLoad] runs once it is back and is not waited
 * for. [expectAnswer] is called just before a pass the screen will be watching for.
 * [nextIn] says how long to wait after a pass for the next, for a screen whose pass can be
 * answered from a copy another screen fetched some seconds before.
 */
class LiveLoop(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wait: suspend (Long) -> Unit,
    private val expectAnswer: () -> Unit,
    private val load: suspend (force: Boolean) -> Unit,
    private val afterLoad: (forced: Boolean) -> Unit = {},
    private val nextIn: () -> Long = { EVERY_MS },
) {
    /** True while a refresh the person asked for (the button) is in flight. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _now = MutableStateFlow(clock())

    /**
     * The time in seconds, moving forward on its own while the screen shows, so "3 min"
     * becomes "2 min" without a download. Ten seconds is fine-grained enough for a number
     * shown in whole minutes.
     */
    val now: StateFlow<Long> = _now

    private var visible = false
    private var loop: Job? = null

    /** Runs [block] on the one thread this lives on: at once when called from it, in turn otherwise. */
    fun confined(block: () -> Unit): Job = scope.launch { block() }

    /** Reads the clock again. New times are never drawn against a clock older than they are. */
    fun readClock(): Long = clock().also { _now.value = it }

    fun resume() {
        confined {
            visible = true
            // Read here, before anything is drawn. A wait does not count the time the phone
            // spent asleep, so the tick alone would leave every countdown where it was.
            _now.value = clock()
            expectAnswer()
            restart(force = false)
        }
    }

    fun pause() {
        confined {
            visible = false
            loop?.cancel()
            loop = null
            _refreshing.value = false
        }
    }

    /** The refresh button: fetch now, even if the copy in hand is seconds old. */
    fun refreshNow() {
        confined {
            if (visible) {
                expectAnswer()
                restart(force = true)
            }
        }
    }

    /**
     * Starts over because what is being followed has changed. A refresh the person asked
     * for is carried over, not dropped, when that happens in the middle of it. Call on the
     * confined thread.
     */
    fun startOver() {
        if (visible) restart(force = _refreshing.value)
    }

    private fun restart(force: Boolean) {
        loop?.cancel()
        _refreshing.value = force
        loop = scope.launch {
            launch {
                while (isActive) {
                    wait(TICK_MS)
                    _now.value = clock()
                }
            }
            var forced = force
            while (isActive) {
                if (forced) {
                    coroutineScope {
                        // Long enough to be seen even when the MTA answers at once.
                        launch { wait(MIN_VISIBLE_MS) }
                        load(true)
                    }
                    _refreshing.value = false
                } else {
                    load(false)
                }
                afterLoad(forced)
                forced = false
                wait(nextIn())
            }
        }
    }

    companion object {
        /** How long after one pass the next begins. */
        const val EVERY_MS = 30_000L

        /** How often the clock moves while the screen shows. */
        const val TICK_MS = 10_000L

        /** The least time the refresh button's dimming stays up. */
        const val MIN_VISIBLE_MS = 400L

        /**
         * How long to wait for the next pass on one feed, given the [age] in seconds of
         * the copy of it in hand. A pass answered from a copy another screen fetched a few
         * seconds ago is followed when that copy is half a minute old. Waiting the whole
         * half minute from then would leave a train's stops over a minute old, and marked
         * as not live, with nothing having failed.
         */
        fun untilStale(age: Long?): Long =
            if (age != null && age in 0 until ArrivalsRepository.FRESH_FOR) EVERY_MS - age * 1000 else EVERY_MS
    }
}

/**
 * Keeps the boards for a set of stations current while their screen is showing. A board
 * that cannot be refreshed keeps the trains it had, marked stale, until they are too old
 * to show.
 *
 * When it fetches and on which thread is [LiveLoop]'s doing. The two repositories, the
 * [clock] and the [wait] between passes are passed in so tests can stand in for the MTA
 * and for time.
 */
class LiveBoards(
    scope: CoroutineScope,
    private val catalog: suspend () -> Catalog,
    private val arrivals: ArrivalsRepository = Subway.arrivals,
    private val alertsRepository: AlertsRepository = Subway.alerts,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val _boards = MutableStateFlow<List<Board>>(emptyList())
    val boards: StateFlow<List<Board>> = _boards

    private var stations: List<Station> = emptyList()

    private val loop = LiveLoop(
        scope = scope,
        clock = clock,
        wait = wait,
        // A board with nothing it can show says "Loading" from here until its answer is in.
        expectAnswer = { _boards.value = _boards.value.map { it.copy(loading = true) } },
        load = ::load,
        // Alerts are asked for once the trains are back, never beside them, and are not
        // waited for. Most times this does nothing: the copy in hand is new enough.
        afterLoad = { forced -> if (stations.isNotEmpty()) alertsRepository.refresh(force = forced) },
    )

    val refreshing: StateFlow<Boolean> get() = loop.refreshing

    val now: StateFlow<Long> get() = loop.now

    /**
     * The service alerts in hand. They are the same for every screen and are not part of a
     * board: a screen picks out the ones for its stations as it draws.
     */
    val alerts: StateFlow<Alerts> get() = alertsRepository.current

    /**
     * Which stations to follow. Boards already loaded for the same stations are kept. A
     * station new to the list starts from whatever the feed cache holds for it, so a station
     * opened from Home has its times at once. Returns when the boards are in place.
     */
    suspend fun follow(stations: List<Station>) {
        // Worked out on the caller's thread, before the hand-over, so a caller on a
        // background thread keeps this work there.
        val known = _boards.value.mapTo(HashSet()) { it.station.id }
        val unknown = stations.filter { it.id !in known }
        val seeds = if (unknown.isEmpty()) {
            emptyMap()
        } else {
            val cat = catalog()
            unknown.associate { it.id to arrivals.peek(it, cat) }
        }
        loop.confined {
            this.stations = stations
            val old = _boards.value.associateBy { it.station.id }
            _boards.value = stations.map { old[it.id] ?: (seeds[it.id] ?: Board(it)).copy(loading = true) }
            // Nearby looks the location up again on the same press of refresh.
            loop.startOver()
        }.join()
    }

    fun resume() = loop.resume()

    fun pause() = loop.pause()

    /** The refresh button: fetch now, even if the copy in hand is seconds old. */
    fun refreshNow() = loop.refreshNow()

    private suspend fun load(force: Boolean) {
        val following = stations
        if (following.isEmpty()) return
        val cat = catalog()
        coroutineScope {
            following.forEach { station ->
                launch { merge(arrivals.board(station, cat, force)) }
            }
        }
    }

    private fun merge(fresh: Board) {
        val now = loop.readClock()
        _boards.value = _boards.value.map { old ->
            if (old.station.id == fresh.station.id) old.replacedBy(fresh, now) else old
        }
    }
}

/**
 * Keeps one train current while its screen is showing: its page, or the ride of someone
 * on it. One feed is fetched, on [LiveLoop]'s rhythm, through the same repository the
 * boards use, so while a board on the same line is up this costs no download of its own.
 *
 * Each feed that comes back is handed to [Train.after], which is where a missed download,
 * a changed list of stops and a train gone from the feed are told apart. A download that
 * fails changes nothing: the train keeps what it had, and how old that is shows.
 *
 * [afterPass] runs each time a pass is back, whether or not it brought anything new.
 */
class LiveTrain(
    scope: CoroutineScope,
    private val catalog: suspend () -> Catalog,
    private val arrivals: ArrivalsRepository = Subway.arrivals,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
    wait: suspend (Long) -> Unit = { delay(it) },
    private val afterPass: () -> Unit = {},
) {
    /**
     * What is known of the train. With no [train] there are three things to tell apart:
     * [loading] while the first answer is on its way, [failed] when the feed could not be
     * had, and neither when the feed came and the train was not in it.
     *
     * With a [train], [loading] is true while an answer is on its way and what is in hand
     * is too old to say anything: it has not failed to be heard from, it has not been
     * asked about yet.
     */
    data class Following(val train: Train? = null, val loading: Boolean = false, val failed: Boolean = false)

    private class Target(val slug: String, val id: String)

    private val _following = MutableStateFlow(Following(loading = true))
    val following: StateFlow<Following> = _following

    private var target: Target? = null

    private val loop: LiveLoop = LiveLoop(
        scope = scope,
        clock = clock,
        wait = wait,
        // The clock has just been read, so how old the train in hand is can be judged here.
        expectAnswer = {
            _following.value = _following.value.let { it.copy(loading = it.train == null || it.train.grade(loop.now.value) == Train.Grade.UNKNOWN) }
        },
        load = ::load,
        nextIn = { LiveLoop.untilStale(target?.let { arrivals.age(it.slug) }) },
    )

    val refreshing: StateFlow<Boolean> get() = loop.refreshing

    val now: StateFlow<Long> get() = loop.now

    /**
     * Which train to follow: the trip [id] in the feed fetched as [slug]. It starts from
     * the copy of that feed in hand, so a train opened from a station page has its stops
     * at once. Returns when that is in place.
     */
    suspend fun follow(slug: String, id: String) {
        val seed = arrivals.peekFeed(slug)?.let { Train.find(slug, it, id, catalog()) }
        start(Target(slug, id), seed)
    }

    /** Follows [train] from what is already known of it: a ride picked up again, or another train the rider says they are on. */
    suspend fun follow(train: Train) = start(Target(train.feed, train.id), train)

    private suspend fun start(next: Target, seed: Train?) {
        loop.confined {
            target = next
            _following.value = Following(seed, loading = seed == null)
            loop.startOver()
        }.join()
    }

    fun resume() = loop.resume()

    fun pause() = loop.pause()

    /** Runs [block] on the one thread this lives on: see [LiveLoop.confined]. */
    fun confined(block: () -> Unit): Job = loop.confined(block)

    /** The refresh button: fetch now, even if the copy in hand is seconds old. */
    fun refreshNow() = loop.refreshNow()

    private suspend fun load(force: Boolean) {
        val following = target ?: return
        val cat = catalog()
        // A pass still waiting here when the rider chooses another train is cancelled with
        // the loop it belongs to, so what comes back is always about the train now followed.
        val feed = arrivals.feed(following.slug, force)
        loop.readClock()
        val known = _following.value.train
        _following.value = when {
            known != null -> Following(if (feed == null) known else known.after(feed, cat))
            feed == null -> Following(failed = true)
            else -> Following(Train.find(following.slug, feed, following.id, cat))
        }
        afterPass()
    }
}

/**
 * A ride in progress: one train followed to one stop, written down as it goes (see
 * [SavedStore.ride]) so it is there to come back to if LightOS closes the tool, and
 * forgotten when it is ended.
 *
 * It also settles whether a ride come back to is still worth showing. A ride is over ten
 * minutes after its train was due at the rider's stop, but what is in hand when the screen
 * comes back is from before the phone slept: the train may be late, or held one stop short.
 * So nothing is ended on old word. The train is asked about first, and the ride ends only
 * if the answer still has it over, or if no answer can be had and the last word of it is
 * past keeping. A ride is only ever judged when the tool itself was away, or was closed.
 * A rider who has just been choosing another train is there to read what it says.
 *
 * This is everything a ride's screen decides, kept out of the screen so it can be run
 * against a clock and a store that tests hold.
 */
class LiveRide(
    scope: CoroutineScope,
    catalog: suspend () -> Catalog,
    private val saved: SavedStore,
    start: Ride,
    resumed: Boolean = false,
    arrivals: ArrivalsRepository = Subway.arrivals,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    /** What the screen is to do with the ride. */
    enum class Showing {
        /** Say how it stands. */
        RIDE,

        /** It looks over, and its train is being asked about before that is believed. */
        ASKING,

        /** It is over, and nobody is left to read it. */
        OVER,
    }

    val live = LiveTrain(scope, catalog, arrivals, clock, wait, afterPass = ::passed)

    private val _ride = MutableStateFlow(start)
    val ride: StateFlow<Ride> = _ride

    private val _showing = MutableStateFlow(Showing.RIDE)
    val showing: StateFlow<Showing> = _showing

    /** True from when the tool goes away until the ride is next shown. A ride opened by the tool starting up has been away too. */
    private var away = resumed

    /** True while an answer is awaited that will say whether the ride is over. */
    private var owed = false

    @Volatile
    private var ended = false

    init {
        saved.setRide(start)
        scope.launch { live.follow(start.train) }
    }

    /** The screen is showing, for the first time or again. */
    fun shown() {
        live.confined {
            if (away) {
                away = false
                owed = true
                if (_ride.value.over(clock())) _showing.value = Showing.ASKING
            }
        }
        live.resume()
    }

    /** Another screen is on top. */
    fun hidden() = live.pause()

    /** The tool is no longer in front: the phone slept, or the rider went elsewhere. */
    fun paused() {
        live.confined { away = true }
        live.pause()
    }

    /** The rider says they are on [train]. Whatever became of the old one, this one they have just named. */
    suspend fun switchTo(train: Train) {
        live.confined {
            keep(_ride.value.on(train))
            owed = false
            _showing.value = Showing.RIDE
        }.join()
        live.follow(train)
    }

    /**
     * The ride is finished with. Nothing that comes back after this is written down. This
     * is the last thing a ride's screen does, so it is done here and now, on the caller's
     * thread, and not handed to a scope that is about to be closed.
     */
    fun end() {
        ended = true
        saved.setRide(null)
    }

    private fun keep(ride: Ride) {
        if (ended || ride == _ride.value) return
        _ride.value = ride
        saved.setRide(ride)
    }

    private fun passed() {
        live.following.value.train?.let { keep(_ride.value.on(it)) }
        if (!owed) return
        owed = false
        val now = clock()
        val ride = _ride.value
        // A feed that came says so by its date, whether or not the train was in it.
        val heard = now - ride.train.checked <= Train.LIVE_FOR
        _showing.value = if (ride.over(now) && (heard || ride.forgotten(now))) Showing.OVER else Showing.RIDE
    }
}

/**
 * Keeps the list of other trains current while its screen is showing: the trains in the
 * followed train's feed that are on their way to the rider's stop, for a rider who is on a
 * different one. It is the feed the ride's own screen has just fetched, so opening the
 * list costs no download.
 *
 * This is everything that list's screen decides, kept out of the screen so it can be run
 * against a clock that tests hold.
 */
class LiveOtherTrains(
    scope: CoroutineScope,
    private val catalog: suspend () -> Catalog,
    private val ride: Ride,
    private val arrivals: ArrivalsRepository = Subway.arrivals,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val _choice = MutableStateFlow(OtherTrains.Choice())
    val choice: StateFlow<OtherTrains.Choice> = _choice

    private val loop: LiveLoop = LiveLoop(
        scope = scope,
        clock = clock,
        wait = wait,
        // A list too old to show says "Loading" from here until its answer is in.
        expectAnswer = { _choice.value = _choice.value.copy(awaited = true) },
        load = ::load,
        nextIn = { LiveLoop.untilStale(arrivals.age(ride.train.feed)) },
    )

    val refreshing: StateFlow<Boolean> get() = loop.refreshing

    val now: StateFlow<Long> get() = loop.now

    fun resume() = loop.resume()

    fun pause() = loop.pause()

    /** The refresh button: fetch now, even if the copy in hand is seconds old. */
    fun refreshNow() = loop.refreshNow()

    private suspend fun load(force: Boolean) {
        val cat = catalog()
        val feed = arrivals.feed(ride.train.feed, force)
        val now = loop.readClock()
        // A download that failed hands back the copy from before it. How old that is shows on the screen.
        _choice.value = if (feed == null) {
            OtherTrains.Choice(failed = true)
        } else {
            // The trains as the list last had them say how long each has been where it is.
            OtherTrains.Choice(OtherTrains.of(ride, feed, cat, now, known = _choice.value.trains.orEmpty()), feed.timestamp)
        }
    }
}

/**
 * Keeps the way from one station to another current while its screen is showing: the
 * block on the found station's page, and the list of trains behind it. The feeds of both
 * stations are fetched on [LiveLoop]'s rhythm, through the same repository the boards use.
 *
 * A plan is not kept past a failed download the way a board's times are. What comes back
 * replaces what was there, and says for itself which lines it could not check.
 */
class LivePlan(
    scope: CoroutineScope,
    private val catalog: suspend () -> Catalog,
    private val arrivals: ArrivalsRepository = Subway.arrivals,
    clock: () -> Long = { System.currentTimeMillis() / 1000 },
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * [plan] is null until there is one. [loading] is true from when an answer is asked
     * for until it is in: a plan too old to offer anything is then waiting to be replaced,
     * and has not failed.
     */
    data class Following(val plan: Plan? = null, val loading: Boolean = true)

    private val _following = MutableStateFlow(Following())
    val following: StateFlow<Following> = _following

    private var ends: Pair<Station, Station>? = null

    /** How many times [follow] has been asked. Only the latest asking is followed. */
    @Volatile
    private var asked = 0

    private val loop = LiveLoop(
        scope = scope,
        clock = clock,
        wait = wait,
        expectAnswer = { _following.value = _following.value.copy(loading = true) },
        load = ::load,
    )

    val refreshing: StateFlow<Boolean> get() = loop.refreshing

    val now: StateFlow<Long> get() = loop.now

    /**
     * Which way to follow, by station id. It starts from the feeds in hand when they are
     * enough to answer, so a station found from the page of another on its line has its
     * block at once. Returns when that is in place.
     */
    suspend fun follow(from: String, to: String) {
        val asking = ++asked
        val cat = catalog()
        val pair = (cat.station(from) ?: return) to (cat.station(to) ?: return)
        // A way with a change looks at every station on the way, which is more than the
        // main thread should do while a page opens.
        val seed = withContext(Dispatchers.Default) { arrivals.peekPlan(pair.first, pair.second, cat) }
        loop.confined {
            // A later asking that got here first is not undone by this one.
            if (asking != asked) return@confined
            ends = pair
            _following.value = Following(seed, loading = true)
            loop.startOver()
        }.join()
    }

    fun resume() = loop.resume()

    fun pause() = loop.pause()

    /** The refresh button: fetch now, even if the copies in hand are seconds old. */
    fun refreshNow() = loop.refreshNow()

    private suspend fun load(force: Boolean) {
        val (from, to) = ends ?: return
        val plan = arrivals.plan(from, to, catalog(), force)
        loop.readClock()
        _following.value = Following(plan, loading = false)
    }
}
