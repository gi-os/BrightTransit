package com.thelightphone.subway.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Downloads the MTA feeds and remembers them for a short while.
 *
 * There is one of these for the whole tool. The home screen, a station page and the nearby
 * list often want the same feed within seconds of each other; each feed is fetched once and
 * shared, and everyone asking while a download is in flight waits on that one download and
 * gets its answer, good or bad. On a weak signal that is one timeout, not one per station.
 *
 * A download runs in this repository's own scope and not in the caller's. Leaving a screen
 * cancels everything the screen started, and a feed thrown away half-read on every move
 * from Home to a station might never arrive on a slow connection. The screen stops waiting;
 * the download finishes and is there for whoever asks next.
 *
 * When a download fails the last good copy is handed back marked stale, so a tunnel or a
 * dead zone leaves the board on screen instead of blanking it.
 */
class ArrivalsRepository(
    private val download: suspend (String) -> ByteArray = { MtaHttp.get(it) },
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Copy(val feed: GtfsRealtime.Feed, val fetchedAt: Long)

    private class Slot {
        var copy: Copy? = null
        var running: Deferred<GtfsRealtime.Feed?>? = null
        var startedAt = 0L

        /** True when the last download to finish brought no feed, so [copy] is from before it. */
        var failed = false
    }

    /** One feed as a board gets it. [old] marks a copy kept from before a failed download. */
    private class Fetched(val slug: String, val feed: GtfsRealtime.Feed?, val old: Boolean = false) {
        val source get() = Board.Source(slug, feed?.timestamp, old)
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val slots = HashMap<String, Slot>()

    /**
     * The trains due at [station]. With [force] the feeds are downloaded again even if the
     * copy in hand is only seconds old (the refresh button).
     *
     * Safe to call from the main thread: the waiting suspends, and the board is put
     * together on this repository's own threads.
     */
    suspend fun board(station: Station, catalog: Catalog, force: Boolean = false): Board = withContext(dispatcher) {
        val fetched = Feeds.slugsFor(station).map { slug -> async { fetch(slug, force) } }.awaitAll()
        assemble(station, catalog, fetched, clock())
    }

    /**
     * The board as the copies in hand have it, without touching the network, or null when
     * a feed the station needs is not held. A copy of any age up to [KEEP_FOR] will do: this
     * is what lets a station opened from Home show its times at once instead of "Loading".
     * How old they are is for [Board.view] to say, as for any other board. A copy from
     * before a failed download is marked as one, as it was for whoever saw the download fail.
     */
    fun peek(station: Station, catalog: Catalog): Board? {
        val now = clock()
        val copies = synchronized(lock) {
            Feeds.slugsFor(station).map { slug ->
                Fetched(slug, held(slug, now) ?: return null, old = slots[slug]?.failed == true)
            }
        }
        return assemble(station, catalog, copies, now).takeUnless { it.failed }
    }

    /**
     * One feed by itself, for following a single train. It is the same download and the
     * same copy a board gets, so a train followed while a board is up on the same line
     * costs nothing more. When the download fails the copy from before it is handed back,
     * and null when there is none: the feed's own timestamp says which it was.
     */
    suspend fun feed(slug: String, force: Boolean = false): GtfsRealtime.Feed? =
        withContext(dispatcher) { fetch(slug, force).feed }

    /** The copy of [slug] in hand, without touching the network, or null. */
    fun peekFeed(slug: String): GtfsRealtime.Feed? = synchronized(lock) { held(slug, clock()) }

    /**
     * How many seconds ago the copy of [slug] in hand was downloaded, or null when there
     * is none. A screen that was answered from a copy made for another goes by this to ask
     * again when that copy is half a minute old, not half a minute after it looked.
     */
    fun age(slug: String): Long? = synchronized(lock) { slots[slug]?.copy?.let { clock() - it.fetchedAt } }

    /**
     * The trains that run direct from [from] to [to]. The feeds of both stations are
     * fetched together, as for a board and with the same sharing, so a plan asked for
     * beside a board costs no download the board was not making.
     *
     * A feed whose download failed is left out even where an older copy is held: a board
     * can show old times marked as old, but a way there is not worked out from them. The
     * plan knows which feeds it was made from, and names the lines it could not check.
     */
    suspend fun plan(from: Station, to: Station, catalog: Catalog, force: Boolean = false): Plan = withContext(dispatcher) {
        val fetched = Planner.slugs(from, to).map { slug -> async { fetch(slug, force) } }.awaitAll()
        Planner.plan(from, to, catalog, current(fetched), clock())
    }

    /**
     * The plan as the copies in hand have it, without touching the network, or null when
     * they leave a line between the two stations unchecked. That is nothing to say yet:
     * the feed has not been asked for.
     */
    fun peekPlan(from: Station, to: Station, catalog: Catalog): Plan? {
        val now = clock()
        val copies = synchronized(lock) {
            Planner.slugs(from, to).map { slug -> Fetched(slug, held(slug, now), old = slots[slug]?.failed == true) }
        }
        return Planner.plan(from, to, catalog, current(copies), now).takeIf { it.unchecked(now).isEmpty() }
    }

    /** The feeds among [fetched] that are the answer to their last download, by name. */
    private fun current(fetched: List<Fetched>): Map<String, GtfsRealtime.Feed> =
        fetched.mapNotNull { got -> got.feed?.takeUnless { got.old }?.let { got.slug to it } }.toMap()

    private fun assemble(station: Station, catalog: Catalog, fetched: List<Fetched>, now: Long): Board {
        // A feed too old to show is not read at all, so its trains have no say in what the
        // others are headed for.
        val feeds = fetched.mapNotNull { got -> got.feed?.takeIf { got.source.had(now) }?.let { got.slug to it } }.toMap()
        return Board.of(station, Directions.arrivals(station, catalog, feeds, now), fetched.map { it.source }, now)
    }

    private suspend fun fetch(slug: String, force: Boolean): Fetched {
        val running = synchronized(lock) {
            val slot = slots.getOrPut(slug) { Slot() }
            val now = clock()
            // An age below zero means the clock was set back. Such a copy is never taken for
            // new, or every download would be put off for as long as the clock went back.
            val young = if (force) JUST_NOW else FRESH_FOR
            slot.copy?.let { if (now - it.fetchedAt in 0 until young) return Fetched(slug, it.feed, old = slot.failed) }
            val inFlight = slot.running?.takeIf { it.isActive }
            if (inFlight != null && now - slot.startedAt in 0..STUCK_AFTER) {
                inFlight
            } else {
                inFlight?.cancel()
                slot.startedAt = now
                start(slug, slot).also { slot.running = it }
            }
        }
        val fresh = try {
            running.await()
        } catch (e: CancellationException) {
            // This caller going away is passed on. Otherwise the download was given up on
            // and replaced under it, which is one more way of not getting the feed.
            currentCoroutineContext().ensureActive()
            null
        }
        if (fresh != null) return Fetched(slug, fresh)
        val kept = synchronized(lock) { held(slug, clock()) }
        return Fetched(slug, kept, old = kept != null)
    }

    private fun start(slug: String, slot: Slot): Deferred<GtfsRealtime.Feed?> = scope.async {
        val feed = try {
            GtfsRealtime.parse(download(Feeds.url(slug)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        synchronized(lock) {
            // A download that was replaced while it ran has no say in what is kept.
            if (isActive) {
                if (feed != null) slot.copy = Copy(feed, clock())
                slot.failed = feed == null
            }
        }
        feed
    }

    /** The copy of [slug] in hand, unless it is too old to show. Call with the lock held. */
    private fun held(slug: String, now: Long): GtfsRealtime.Feed? =
        slots[slug]?.copy?.takeIf { now - it.fetchedAt < KEEP_FOR }?.feed

    companion object {
        /** A copy this young is used as is. */
        const val FRESH_FOR = 20L

        /** A copy this young answers even the refresh button: it was fetched for the same press. */
        private const val JUST_NOW = 2L

        /** Past this the times are too old to show, even as a fallback. */
        const val KEEP_FOR = 20 * 60L

        /** Times the MTA built longer ago than this are shown, but marked as not live. */
        const val STALE_AFTER = 120L

        /**
         * A download still running after this long is given up on and replaced. A request
         * times out in fifteen seconds, so an older one was in flight when the phone slept.
         */
        const val STUCK_AFTER = 20L
    }
}

/** The one HTTP client behind every request to the MTA, shared by the trains and the alerts. */
internal object MtaHttp {
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 10_000
            }
        }
    }

    /**
     * [requestTimeoutMs] gives one request longer than the usual fifteen seconds.
     *
     * Accept-Encoding and Range are never set here. OkHttp asks for gzip and unpacks it on
     * its own, but only while neither header is set by hand, and the alerts feed is six
     * times the size without it.
     */
    suspend fun get(url: String, requestTimeoutMs: Long? = null): ByteArray {
        // The already-encoded string is passed through so the %2F reaches the server.
        val response: HttpResponse = client.get(url) {
            header("Accept", "application/x-protobuf")
            if (requestTimeoutMs != null) timeout { requestTimeoutMillis = requestTimeoutMs }
        }
        if (!response.status.isSuccess()) throw java.io.IOException("HTTP ${response.status.value}")
        return response.body()
    }
}
