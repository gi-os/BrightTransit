package com.thelightphone.subway

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.thelightphone.subway.data.ArrivalsRepository
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The parts every screen shares: the station catalog, the feed cache and what is saved.
 * Version 1 built a catalog reader, a store and an HTTP client per screen.
 */
object Subway {
    val arrivals = ArrivalsRepository()

    @Volatile
    private var catalog: Catalog? = null

    @Volatile
    private var saved: SavedStore? = null

    /** Parses the 445 stations once, off the main thread, and keeps them. */
    suspend fun catalog(readAsset: (String) -> ByteArray): Catalog =
        catalog ?: withContext(Dispatchers.Default) {
            synchronized(this@Subway) {
                catalog ?: Catalog.parse(String(readAsset("stations.json"), Charsets.UTF_8)).also { catalog = it }
            }
        }

    fun saved(dataStore: DataStore<Preferences>): SavedStore =
        saved ?: synchronized(this) { saved ?: SavedStore(dataStore).also { saved = it } }
}

/**
 * Keeps the boards for a set of stations current while their screen is showing.
 *
 * The times are fetched when the screen appears and again every half minute, so nobody has
 * to sit pressing refresh. It stops as soon as the screen is covered or the phone sleeps.
 * A board that cannot be refreshed keeps the trains it had and is marked stale.
 */
class LiveBoards(
    private val scope: CoroutineScope,
    private val catalog: suspend () -> Catalog,
) {
    private val _boards = MutableStateFlow<List<Board>>(emptyList())
    val boards: StateFlow<List<Board>> = _boards

    /** True while a refresh the person asked for (the button) is in flight. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    @Volatile
    private var stations: List<Station> = emptyList()
    private var visible = false
    private var loop: Job? = null

    /** Which stations to follow. Boards already loaded for the same stations are kept. */
    fun follow(stations: List<Station>) {
        this.stations = stations
        val old = _boards.value.associateBy { it.station.id }
        _boards.value = stations.map { old[it.id] ?: Board(it, loading = true) }
        if (visible) restart(force = false)
    }

    fun resume() {
        visible = true
        restart(force = false)
    }

    fun pause() {
        visible = false
        loop?.cancel()
        _refreshing.value = false
    }

    /** The refresh button: fetch now, even if the copy in hand is seconds old. */
    fun refreshNow() {
        if (visible) restart(force = true)
    }

    private fun restart(force: Boolean) {
        loop?.cancel()
        loop = scope.launch(Dispatchers.Default) {
            var forced = force
            while (isActive) {
                if (forced) {
                    _refreshing.value = true
                    val started = System.currentTimeMillis()
                    load(force = true)
                    // Long enough to be seen even when the MTA answers at once.
                    delay((MIN_VISIBLE_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                    _refreshing.value = false
                } else {
                    load(force = false)
                }
                forced = false
                delay(EVERY_MS)
            }
        }
    }

    private suspend fun load(force: Boolean) {
        val following = stations
        if (following.isEmpty()) return
        val cat = catalog()
        coroutineScope {
            following.forEach { station ->
                launch { merge(Subway.arrivals.board(station, cat, force)) }
            }
        }
    }

    private fun merge(fresh: Board) {
        _boards.update { list ->
            list.map { old ->
                when {
                    old.station.id != fresh.station.id -> old
                    fresh.failed && old.arrivals.isNotEmpty() -> old.copy(stale = true, loading = false)
                    else -> fresh
                }
            }
        }
    }

    private companion object {
        const val EVERY_MS = 30_000L
        const val MIN_VISIBLE_MS = 400L
    }
}
