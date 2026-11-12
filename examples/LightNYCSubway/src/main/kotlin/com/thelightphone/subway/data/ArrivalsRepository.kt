package com.thelightphone.subway.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads the MTA feeds and remembers them for a short while.
 *
 * There is one of these for the whole tool. The home screen, a station page and the nearby
 * list often want the same feed within seconds of each other; each feed is fetched once and
 * shared, and two screens asking at the same moment wait on the same download.
 *
 * When a download fails the last good copy is handed back marked stale, so a tunnel or a
 * dead zone leaves the board on screen instead of blanking it.
 */
class ArrivalsRepository(
    private val download: suspend (String) -> ByteArray = { httpGet(it) },
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class Cached(val feed: GtfsRealtime.Feed, val fetchedAt: Long)

    private class Slot {
        val lock = Mutex()
        @Volatile var cached: Cached? = null
    }

    private class Fetched(val feed: GtfsRealtime.Feed?, val stale: Boolean)

    private val slots = ConcurrentHashMap<String, Slot>()

    /**
     * The trains due at [station]. With [force] the feeds are downloaded again even if the
     * copy in hand is only seconds old (the refresh button).
     */
    suspend fun board(station: Station, catalog: Catalog, force: Boolean = false): Board = coroutineScope {
        val results = Feeds.slugsFor(station).map { slug -> async { fetch(slug, force) } }.awaitAll()
        val feeds = results.mapNotNull { it.feed }
        if (feeds.isEmpty()) return@coroutineScope Board(station, failed = true)
        val now = clock()
        Board(
            station = station,
            arrivals = Directions.arrivals(station, catalog, feeds, now),
            asOf = feeds.minOf { it.timestamp },
            // The MTA rebuilds a feed every few seconds. Two minutes without one means it is
            // not live any more, whatever the download said.
            stale = results.any { it.stale } || feeds.any { now - it.timestamp > STALE_AFTER },
        )
    }

    private suspend fun fetch(slug: String, force: Boolean): Fetched {
        val slot = slots.getOrPut(slug) { Slot() }
        slot.cached?.let { if (!force && clock() - it.fetchedAt < FRESH_FOR) return Fetched(it.feed, false) }
        return slot.lock.withLock {
            // Someone else may have finished the same download while this call waited.
            slot.cached?.let { if (clock() - it.fetchedAt < (if (force) 2 else FRESH_FOR)) return Fetched(it.feed, false) }
            val fresh = runCatching { GtfsRealtime.parse(download(Feeds.url(slug))) }.getOrNull()
            if (fresh != null) {
                slot.cached = Cached(fresh, clock())
                Fetched(fresh, false)
            } else {
                val old = slot.cached?.takeIf { clock() - it.fetchedAt < KEEP_FOR }
                Fetched(old?.feed, stale = old != null)
            }
        }
    }

    companion object {
        /** A copy this young is used as is. */
        const val FRESH_FOR = 20L

        /** Past this the times are too old to show, even as a fallback. */
        const val KEEP_FOR = 20 * 60L

        const val STALE_AFTER = 120L

        private val client by lazy {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 8_000
                    requestTimeoutMillis = 15_000
                    socketTimeoutMillis = 10_000
                }
            }
        }

        private suspend fun httpGet(url: String): ByteArray {
            // The already-encoded string is passed through so the %2F reaches the server.
            val response: HttpResponse = client.get(url) { header("Accept", "application/x-protobuf") }
            if (!response.status.isSuccess()) throw java.io.IOException("HTTP ${response.status.value}")
            return response.body()
        }
    }
}
