package com.thelightphone.subway.data

/**
 * One train that runs from one station to another with no change.
 *
 * [train] is the train as the feed has it. [from] is the platform it is boarded at and [to]
 * the platform it is left at, both as the station file names them ("L14", "L03"). [leaves]
 * is when the feed has it at [from] and [arrives] when it has it at [to], or 0 where the
 * feed gives no time that can be believed. [stops] is how many stops the ride is.
 *
 * [route], [heading] and [lastStop] are what the page of the station it leaves from calls
 * it: its bullet, the way it is going and where it ends.
 */
data class Direct(
    val train: Train,
    val from: String,
    val to: String,
    val leaves: Long,
    val arrives: Long,
    val stops: Int,
    val route: String,
    val heading: String,
    val lastStop: String,
) {
    /** False for a trip no train has yet. Its times are the timetable's, and it may never run. */
    val running: Boolean get() = train.assigned

    /** What tells this train from every other: the feed it rides in and its trip there. */
    val key: Pair<String, String> get() = train.feed to train.id

    /** Gone from the platform, by the rule a board goes by: more than half a minute past its time. */
    fun gone(now: Long): Boolean = leaves < now - 30

    /** Where the train is at [now], seen from the platform it is boarded at. */
    fun seen(now: Long): Sighting = train.seenFrom(from, now)

    /** True when the train has stood where it is for long enough that its times will slip. */
    fun held(now: Long): Boolean = (seen(now) as? Sighting.Coming)?.heldSince != null

    /** The ride this train is for someone going from the one station to the other: on at [from], off at [to]. */
    fun ride(): Ride = Ride(train, from = from, to = to)
}

/** Soonest there first. A train with no arrival time goes after the ones that have one, in the order they leave. */
private val SOONEST_THERE = compareBy<Direct>({ if (it.arrives > 0) it.arrives else Long.MAX_VALUE }, { it.leaves }, { it.train.id })

/**
 * The trains that run direct from one station to another, as the feeds had them when this
 * was made. [trains] is every one of them, soonest there first. [built] says when the MTA
 * built each feed they were looked for in; a feed that could not be had is not in it.
 *
 * Like a board, a plan can sit in memory through a sleep or a tunnel, so what it has to say
 * is asked for with the time: see [view].
 */
data class Plan(
    val from: Station,
    val to: Station,
    val trains: List<Direct>,
    val built: Map<String, Long>,
) {
    /**
     * Where a list of later trains starts, for a rider who will not make the first to go.
     * [time] is when that train was to leave, and [passed] are the trains that were to
     * leave by then, by [Direct.key]. A train is passed over by name and not only by its
     * time: the time moves with every download, and a train that slips half a minute is
     * still the one the rider gave up on.
     */
    data class After(val time: Long, val passed: Set<Pair<String, String>> = emptySet())

    sealed interface View {
        /**
         * Trains to offer, soonest there first: see [choose] for where a train with no
         * arrival time goes. [unchecked] are lines that run between the two stations and
         * could not be looked at, so a faster train may be missing.
         * [later] is what to pass to [view] for the trains after the first of these to go,
         * or null when there are none.
         */
        data class Trains(val choices: List<Direct>, val unchecked: List<String>, val later: After?) : View

        /**
         * Nothing to offer, and not every feed a train between the two could be in was
         * had. [lines] are the ones that run between the two stations and could not be
         * looked at, where the station file knows of any.
         */
        data class Unchecked(val lines: List<String>) : View

        /** No train runs direct right now: every feed one could be in was looked in. */
        data object None : View
    }

    /**
     * What this plan has to offer at [now]. With [after], it is what is left for someone
     * who will not make the trains passed over there, or any that leaves as soon.
     *
     * A way there is never worked out from old times. A feed built more than
     * [Planner.FRESH_FOR] ago is as good as not had: its trains are not offered and its
     * lines are named as unchecked, the same as if the download had failed.
     *
     * Of the trains left, the one that gets there first is always offered. Another is
     * offered with it only when it leaves sooner: a train that both leaves later and gets
     * there later is no choice at all. That is what settles the local now against the
     * express in four minutes, and it is rarely more than two trains. See [choose].
     */
    fun view(now: Long, after: After? = null): View {
        val live = live(now)
        val found = trains.filter { it.train.feed in live }
        val unchecked = unchecked(live, found)
        val current = found.filterNot { it.gone(now) }
        val left = if (after == null) current else current.filter { it.leaves > after.time && it.key !in after.passed }
        val choices = choose(left, now)
        // "No direct train" is only said from every feed either station is looked for in.
        // A train can run between two stations that share no line in the station file,
        // so a feed that was not had can always be the one it rides in.
        if (choices.isEmpty()) return if (Planner.slugs(from, to).all { it in live }) View.None else View.Unchecked(unchecked)
        val first = choices.minOf { it.leaves }
        val later = if (left.none { it.leaves > first }) {
            null
        } else {
            After(first, after?.passed.orEmpty() + current.filter { it.leaves <= first }.map { it.key })
        }
        return View.Trains(choices, unchecked, later)
    }

    /** The lines that run between the two stations and cannot be answered for at [now]. */
    fun unchecked(now: Long): List<String> = live(now).let { live -> unchecked(live, trains.filter { it.train.feed in live }) }

    private fun live(now: Long): Set<String> = built.filterValues { now - it <= Planner.FRESH_FOR }.keys

    /**
     * A shuttle is looked for in two feeds and rides in one, so it is only unchecked when
     * the feed in hand has no train of it either. See [Board.of].
     */
    private fun unchecked(live: Set<String>, found: List<Direct>): List<String> =
        Planner.shared(from, to)
            .filter { (line, feeds) -> feeds.any { it !in live } && (feeds.size == 1 || found.none { Routes.plain(it.route) == line }) }
            .map { it.first }

    /**
     * The trains worth offering out of [trains]. One is left out when another both leaves
     * sooner and gets there sooner. Where the feed gives a train no arrival time, leaving
     * later is taken to mean getting there later.
     *
     * A train that is only on the timetable, or is being held, never puts a moving one
     * out: its times are the ones that slip.
     *
     * They are listed soonest there first. A train with no arrival time is placed by the
     * train behind it on its own line and platform: it will be there before that one, so
     * it goes just ahead of it. With no such train to go by it is last.
     */
    private fun choose(trains: List<Direct>, now: Long): List<Direct> {
        val sure = trains.associateWith { it.running && !it.held(now) }
        fun beats(other: Direct, train: Direct) =
            (sure.getValue(other) || !sure.getValue(train)) &&
                other.leaves < train.leaves &&
                (train.arrives == 0L || other.arrives in 1 until train.arrives)
        val there = trains.associate { train ->
            train.key to if (train.arrives > 0) {
                train.arrives
            } else {
                trains.filter { it.arrives > 0 && it.train.route == train.train.route && it.from == train.from && it.leaves > train.leaves }
                    .minOfOrNull { it.arrives } ?: Long.MAX_VALUE
            }
        }
        return trains.filter { train -> trains.none { beats(it, train) } }
            .sortedWith(compareBy({ there.getValue(it.key) }, { it.leaves }, { it.train.id }))
            .take(MOST)
    }

    companion object {
        /** No list of ways there is longer than this. */
        const val MOST = 4
    }
}

/**
 * Works out which trains run direct from one station to another, from the live feeds and
 * nothing else. There is no map of the lines and no timetable behind it: a train is
 * offered because a trip in the feed stops at the first station and later at the second.
 * A line that is not running that way today simply has no such trip.
 *
 * Changes of train are left out on purpose. A plan with a change holds as named about two
 * times in three; one train holds nearly nine times in ten, and when it does not the ride
 * screen says so.
 */
object Planner {
    /** Feeds built longer ago than this are not planned from. It is when a board calls its times not live. */
    const val FRESH_FOR = ArrivalsRepository.STALE_AFTER

    /**
     * Two stops listed less than this far apart are the feed having no times for them. The
     * lettered lines now and then list a stretch of stops a second apart.
     */
    private const val LEAST_BETWEEN = 20L

    private const val HOUR = 3600L

    /** How long before the time in its id a trip can list its first stop. The L does, by a quarter minute. */
    private const val AHEAD_OF_ITS_ID = 60L

    /**
     * The feeds that go on listing a trip no train took, whole, long after it was to set
     * out: the numbered lines, the L and the railway. The lettered lines' feeds do not.
     * They take a stop off such a trip as its time goes by, and nearly always give it its
     * train a minute or two after it was to set out.
     */
    private val KEEPS_AN_EMPTY_TRIP = setOf("gtfs", "gtfs-l", "gtfs-si")

    /** Every feed a train between the two could be in: the ones either station is looked for in. */
    fun slugs(from: Station, to: Station): List<String> = (Feeds.slugsFor(from) + Feeds.slugsFor(to)).distinct().sorted()

    /**
     * The lines that stop at both stations, as the first lists them, each with the feeds it
     * can ride in. This comes from the station file and says nothing about today: it is
     * only used to name what could not be checked.
     */
    internal fun shared(from: Station, to: Station): List<Pair<String, List<String>>> {
        val there = to.feedRoutes()
        return from.routes.mapNotNull { line ->
            val platform = from.platforms.firstOrNull { line in it.routes && Routes.feedId(line, it.stop) in there }
            platform?.let { line to Feeds.forRoute(line, it.stop) }
        }
    }

    /**
     * The direct trains from [from] to [to] in [feeds], each under the name it is fetched
     * by. Feeds that are too old at [now] are passed over, and the plan keeps note of
     * which were had.
     *
     * The trains are the ones the first station's own page would list, kept when they go
     * on to stop at the second. So a train is named here as it is named there, one that is
     * arriving to end its run is not offered, and one that stops at the second station
     * first is going the other way and is not offered either. Then:
     *
     *  - on the numbered lines, the L and the railway, a trip with no train whose time to
     *    set out has passed is a slot nothing filled. A few sit in those feeds for an
     *    hour. It is left out, until a train has it. See [setsOut]. On the lettered lines
     *    such a trip is a train the feed has yet to own up to, and it is offered as
     *    scheduled for as long as the first station's page lists it.
     *  - a train the feed has already seen beyond the first station is left out.
     *  - an arrival time is kept only when every stop on the way is a believable time
     *    after the one before it.
     */
    fun plan(from: Station, to: Station, catalog: Catalog, feeds: Map<String, GtfsRealtime.Feed>, now: Long): Plan {
        val live = feeds.filterValues { now - it.timestamp <= FRESH_FOR }
        val trips = live.mapValues { (_, feed) -> feed.trips.associateBy { it.id } }
        val goal = to.platforms.mapTo(HashSet()) { it.stop }
        val trains = Directions.arrivals(from, catalog, live, now).mapNotNull { due ->
            val feed = live[due.feed] ?: return@mapNotNull null
            val trip = trips.getValue(due.feed)[due.trip]?.takeIf { it.id.isNotEmpty() } ?: return@mapNotNull null
            val train = Train.of(due.feed, feed, trip, catalog)
            if (!train.assigned && due.feed in KEEPS_AN_EMPTY_TRIP && setsOut(trip) < now) return@mapNotNull null
            val on = train.place(due.stop)
            if (on < 0) return@mapNotNull null
            val off = (on + 1 until train.stops.size).firstOrNull { train.stops[it].platform in goal } ?: return@mapNotNull null
            val timed = (on until off).all { train.stops[it + 1].time - train.stops[it].time >= LEAST_BETWEEN }
            Direct(
                train = train,
                from = due.stop,
                to = train.stops[off].platform,
                leaves = train.stops[on].time,
                arrives = if (timed) train.stops[off].time else 0,
                stops = off - on,
                route = due.route,
                heading = due.heading,
                lastStop = due.destination,
            )
        }
        return Plan(
            from = from,
            to = to,
            trains = trains.distinctBy { it.key }.sortedWith(SOONEST_THERE),
            built = live.mapValues { it.value.timestamp },
        )
    }

    /**
     * When [trip] sets out, or set out. Its id opens with that, in hundredths of a minute
     * past midnight: "095750_H..N21R" is the shuttle that leaves at 3:57:30 PM. The first
     * stop a trip lists is not always that stop: a feed can take stops off the front of a
     * trip no train has, and the first one left is still ahead.
     *
     * Only the minutes and seconds are read from the id, and looked for on the clock at or
     * before the first stop listed. New York keeps a whole number of hours from the feeds'
     * own clock, so no date or time zone comes into it. That holds for as long as less
     * than an hour of stops has come off the list. An id that opens with no time leaves
     * the first stop's own time to go by.
     */
    internal fun setsOut(trip: GtfsRealtime.Trip): Long {
        val first = trip.stops.first().time
        val hundredths = trip.id.substringBefore('_', "").takeIf { head -> head.isNotEmpty() && head.all { it in '0'..'9' } }?.toLongOrNull() ?: return first
        val intoTheHour = hundredths * 6 / 10 % HOUR
        return first - ((first - intoTheHour + AHEAD_OF_ITS_ID).mod(HOUR) - AHEAD_OF_ITS_ID)
    }
}
