package com.thelightphone.subway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Approximate location from IP geolocation (GPS APIs are blocked by the SDK). */
data class IpGeo(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String = "",
)

/**
 * Fetches and decodes MTA realtime feeds for a single station. The [HttpClient]
 * is created lazily on first use (off the main thread) so it never slows startup.
 */
class ArrivalsRepository {

    private var client: HttpClient? = null

    private fun client(): HttpClient = client ?: HttpClient(OkHttp).also { client = it }

    /**
     * Query every feed that serves [station], decode it, and keep only the trains
     * stopping at this station in the future, soonest first.
     */
    suspend fun arrivalsFor(station: Station, nowSeconds: Long): List<Arrival> = coroutineScope {
        val slugs = MtaFeeds.slugsFor(station)
        val raws = slugs.map { slug ->
            async { runCatching { fetch(slug) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()

        val stopIds = station.stops.toHashSet()
        raws.mapNotNull { raw ->
            if (raw.stopId.isEmpty()) return@mapNotNull null
            val base = raw.stopId.dropLast(1)
            if (base !in stopIds) return@mapNotNull null
            val dir = when (raw.stopId.last()) {
                'N' -> Direction.NORTH
                'S' -> Direction.SOUTH
                else -> return@mapNotNull null
            }
            if (raw.time < nowSeconds - 30) return@mapNotNull null // already left
            Arrival(route = raw.route, direction = dir, epochSeconds = raw.time)
        }
            .distinctBy { Triple(it.route, it.direction, it.epochSeconds) }
            .sortedBy { it.epochSeconds }
    }

    /**
     * Approximate device location via IP geolocation. Tries several providers so a
     * single one being blocked/rate-limited doesn't break "nearby". Not GPS-accurate
     * (neighborhood level) — but the SDK forbids the Android location APIs, so this
     * is the only automatic option.
     */
    suspend fun ipLocation(): IpGeo? {
        val providers = listOf(
            "https://ipapi.co/json/",
            "https://ipwho.is/",
            "https://freeipapi.com/api/json",
            "https://get.geojs.io/v1/ip/geo.json",
        )
        for (url in providers) {
            val geo = runCatching {
                val body: String = client().get(url) { header("Accept", "application/json") }.body()
                parseGeo(body)
            }.getOrNull()
            if (geo != null) return geo
        }
        return null
    }

    private fun parseGeo(body: String): IpGeo? {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun num(vararg keys: String): Double? {
            for (k in keys) {
                val p = obj[k] as? JsonPrimitive ?: continue
                val d = p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
                if (d != null) return d
            }
            return null
        }
        fun str(vararg keys: String): String {
            for (k in keys) {
                val s = (obj[k] as? JsonPrimitive)?.contentOrNull
                if (!s.isNullOrBlank()) return s
            }
            return ""
        }
        val lat = num("latitude", "lat")
        val lon = num("longitude", "lon", "lng")
        if (lat == null || lon == null) return null
        return IpGeo(lat, lon, str("city", "cityName", "name"))
    }

    private suspend fun fetch(slug: String): List<GtfsRealtime.Raw> {
        // Pass the already-encoded URL string so the %2F survives to the server.
        val bytes: ByteArray = client().get(MtaFeeds.url(slug)) {
            header("Accept", "application/x-protobuf")
        }.body()
        return GtfsRealtime.parse(bytes)
    }

    fun close() {
        client?.close()
        client = null
    }
}
