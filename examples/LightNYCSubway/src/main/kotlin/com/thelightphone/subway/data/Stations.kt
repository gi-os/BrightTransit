package com.thelightphone.subway.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One set of tracks inside a station: a GTFS parent stop. The realtime feeds name a stop as
 * this id plus a direction letter ("L14N", "L14S").
 *
 * [n] and [s] are the words on the MTA's own signs for each direction ("Manhattan",
 * "Uptown"). They are empty where the MTA table only has a compass word, or where one
 * word could not be right for every line that stops here, and then the direction is named
 * train by train after where each is going. See [Directions.heading].
 */
@Serializable
data class Platform(
    val stop: String,
    val name: String = "",
    val routes: List<String> = emptyList(),
    val n: String = "",
    val s: String = "",
) {
    fun sign(direction: Direction): String = if (direction == Direction.NORTH) n else s
}

/**
 * The least time the MTA allows to change from a train at one platform of a station to a
 * train at another, in seconds, from the transfers file in its schedule. [from] and [to]
 * are the platforms' stop ids, the same id twice for a change on one platform.
 */
@Serializable
data class Transfer(val from: String, val to: String, val seconds: Long)

/**
 * A station as a rider thinks of it: every platform you can reach without leaving the
 * turnstiles. [id] is the MTA Complex ID, which is also the key a saved station is stored
 * under. [aka] holds other names the same place goes by, and [avenues] the avenues its
 * lines run under ("7 Av" at 34 St-Penn Station). Both are for search only. [changes] are
 * the MTA's times to change trains here that are not [SAME_PLATFORM]: see [change].
 */
@Serializable
data class Station(
    val id: String,
    val name: String,
    val boro: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val routes: List<String> = emptyList(),
    val platforms: List<Platform> = emptyList(),
    val aka: List<String> = emptyList(),
    val avenues: List<String> = emptyList(),
    val changes: List<Transfer> = emptyList(),
) {
    val boroLabel: String
        get() = when (boro) {
            "M" -> "Manhattan"
            "Bx" -> "The Bronx"
            "Bk" -> "Brooklyn"
            "Q" -> "Queens"
            "SI" -> "Staten Island"
            else -> boro
        }

    /**
     * The station's lines under the ids the feeds use, which is what a service alert is
     * matched on. A function and not a property, so reading the station file is unchanged.
     */
    fun feedRoutes(): Set<String> =
        platforms.flatMapTo(HashSet()) { platform -> platform.routes.map { Routes.feedId(it, platform.stop) } }

    /**
     * The least time, in seconds, the MTA allows to get off a train at platform [from] here
     * and onto one at platform [to], or null where it lists no change between the two. A
     * change on one platform takes [SAME_PLATFORM] unless the station file says otherwise.
     * build_stations.py says where the times come from.
     */
    fun change(from: String, to: String): Long? =
        changes.firstOrNull { it.from == from && it.to == to }?.seconds
            ?: SAME_PLATFORM.takeIf { from == to && platforms.any { it.stop == from } }

    /** Great-circle distance to a point, in meters. */
    fun metersTo(lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat)
        val dLon = Math.toRadians(lon2 - lon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    companion object {
        /** What the MTA gives most platforms for a change on the platform itself. */
        const val SAME_PLATFORM = 180L
    }
}

@Serializable
private data class StationsFile(val stations: List<Station>)

/** Every station, indexed the three ways the tool looks them up. */
class Catalog(val stations: List<Station>) {
    private val byId = stations.associateBy { it.id }
    private val byStop: Map<String, Pair<Station, Platform>> =
        stations.flatMap { st -> st.platforms.map { it.stop to (st to it) } }.toMap()

    private val sharedNames: Set<String> = stations.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys

    val search = StationSearch(stations)

    fun station(id: String): Station? = byId[id]

    /**
     * Whether another station goes by the same name as [station]. Six are called 86 St,
     * and anywhere one of them is named it has to be told from the other five.
     */
    fun hasNamesake(station: Station): Boolean = station.name in sharedNames

    /**
     * The station ids a saved list really means, in order and without repeats.
     *
     * Early versions saved a station under the id of one of its platforms ("L12"), later ones
     * under the station's own id ("625"). A list that has been through both can hold the same
     * station two or three times, and platform ids no later version could read. Anything
     * that matches neither is dropped.
     */
    fun canonicalIds(saved: List<String>): List<String> =
        saved.mapNotNull { byId[it]?.id ?: byStop[it]?.first?.id }.distinct()

    /** The station and platform a GTFS parent stop id belongs to. */
    fun stop(stopId: String): Pair<Station, Platform>? = byStop[stopId]

    /**
     * What a platform is called: its own name where it has one (the L stops at "6 Av" and
     * "8 Av" inside the station called 14 St), and the station's otherwise. Empty for an
     * id that is not a platform.
     */
    fun stopName(stopId: String): String = byStop[stopId]?.let { (station, platform) -> platform.name.ifEmpty { station.name } }.orEmpty()

    fun nearest(lat: Double, lon: Double, limit: Int): List<Pair<Station, Double>> =
        stations.asSequence()
            .filter { it.lat != 0.0 || it.lon != 0.0 }
            .map { it to it.metersTo(lat, lon) }
            .sortedBy { it.second }
            .take(limit)
            .toList()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Catalog = Catalog(json.decodeFromString<StationsFile>(text).stations)
    }
}
