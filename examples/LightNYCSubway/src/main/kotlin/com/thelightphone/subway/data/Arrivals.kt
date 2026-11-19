package com.thelightphone.subway.data

enum class Direction {
    NORTH, SOUTH;

    val opposite: Direction get() = if (this == NORTH) SOUTH else NORTH
}

/**
 * One train on its way to a station.
 *
 * [heading] is the direction as a rider would say it ("Manhattan", "Uptown", "Brooklyn");
 * trains with the same heading are listed together. [destination] is the last stop of this
 * particular train, which is what the front of the train says. [stop] is the platform it
 * leaves from, as the feeds name it, and [direction] the side of that platform.
 *
 * [assigned] is false for a train that is so far only on the timetable: its time is the
 * schedule's, not a train's. See [GtfsRealtime.Trip]. [feed] is the feed the train was read
 * from, which is what says how old its time is, and [trip] its id there, which is how the
 * same train is found again in the next download.
 */
data class Arrival(
    val route: String,
    val direction: Direction,
    val epochSeconds: Long,
    val heading: String,
    val destination: String,
    val assigned: Boolean = true,
    val stop: String = "",
    val feed: String = "",
    val trip: String = "",
) {
    fun minutesFrom(nowSeconds: Long): Int = (((epochSeconds - nowSeconds) + 30) / 60).toInt()

    /** Gone from the platform: more than half a minute past its time. */
    fun departed(nowSeconds: Long): Boolean = epochSeconds < nowSeconds - 30
}

/**
 * What the tool knows about one station right now.
 *
 * [asOf] is when the MTA built the oldest feed these times rest on. [stale] means the
 * last download failed and these are the times from the one before. [failed] means there
 * is nothing to show at all. [loading] means an answer has been asked for and is not back
 * yet. [missing] are the station's lines whose feed could not be had, named as riders name
 * them and in the station's own order; the other lines' times are still good. [sources]
 * are the feeds the board was put together from, kept so it can be judged again as they
 * age. See [of].
 */
data class Board(
    val station: Station,
    val arrivals: List<Arrival> = emptyList(),
    val asOf: Long = 0,
    val stale: Boolean = false,
    val failed: Boolean = false,
    val loading: Boolean = false,
    val missing: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
) {
    /**
     * One of the feeds a board was put together from. [built] is when the MTA built the
     * copy in hand, or null when there is none. [old] marks a copy kept from before a
     * failed download.
     */
    data class Source(val slug: String, val built: Long?, val old: Boolean = false) {
        /** False for no copy at all, and for one the MTA built too long ago for its times to be shown. */
        fun had(now: Long): Boolean = built != null && now - built <= ArrivalsRepository.KEEP_FOR
    }

    /**
     * What this board has to say at [now]. Home and the station page both draw from this
     * and nothing else, so they cannot disagree about what is known.
     *
     * The age of the times is judged here, when they are drawn, and not only when they were
     * fetched: a board can sit in memory through a sleep or a tunnel. Times older than
     * [ArrivalsRepository.STALE_AFTER] are marked not live, and past
     * [ArrivalsRepository.KEEP_FOR] they are not shown at all. "No trains" is only ever
     * said from times that are live and cover every line; anything less is "unavailable".
     * While an answer is on its way, a board with nothing it can show says it is loading.
     */
    fun view(now: Long): View {
        val board = at(now)
        val age = now - board.asOf
        val groups = if (board.failed || age > ArrivalsRepository.KEEP_FOR) emptyList() else board.groups(now)
        val old = board.stale || age > ArrivalsRepository.STALE_AFTER
        return when {
            groups.isNotEmpty() -> View.Trains(groups, old, board.missing, board.asOf)
            loading -> View.Loading
            board.failed || old || board.missing.isNotEmpty() -> View.Unavailable
            else -> View.NoTrains
        }
    }

    /**
     * This board as it stands at [now]. A feed that has grown too old to show since the
     * board was put together takes its own lines with it and leaves the rest as they were.
     */
    private fun at(now: Long): Board =
        if (sources.all { it.built == null || it.had(now) }) {
            this
        } else {
            of(station, arrivals, sources, now).copy(loading = loading)
        }

    /**
     * The board to show once [fresh] has come back. A load that failed outright does not
     * blank trains already on screen: they stay, marked stale, until they are too old to show.
     */
    fun replacedBy(fresh: Board, now: Long): Board {
        val kept = at(now)
        return if (fresh.failed && kept.arrivals.isNotEmpty() && now - kept.asOf <= ArrivalsRepository.KEEP_FOR) {
            kept.copy(stale = true, loading = false, sources = kept.sources.map { it.copy(old = true) })
        } else {
            fresh
        }
    }

    sealed interface View {
        /**
         * Times to show. With [stale] they are old and must not look live. [asOf] is when
         * the MTA built the oldest feed among them.
         */
        data class Trains(val groups: List<Group>, val stale: Boolean, val missing: List<String>, val asOf: Long) : View {
            /** Said above the times when some of the station's lines are not among them. */
            val missingNote: String?
                get() = if (missing.isEmpty()) null else "No times for the ${missing.joinToString(" ")} right now."
        }

        /** No times to show, and the one sentence that stands where they would be. */
        sealed class Message(val text: String) : View

        data object Loading : Message("Loading…")

        data object Unavailable : Message("Times unavailable. Check your connection.")

        data object NoTrains : Message("No trains due right now.")
    }

    /**
     * Trains still to come, grouped by where they are headed.
     *
     * Grouping is by heading and not by the feed's north/south. At Delancey St-Essex St the
     * F to Brooklyn is "south" and the J to Brooklyn is "north"; a rider wants them together.
     *
     * Where a heading sits never depends on when its trains come, so it is in the same
     * place after every refresh. Each heading goes by the first of its lines in the
     * station's own order. The headings that line leaves for from the north side come
     * first, so a plain station still reads Uptown then Downtown; after that they follow
     * the station's order of lines, and then their names.
     */
    fun groups(nowSeconds: Long): List<Group> {
        // A line the station does not list (a rerouted train) goes after the ones it does.
        fun place(route: String) = station.routes.indexOf(Routes.plain(route)).let { if (it < 0) station.routes.size else it }
        return arrivals.filter { !it.departed(nowSeconds) }
            .sortedBy { it.epochSeconds }
            .groupBy { it.heading }
            .map { (heading, trains) ->
                val rows = trains.groupBy { it.route to it.destination }
                    .map { (key, list) -> Row(key.first, key.second, list) }
                    .sortedWith(compareBy<Row>({ place(it.route) }, { it.route }, { it.destination }))
                val first = rows.first().route
                val north = trains.any { it.route == first && it.direction == Direction.NORTH }
                Group(if (north) Direction.NORTH else Direction.SOUTH, heading, trains, rows)
            }
            .sortedWith(compareBy<Group>({ it.direction.ordinal }, { place(it.rows.first().route) }, { it.heading }))
    }

    /**
     * The trains under one heading, twice over: [arrivals] is every train soonest first,
     * and [rows] is the same trains set out by line and last stop. [direction] is the side
     * the heading is listed on, which is the side its first line leaves from.
     */
    data class Group(val direction: Direction, val heading: String, val arrivals: List<Arrival>, val rows: List<Row>)

    /**
     * One line to one last stop, with its trains soonest first. A big station lists a row
     * for every line that is coming, where the next few trains whoever they are would leave
     * most lines out. A train turning back early has a last stop of its own, so it gets a
     * row of its own. Rows follow the station's order of lines, and a line's rows go by
     * the name of the last stop: like the headings, a row is in the same place after every
     * refresh, whichever train happens to be next.
     */
    data class Row(val route: String, val destination: String, val arrivals: List<Arrival>)

    companion object {
        /**
         * The board for [station] at [now], given the trains read from [sources]. This is
         * where it is settled which of them are shown and what is said about the rest.
         *
         * A feed the MTA built more than [ArrivalsRepository.KEEP_FOR] ago is as good as
         * not had: its trains are left off and its lines are named missing, and the lines
         * whose feeds are current stay up.
         *
         * [asOf] and [stale] go by the feeds that count here and no others. A feed counts
         * when it put a train on this board, or when a line of the station rides in it and
         * has no train: then what the board says of that line is only as new as the feed.
         * A station is also looked for in feeds none of its lines ride in (see
         * [Feeds.slugsFor]), and how old such a copy is says nothing about the times shown.
         */
        fun of(station: Station, arrivals: List<Arrival>, sources: List<Source>, now: Long): Board {
            val (had, gone) = sources.partition { it.had(now) }
            val read = arrivals.filter { train -> had.any { it.slug == train.feed } }
            val missing = missing(station, gone.map { it.slug }, read)
            // A train of a line named missing is left off with the rest of its line. One
            // rerouted E under "No times for the E" would pass for the next E.
            val shown = read.filter { Routes.plain(it.route) !in missing }
            val listed = shown.mapTo(HashSet()) { Routes.plain(it.route) }
            val counting = had.filter { feed ->
                shown.any { it.feed == feed.slug } || station.platforms.any { platform ->
                    platform.routes.any { line -> line !in listed && feed.slug in Feeds.forRoute(line, platform.stop) }
                }
            }
            val settled = had + gone.map { Source(it.slug, built = null) }
            if (counting.isEmpty()) return Board(station, failed = true, missing = station.routes, sources = settled)
            val built = counting.mapNotNull { it.built }
            return Board(
                station = station,
                arrivals = shown,
                asOf = built.min(),
                // The MTA rebuilds a feed every few seconds. Two minutes without one means it
                // is not live any more, whatever the download said.
                stale = counting.any { it.old } || built.any { now - it > ArrivalsRepository.STALE_AFTER },
                missing = missing,
                sources = settled,
            )
        }

        /**
         * The station's lines that have no times because the feed they ride in is one of
         * [gone]. A line with a train on the board from some other feed is still one of
         * them: that train is a stray, and the rest of the line is not there. Only the
         * shuttles are let off for having a train listed, since they are looked for in two
         * feeds and ride in one.
         */
        private fun missing(station: Station, gone: List<String>, arrivals: List<Arrival>): List<String> {
            if (gone.isEmpty()) return emptyList()
            val listed = arrivals.mapTo(HashSet()) { Routes.plain(it.route) }
            return station.routes.filter { line ->
                station.platforms.any { platform ->
                    val feeds = Feeds.forRoute(line, platform.stop)
                    line in platform.routes && feeds.any { it in gone } && (feeds.size == 1 || line !in listed)
                }
            }
        }
    }
}

/** Line names as the feeds spell them versus how they are drawn. */
object Routes {
    /**
     * "GS", "FS" and "H" are the three shuttles, all signed S. "SI" is the Staten Island
     * Railway and "SS" its rush-hour express, which riders do not tell apart by name.
     */
    fun display(feedRoute: String): String = when (val r = feedRoute.uppercase()) {
        "GS", "FS", "H" -> "S"
        "SI", "SS" -> "SIR"
        else -> r
    }

    /** Express services ("6X", "7X", "FX") are drawn as a diamond around the plain letter. */
    fun isExpress(route: String): Boolean = route.length == 2 && route[1] == 'X'

    fun glyph(route: String): String = if (isExpress(route)) route.dropLast(1) else route

    /** A favorited "6" also covers the "6X". */
    fun plain(route: String): String = glyph(route)

    /**
     * A line at one platform, spelled the way the feeds spell it. The station file writes
     * all three shuttles as "S"; which one it is can only be told from the stop: 42 St has
     * numbered stops (901, 902), Rockaway Park has H stops, and Franklin Av is the rest.
     */
    fun feedId(route: String, stop: String): String = when (route) {
        "SIR" -> "SI"
        "S" -> when {
            stop.firstOrNull()?.isDigit() == true -> "GS"
            stop.startsWith("H") -> "H"
            else -> "FS"
        }
        else -> route
    }

    /**
     * A line as an alert names it, turned into the id stations are matched on. An alert for
     * the "6X" is an alert for the 6, and the railway turns up as "SI", "SIR" and "SS".
     */
    fun alertId(feedRoute: String): String = when (val r = feedRoute.uppercase()) {
        "SIR", "SS" -> "SI"
        else -> plain(r)
    }
}

/** The MTA's realtime feeds. No key is needed. */
object Feeds {
    /**
     * The gateway treats "nyct/<slug>" as one path segment, so the slash has to stay
     * percent-encoded or the request is refused.
     */
    fun url(slug: String) = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2F$slug"

    /** Service alerts for every line in one feed. Its slash has to stay encoded too. */
    const val ALERTS = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts"

    internal fun forRoute(route: String, stop: String): List<String> = when (route.uppercase()) {
        "1", "2", "3", "4", "5", "6", "7" -> listOf("gtfs")
        "A", "C", "E" -> listOf("gtfs-ace")
        "B", "D", "F", "M" -> listOf("gtfs-bdfm")
        "G" -> listOf("gtfs-g")
        "J", "Z" -> listOf("gtfs-jz")
        "N", "Q", "R", "W" -> listOf("gtfs-nqrw")
        "L" -> listOf("gtfs-l")
        "SIR" -> listOf("gtfs-si")
        // The 42 St shuttle rides in the numbered feed, Franklin Av with the B D F M and Rockaway Park with the A C E.
        "S" -> if (stop.firstOrNull()?.isDigit() == true) listOf("gtfs") else listOf("gtfs-ace", "gtfs-bdfm")
        else -> emptyList()
    }

    /**
     * Every feed that can carry a train for [station]. The A C E and B D F M share so much
     * track, and swap onto each other so often on nights and weekends, that a station on
     * one is always checked in both.
     */
    fun slugsFor(station: Station): List<String> {
        val slugs = station.platforms
            .flatMap { p -> p.routes.flatMap { forRoute(it, p.stop) } }
            .toMutableSet()
        if ("gtfs-ace" in slugs || "gtfs-bdfm" in slugs) {
            slugs += "gtfs-ace"
            slugs += "gtfs-bdfm"
        }
        return slugs.sorted()
    }
}

/** Names a direction of travel. */
object Directions {
    private const val MANHATTAN = "M"

    /**
     * The side of the platform a train leaves from: the letter that ends its stop id, or
     * null for anything else there.
     *
     * The M is the exception. The feeds have it northbound all the way from Middle Village
     * to Forest Hills, so from Myrtle Av to the end of the Nassau St line, where it shares
     * its track with the J and Z, it carries the opposite letter to the train in front of
     * it. The signs there were written for the J, so the M is turned round to match.
     */
    fun side(route: String, stop: String, letter: Char): Direction? {
        val side = when (letter) {
            'N' -> Direction.NORTH
            'S' -> Direction.SOUTH
            else -> return null
        }
        val againstTheJ = route == "M" && stop.startsWith("M") && (stop.drop(1).toIntOrNull() ?: 0) >= 11
        return if (againstTheJ) side.opposite else side
    }

    /**
     * Names the way a train is going, as a rider standing in [station] would say it.
     * [terminal] is its last stop and [viaManhattan] whether it stops in Manhattan on the
     * way there. The first of these that applies:
     *
     *  1. The MTA's sign for that side of the platform, where it has one.
     *  2. Outside Manhattan, "Manhattan" for a train that still stops there, wherever it
     *     ends: the F from Bergen St ends in Queens, and nobody boards it for Queens.
     *  3. The borough the train ends in, when that is not this one.
     *  4. Outside Manhattan, this borough. It is the way out from the city, and it is one
     *     name however many places the trains end or turn back: everything leaving
     *     Atlantic Av the other way is "Brooklyn". Each train's own last stop is on its row.
     *  5. In Manhattan, for a train that ends there, its last stop.
     */
    fun heading(station: Station, platform: Platform, direction: Direction, terminal: Pair<Station, Platform>, viaManhattan: Boolean): String {
        val sign = platform.sign(direction)
        val end = terminal.first
        return when {
            sign.isNotEmpty() -> sign
            station.boro != MANHATTAN && viaManhattan -> "Manhattan"
            end.boro != station.boro -> end.boroLabel
            station.boro != MANHATTAN -> station.boroLabel
            else -> lastStop(terminal)
        }
    }

    /**
     * "Canarsie-Rockaway Pkwy" is "Canarsie". Names that start with a number ("241 St") and
     * names of two streets ("Myrtle-Wyckoff Avs") stay whole, since half of them is not a place.
     */
    fun shortName(name: String): String {
        if (name.endsWith("Avs") || name.endsWith("Sts")) return name
        val head = name.substringBefore('-').trim()
        return if (head.length >= 4 && !head[0].isDigit()) head else name
    }

    private fun name(stop: Pair<Station, Platform>) = stop.second.name.ifEmpty { stop.first.name }

    private fun lastStop(terminal: Pair<Station, Platform>) = shortName(name(terminal))

    /** A train due at the station, before it is settled what to call its direction. */
    private class Due(
        val feed: String,
        val trip: GtfsRealtime.Trip,
        val platform: Platform,
        val side: Direction,
        val time: Long,
        val terminal: Pair<Station, Platform>,
        val viaManhattan: Boolean,
        var heading: String,
    )

    /**
     * Turns the trips in [feeds], each under the name it is fetched by, into the trains
     * due at [station].
     *
     * A train is left out when this is its last stop: it is arriving to empty out, not
     * something to wait for. The feeds also list points that are not stations (a siding
     * past Broad Channel where the Rockaway Park shuttle turns round); those are passed
     * over, so a train's last stop is always the last place a rider could get off.
     */
    fun arrivals(station: Station, catalog: Catalog, feeds: Map<String, GtfsRealtime.Feed>, nowSeconds: Long): List<Arrival> {
        val platforms = station.platforms.associateBy { it.stop }
        val due = ArrayList<Due>()
        for ((slug, feed) in feeds) for (trip in feed.trips) {
            for ((index, stop) in trip.stops.withIndex()) {
                val id = stop.stopId
                if (id.length < 2) continue
                val platform = platforms[id.dropLast(1)] ?: continue
                val side = side(trip.route, platform.stop, id.last()) ?: continue
                if (stop.time < nowSeconds - 30) continue
                val ahead = trip.stops.subList(index + 1, trip.stops.size).mapNotNull { catalog.stop(it.stopId.dropLast(1)) }
                val terminal = ahead.lastOrNull() ?: continue
                val viaManhattan = ahead.any { it.first.boro == MANHATTAN }
                due += Due(slug, trip, platform, side, stop.time, terminal, viaManhattan, heading(station, platform, side, terminal, viaManhattan))
            }
        }
        untangle(due)
        return due
            .map {
                Arrival(
                    route = Routes.display(it.trip.route),
                    direction = it.side,
                    epochSeconds = it.time,
                    heading = it.heading,
                    destination = name(it.terminal),
                    assigned = it.trip.assigned,
                    stop = it.platform.stop,
                    feed = it.feed,
                    trip = it.trip.id,
                )
            }
            // The feeds now and then list one train under two trips. The copy that a train
            // has been given is the one to keep.
            .sortedBy { !it.assigned }
            .distinctBy { it.copy(assigned = true, feed = "", trip = "") }
            .sortedBy { it.epochSeconds }
    }

    /**
     * One name cannot stand for both ways out of a platform. The rule gives the same one
     * twice where trains both ways end in the same borough: at Hoyt-Schermerhorn Sts the G
     * to Court Sq and the A to Far Rockaway both end in Queens, and at Prospect Park the
     * shuttle to Franklin Av and the Q to Coney Island both stay in Brooklyn.
     *
     * A sign keeps its name. Otherwise the name stays with the side that leads away from
     * Manhattan, where a borough is what riders call it, and the trains on the other side
     * are named after their last stop ("Court Sq", "Franklin Av"). Where neither side or
     * both lead to Manhattan there is no way out to speak of, and both go by last stop.
     */
    private fun untangle(due: List<Due>) {
        for ((platform, here) in due.groupBy { it.platform }) {
            val (north, south) = here.partition { it.side == Direction.NORTH }
            val onBoth = north.map { it.heading }.intersect(south.map { it.heading }.toSet())
            if (onBoth.isEmpty()) continue
            val northIn = north.any { it.viaManhattan }
            val southIn = south.any { it.viaManhattan }
            val wayOut = when {
                northIn == southIn -> null
                northIn -> Direction.SOUTH
                else -> Direction.NORTH
            }
            for (name in onBoth) {
                val keeper = Direction.entries.firstOrNull { platform.sign(it) == name } ?: wayOut
                here.filter { it.heading == name && it.side != keeper && platform.sign(it.side).isEmpty() }
                    .forEach { it.heading = lastStop(it.terminal) }
            }
        }
    }
}
