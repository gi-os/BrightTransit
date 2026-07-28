package com.thelightphone.subway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.URLProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fetches and decodes MTA realtime feeds, returning the upcoming arrivals for a
 * single station. Holds one [HttpClient]; remember to [close].
 */
class ArrivalsRepository {

    private val client = HttpClient(OkHttp)

    /**
     * Query every feed that serves [station], decode it, and keep only the trains
     * stopping at this station in the future, soonest first.
     */
    suspend fun arrivalsFor(station: Station, nowSeconds: Long): List<Arrival> = coroutineScope {
        val slugs = MtaFeeds.slugsFor(station)
        val raws = slugs.map { slug ->
            async { runCatching { fetch(slug) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()

        raws.mapNotNull { raw ->
            val dir = when {
                raw.stopId == station.id + "N" -> Direction.NORTH
                raw.stopId == station.id + "S" -> Direction.SOUTH
                else -> return@mapNotNull null
            }
            if (raw.time < nowSeconds - 30) return@mapNotNull null // already left
            Arrival(route = raw.route, direction = dir, epochSeconds = raw.time)
        }
            .distinctBy { Triple(it.route, it.direction, it.epochSeconds) }
            .sortedBy { it.epochSeconds }
    }

    private suspend fun fetch(slug: String): List<GtfsRealtime.Raw> {
        val bytes: ByteArray = client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = MtaFeeds.HOST
                // Pre-encoded path; the %2F between "nyct" and the slug is required.
                encodedPath = MtaFeeds.encodedPath(slug)
            }
            header("Accept", "application/x-protobuf")
        }.body()
        return GtfsRealtime.parse(bytes)
    }

    fun close() = client.close()
}
