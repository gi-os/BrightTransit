package com.thelightphone.subway

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.thelightphone.subway.data.AlertsRepository
import com.thelightphone.subway.data.ArrivalsRepository
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Station
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

/**
 * The tool's moving parts with the MTA and time taken out: the two real repositories over
 * downloads the test answers, one clock the test moves, and waits that end when that clock
 * says so. Everything runs on the test's own thread, so each call here is finished, as far
 * as it can get without a download, by the time it returns.
 *
 * The clock starts five seconds after the captured L feed was built, unless a test [start]s
 * it at another time to replay feeds recorded then.
 */
class TestPhone(start: Long = Fixtures.lFeed.timestamp + 5) {
    private var ms = start * 1000

    val now: Long get() = ms / 1000

    var trainDownloads = 0
    var alertDownloads = 0

    /** The L running as it was captured, in a feed the MTA built five seconds ago. */
    fun feedNow(): ByteArray = TrainFeeds.lFeedLater(now - 5 - Fixtures.lFeed.timestamp)

    /** What the MTA sends back for a train feed, given its address. */
    var trains: suspend (String) -> ByteArray = { feedNow() }

    var alertsAnswer: suspend () -> ByteArray = { Fixtures.alertBytes }

    private val waits = Waits()

    val arrivals = ArrivalsRepository(
        download = {
            trainDownloads++
            trains(it)
        },
        clock = { now },
        dispatcher = Dispatchers.Unconfined,
    )

    val alerts = AlertsRepository(
        download = {
            alertDownloads++
            alertsAnswer()
        },
        clock = { now },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** A screen's worth of live boards, as a view model would hold them. */
    fun screen(vararg following: Station): LiveBoards {
        val live = LiveBoards(
            scope = CoroutineScope(Dispatchers.Unconfined),
            catalog = { Fixtures.catalog },
            arrivals = arrivals,
            alertsRepository = alerts,
            clock = { now },
            wait = { waits.park(ms + it) },
        )
        if (following.isNotEmpty()) live.following(*following)
        return live
    }

    /** One train followed, as its page or a ride would hold it. */
    fun following(): LiveTrain = LiveTrain(
        scope = CoroutineScope(Dispatchers.Unconfined),
        catalog = { Fixtures.catalog },
        arrivals = arrivals,
        clock = { now },
        wait = { waits.park(ms + it) },
    )

    /** A ride on a train, as its screen would hold it, written down in [store]. */
    fun ride(start: Ride, store: SavedStore, resumed: Boolean = false): LiveRide = LiveRide(
        scope = CoroutineScope(Dispatchers.Unconfined),
        catalog = { Fixtures.catalog },
        saved = store,
        start = start,
        resumed = resumed,
        arrivals = arrivals,
        clock = { now },
        wait = { waits.park(ms + it) },
    )

    /** The list of other trains a rider on [ride] could be on, as its screen would hold it. */
    fun otherTrains(ride: Ride): LiveOtherTrains = LiveOtherTrains(
        scope = CoroutineScope(Dispatchers.Unconfined),
        catalog = { Fixtures.catalog },
        ride = ride,
        arrivals = arrivals,
        clock = { now },
        wait = { waits.park(ms + it) },
    )

    /** The way from one station to another, as the page of the second or the list of trains would hold it. */
    fun way(): LivePlan = LivePlan(
        scope = CoroutineScope(Dispatchers.Unconfined),
        catalog = { Fixtures.catalog },
        arrivals = arrivals,
        clock = { now },
        wait = { waits.park(ms + it) },
    )

    fun offline() {
        trains = { throw IOException("no signal") }
    }

    fun online() {
        trains = { feedNow() }
    }

    /** [seconds] go by with the phone awake: every wait that comes due on the way ends, in order. */
    fun elapse(seconds: Long) {
        val end = ms + seconds * 1000
        while (true) {
            val due = waits.nextDue()?.takeIf { it <= end } ?: break
            ms = maxOf(ms, due)
            waits.endNext()
        }
        ms = end
    }

    /**
     * [seconds] go by with the phone asleep. The clock moves; the waits do not, because a
     * wait counts only the time the phone is awake.
     */
    fun sleep(seconds: Long) {
        ms += seconds * 1000
        waits.postpone(seconds * 1000)
    }

    /** Someone corrects the phone's clock backwards. Time itself does not move, so no wait ends sooner or later. */
    fun setClockBack(seconds: Long) {
        ms -= seconds * 1000
        waits.postpone(-seconds * 1000)
    }

    /** How many waits are parked. A screen that is showing has two, its tick and its next pass; a hidden one has none. */
    val waiting: Int get() = waits.count

    /** Stands in for delay(): a wait parks until the phone's clock reaches the time it is due. */
    private class Waits {
        private class Parked(var due: Long, val gate: CompletableDeferred<Unit> = CompletableDeferred())

        private val parked = ArrayList<Parked>()

        val count: Int get() = parked.size

        suspend fun park(due: Long) {
            val mine = Parked(due)
            parked += mine
            try {
                mine.gate.await()
            } finally {
                parked -= mine
            }
        }

        fun nextDue(): Long? = parked.minOfOrNull { it.due }

        fun endNext() {
            val next = parked.minByOrNull { it.due } ?: return
            parked -= next
            next.gate.complete(Unit)
        }

        fun postpone(by: Long) {
            parked.forEach { it.due += by }
        }
    }
}

/** The phone's store, in memory. A write can be held up by a test, as a slow disk would. */
class MemoryStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    var hold: CompletableDeferred<Unit>? = null
    var writes = 0

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        hold?.await()
        writes++
        return transform(state.value).also { state.value = it }
    }
}

fun LiveBoards.following(vararg stations: Station) = runBlocking { follow(stations.toList()) }

/** What the one board on a screen says right now. */
fun LiveBoards.says(): Board.View = boards.value.single().view(now.value)

/** The minutes shown under each heading, soonest first. */
fun Board.View.minutes(now: Long): Map<String, List<Int>> =
    (this as Board.View.Trains).groups.associate { group -> group.heading to group.arrivals.map { it.minutesFrom(now) } }

/** Train feeds the MTA has not sent, made from the one it did. */
object TrainFeeds {
    /** The captured L feed as it would be [seconds] later: built that much later, every train that much later. */
    fun lFeedLater(seconds: Long): ByteArray = bytes(later(Fixtures.lFeed, seconds))

    /** [feed] as it would be [seconds] later with nothing else changed. */
    fun later(feed: GtfsRealtime.Feed, seconds: Long): GtfsRealtime.Feed = feed.copy(
        timestamp = feed.timestamp + seconds,
        trips = feed.trips.map { trip -> trip.copy(stops = trip.stops.map { it.copy(time = it.time + seconds) }) },
        positions = feed.positions.mapValues { it.value.copy(timestamp = it.value.timestamp + seconds) },
    )

    /**
     * [feed] written out the way the MTA would send it, with only the fields the tool reads.
     * A train that has its trip is written as plain GTFS-realtime, which has no way to say
     * so; one that does not is written the way the L's feed does it, with a zero. A
     * position is an entity of its own, as in the real feeds, and one with no status is
     * written with none.
     *
     * With [mixed], the fields of every message come in an order drawn from it, among
     * fields the tool does not read, one of each kind protobuf has. Protobuf promises a
     * reader no order, so the feed read back must be the same.
     */
    fun bytes(feed: GtfsRealtime.Feed, mixed: Random? = null): ByteArray =
        Proto(mixed).apply {
            message(1) { number(3, feed.timestamp) }
            feed.trips.forEach { trip ->
                message(2) {
                    message(3) {
                        message(1) {
                            if (trip.id.isNotEmpty()) text(1, trip.id)
                            text(5, trip.route)
                            if (!trip.assigned) message(1001) { number(2, 0) }
                        }
                        trip.stops.forEach { stop ->
                            message(2) {
                                // A stop with a time to leave and none to arrive is written as one.
                                message(if (stop.departsOnly) 3 else 2) { number(2, stop.time) }
                                text(4, stop.stopId)
                            }
                        }
                    }
                }
            }
            feed.positions.forEach { (trip, position) ->
                message(2) {
                    message(4) {
                        message(1) { text(1, trip) }
                        position.status?.let { number(4, it.ordinal.toLong()) }
                        number(5, position.timestamp)
                        text(7, position.stopId)
                    }
                }
            }
        }.bytes()

    /** Just enough of a protobuf writer for a feed of trains. */
    private class Proto(private val mixed: Random?) {
        private class Field(val number: Int, val bytes: ByteArray)

        private val fields = ArrayList<Field>()

        fun number(field: Int, value: Long) = add(field, 0) { varint(value) }

        fun text(field: Int, value: String) = raw(field, value.toByteArray())

        fun message(field: Int, build: Proto.() -> Unit) = raw(field, Proto(mixed).apply(build).bytes())

        fun bytes(): ByteArray {
            if (mixed != null) mix(mixed)
            return ByteArrayOutputStream().also { out -> fields.forEach { out.write(it.bytes) } }.toByteArray()
        }

        /**
         * Adds a field of each kind under numbers the tool does not read, then shuffles the
         * lot. A repeated field keeps its own order, since that is the order the train runs in.
         */
        private fun mix(random: Random) {
            add(UNREAD, 0) { varint(random.nextLong() ushr 1) }
            add(UNREAD + 1, 1) { write(ByteArray(8).also(random::nextBytes)) }
            add(UNREAD + 2, 5) { write(ByteArray(4).also(random::nextBytes)) }
            raw(UNREAD + 3, ByteArray(random.nextInt(40)).also(random::nextBytes))
            val waiting = fields.groupBy { it.number }.mapValues { ArrayDeque(it.value) }
            val shuffled = fields.map { it.number }.shuffled(random).map { waiting.getValue(it).removeFirst() }
            fields.clear()
            fields += shuffled
        }

        private fun raw(field: Int, value: ByteArray) = add(field, 2) {
            varint(value.size.toLong())
            write(value)
        }

        private fun add(field: Int, wire: Int, value: ByteArrayOutputStream.() -> Unit) = apply {
            val out = ByteArrayOutputStream()
            out.varint((field shl 3 or wire).toLong())
            out.value()
            fields += Field(field, out.toByteArray())
        }

        private fun ByteArrayOutputStream.varint(value: Long) {
            var rest = value
            while (rest >= 0x80) {
                write((rest and 0x7F or 0x80).toInt())
                rest = rest ushr 7
            }
            write(rest.toInt())
        }

        private companion object {
            /** The first of four field numbers that mean nothing to the tool in any message. */
            const val UNREAD = 2000
        }
    }
}
