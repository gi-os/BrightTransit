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
 * "Uptown"). They are empty where the MTA table only has a compass word, and then the
 * direction is named after where the train is going. See [Directions].
 */
@Serializable
data class Platform(
    val stop: String,
    val name: String = "",
    val routes: List<String> = emptyList(),
    val n: String = "",
    val s: String = "",
)

/**
 * A station as a rider thinks of it: every platform you can reach without leaving the
 * turnstiles. [id] is the MTA Complex ID, which is also the key a saved station is stored
 * under. [aka] holds other names the same place goes by, for search only.
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

    /** Great-circle distance to a point, in meters. */
    fun metersTo(lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat)
        val dLon = Math.toRadians(lon2 - lon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
}

@Serializable
private data class StationsFile(val stations: List<Station>)

/** Every station, indexed the three ways the tool looks them up. */
class Catalog(val stations: List<Station>) {
    private val byId = stations.associateBy { it.id }
    private val byStop: Map<String, Pair<Station, Platform>> =
        stations.flatMap { st -> st.platforms.map { it.stop to (st to it) } }.toMap()

    val search = StationSearch(stations)

    fun station(id: String): Station? = byId[id]

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
