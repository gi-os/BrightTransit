package com.thelightphone.subway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Approximate location from IP geolocation (GPS APIs are blocked by the SDK). */
@Serializable
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
     * Approximate device location via IP geolocation (ipapi.co). Returns null on
     * failure. Not GPS-accurate — neighborhood level — but the SDK forbids the
     * Android location APIs, so this is the only automatic option.
     */
    suspend fun ipLocation(): IpGeo? = runCatching {
        val body: String = client().get("https://ipapi.co/json/") {
            header("Accept", "application/json")
        }.body()
        Json { ignoreUnknownKeys = true }.decodeFromString<IpGeo>(body)
    }.getOrNull()?.takeIf { it.latitude != null && it.longitude != null }

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
