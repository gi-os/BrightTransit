package com.thelightphone.subway

import kotlinx.serialization.Serializable

/**
 * A subway station COMPLEX as parsed from assets/stations.json (derived from the
 * MTA "Stations" open-data table, grouped by Complex ID so transfer-connected
 * platforms — e.g. the three 14 St-Union Sq entries — are one station).
 *
 * [id] is the MTA Complex ID (stable key for starring). [stops] holds the GTFS
 * parent stop ids of every constituent platform; realtime feeds append a
 * direction suffix ("635N" / "635S"), so we match against each stop id in turn.
 */
@Serializable
data class Station(
    val id: String,
    val name: String,
    val boro: String = "",
    val routes: List<String> = emptyList(),
    val stops: List<String> = emptyList(),
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val nl: String = "",   // north direction label ("Uptown & The Bronx")
    val sl: String = "",   // south direction label ("Downtown & Brooklyn")
) {
    /** Great-circle distance to a point, in meters (haversine). */
    fun distanceMetersTo(lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat)
        val dLon = Math.toRadians(lon2 - lon)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }
    val boroLabel: String
        get() = when (boro) {
            "M" -> "Manhattan"
            "Bx" -> "The Bronx"
            "Bk" -> "Brooklyn"
            "Q" -> "Queens"
            "SI" -> "Staten Island"
            else -> boro
        }
}

@Serializable
data class StationsFile(val stations: List<Station>)

enum class Direction { NORTH, SOUTH }

/** One upcoming train at a station. */
data class Arrival(
    val route: String,
    val direction: Direction,
    val epochSeconds: Long,
) {
    fun minutesFrom(nowSeconds: Long): Int =
        (((epochSeconds - nowSeconds) + 30) / 60).toInt()
}

/**
 * MTA GTFS-realtime feed endpoints. No API key required (as of 2023).
 * Each feed carries a group of lines; a station is served by the union of the
 * feeds for its routes.
 *
 * Endpoints live at
 *   https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct/<slug>
 * but the gateway treats "nyct/<slug>" as a SINGLE path parameter, so the
 * internal slash MUST be percent-encoded (`nyct%2F<slug>`) or the request 403s.
 * See [ArrivalsRepository] for how the request is built.
 */
object MtaFeeds {
    /**
     * Full feed URL. The internal slash between "nyct" and the slug MUST stay
     * percent-encoded (`%2F`) — the gateway treats it as one path parameter and
     * 403s on a literal slash. Ktor preserves the `%2F` when given the encoded
     * string directly.
     */
    fun url(slug: String) =
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2F$slug"

    /** Which feed(s) carry a given daytime route. */
    fun feedsForRoute(route: String): List<String> = when (route.uppercase()) {
        "1", "2", "3", "4", "5", "6", "7" -> listOf("gtfs")
        "A", "C", "E" -> listOf("gtfs-ace")
        "B", "D", "F", "M" -> listOf("gtfs-bdfm")
        "G" -> listOf("gtfs-g")
        "J", "Z" -> listOf("gtfs-jz")
        "N", "Q", "R", "W" -> listOf("gtfs-nqrw")
        "L" -> listOf("gtfs-l")
        "SIR" -> listOf("gtfs-si")
        // Shuttles all show as "S" in the static table but live in different
        // feeds (42 St -> numbered, Franklin/Rockaway -> ace). Query both.
        "S" -> listOf("gtfs", "gtfs-ace")
        else -> emptyList()
    }

    /** Distinct feed slugs needed to cover every route at [station]. */
    fun slugsFor(station: Station): List<String> =
        station.routes
            .flatMap { feedsForRoute(it) }
            .distinct()
}
