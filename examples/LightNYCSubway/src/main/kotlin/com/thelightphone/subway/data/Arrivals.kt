package com.thelightphone.subway.data

enum class Direction { NORTH, SOUTH }

/**
 * One train on its way to a station.
 *
 * [heading] is the direction as a rider would say it ("Manhattan", "Uptown", "Canarsie");
 * trains with the same heading are listed together. [destination] is the last stop of this
 * particular train, which is what the front of the train says.
 */
data class Arrival(
    val route: String,
    val direction: Direction,
    val epochSeconds: Long,
    val heading: String,
    val destination: String,
) {
    fun minutesFrom(nowSeconds: Long): Int = (((epochSeconds - nowSeconds) + 30) / 60).toInt()

    /** Gone from the platform: more than half a minute past its time. */
    fun departed(nowSeconds: Long): Boolean = epochSeconds < nowSeconds - 30
}

/**
 * What the tool knows about one station right now.
 *
 * [asOf] is when the MTA built the oldest feed these times came from. [stale] means the
 * last download failed and these are the times from the one before. [failed] means there
 * is nothing to show at all.
 */
data class Board(
    val station: Station,
    val arrivals: List<Arrival> = emptyList(),
    val asOf: Long = 0,
    val stale: Boolean = false,
    val failed: Boolean = false,
    val loading: Boolean = false,
) {
    /**
     * Trains still to come, grouped by where they are headed, soonest first inside each group.
     *
     * Grouping is by heading and not by the feed's north/south. At Delancey St-Essex St the
     * F to Brooklyn is "south" and the J to Brooklyn is "north"; a rider wants them together.
     * Groups made mostly of northbound trains come first, so a plain station still reads
     * Uptown then Downtown.
     */
    fun groups(nowSeconds: Long): List<Group> =
        arrivals.filter { !it.departed(nowSeconds) }
            .groupBy { it.heading }
            .map { (heading, list) ->
                val north = list.count { it.direction == Direction.NORTH }
                Group(if (north * 2 >= list.size) Direction.NORTH else Direction.SOUTH, heading, list.sortedBy { it.epochSeconds })
            }
            .sortedWith(compareBy<Group> { it.direction.ordinal }.thenBy { it.arrivals.first().epochSeconds })

    data class Group(val direction: Direction, val heading: String, val arrivals: List<Arrival>)
}

/** Line names as the feeds spell them versus how they are drawn. */
object Routes {
    /** "GS", "FS" and "H" are the three shuttles, all signed S. "SI" is the Staten Island Railway. */
    fun display(feedRoute: String): String = when (val r = feedRoute.uppercase()) {
        "GS", "FS", "H", "SS" -> "S"
        "SI" -> "SIR"
        else -> r
    }

    /** Express services ("6X", "7X", "FX") are drawn as a diamond around the plain letter. */
    fun isExpress(route: String): Boolean = route.length == 2 && route[1] == 'X'

    fun glyph(route: String): String = if (isExpress(route)) route.dropLast(1) else route

    /** A favorited "6" also covers the "6X". */
    fun plain(route: String): String = glyph(route)
}

/** The MTA's realtime feeds. No key is needed. */
object Feeds {
    /**
     * The gateway treats "nyct/<slug>" as one path segment, so the slash has to stay
     * percent-encoded or the request is refused.
     */
    fun url(slug: String) = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2F$slug"

    private fun forRoute(route: String, stop: String): List<String> = when (route.uppercase()) {
        "1", "2", "3", "4", "5", "6", "7" -> listOf("gtfs")
        "A", "C", "E" -> listOf("gtfs-ace")
        "B", "D", "F", "M" -> listOf("gtfs-bdfm")
        "G" -> listOf("gtfs-g")
        "J", "Z" -> listOf("gtfs-jz")
        "N", "Q", "R", "W" -> listOf("gtfs-nqrw")
        "L" -> listOf("gtfs-l")
        "SIR" -> listOf("gtfs-si")
        // The 42 St shuttle rides in the numbered feed; Franklin Av and Rockaway Park in the lettered ones.
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
    /**
     * The MTA's sign for this platform if it says something useful. Otherwise the borough
     * the train ends in, if that is a different borough ("Manhattan", "Queens"), and
     * failing that the name of the last stop ("Canarsie", "Coney Island").
     */
    fun heading(station: Station, platform: Platform, direction: Direction, terminal: Pair<Station, Platform>?): String {
        val sign = if (direction == Direction.NORTH) platform.n else platform.s
        if (sign.isNotEmpty()) return sign
        if (terminal == null) return if (direction == Direction.NORTH) "Northbound" else "Southbound"
        val (endStation, endPlatform) = terminal
        if (endStation.boro != station.boro) return endStation.boroLabel
        return shortName(endPlatform.name.ifEmpty { endStation.name })
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

    /**
     * Turns the trips in [feeds] into the trains due at [station].
     *
     * A train is left out when this is its last stop: it is arriving to empty out, not
     * something to wait for.
     */
    fun arrivals(station: Station, catalog: Catalog, feeds: List<GtfsRealtime.Feed>, nowSeconds: Long): List<Arrival> {
        val platforms = station.platforms.associateBy { it.stop }
        val out = ArrayList<Arrival>()
        for (feed in feeds) for (trip in feed.trips) {
            val last = trip.stops.last()
            for ((index, stop) in trip.stops.withIndex()) {
                if (index == trip.stops.lastIndex) continue
                val id = stop.stopId
                if (id.length < 2) continue
                val platform = platforms[id.dropLast(1)] ?: continue
                val direction = when (id.last()) {
                    'N' -> Direction.NORTH
                    'S' -> Direction.SOUTH
                    else -> continue
                }
                if (stop.time < nowSeconds - 30) continue
                val terminal = catalog.stop(last.stopId.dropLast(1))
                out += Arrival(
                    route = Routes.display(trip.route),
                    direction = direction,
                    epochSeconds = stop.time,
                    heading = heading(station, platform, direction, terminal),
                    destination = terminal?.let { it.second.name.ifEmpty { it.first.name } } ?: "",
                )
            }
        }
        return out
            .distinctBy { Triple(it.route, it.direction, it.epochSeconds) }
            .sortedBy { it.epochSeconds }
    }
}
