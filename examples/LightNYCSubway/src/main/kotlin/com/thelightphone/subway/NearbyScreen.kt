package com.thelightphone.subway

import android.Manifest
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.StationBoard
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.clockLabel
import com.thelightphone.subway.ui.distanceLabel
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.subway.ui.searchButton
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOCATION = Manifest.permission.ACCESS_FINE_LOCATION

/**
 * What the tool knows about where the rider is, which decides what the list may claim.
 * Only [Here] is somewhere the rider can be said to be standing.
 */
sealed interface Whereabouts {
    /** Somewhere to measure the list from. */
    sealed interface Place : Whereabouts {
        val lat: Double
        val lon: Double
    }

    /** A fix new and close enough that a distance from it is a distance from the rider. */
    data class Here(override val lat: Double, override val lon: Double) : Place

    /**
     * Where the phone was at [atSeconds]: a fix too old to say where the rider is now.
     * With [close] it was a tight one, and a distance from it is the walk from where the
     * phone then was. Without, it was good only to within some blocks even then.
     */
    data class Earlier(override val lat: Double, override val lon: Double, val atSeconds: Long, val close: Boolean) : Place

    /** A new fix that is good only to within some blocks, or does not say how good it is. */
    data class Roughly(override val lat: Double, override val lon: Double) : Place

    /** No fix worth using. This is the default location set on the Light dashboard. */
    data class DefaultLocation(override val lat: Double, override val lon: Double) : Place

    data object Unknown : Whereabouts

    companion object {
        /**
         * How old a fix can be and still be where the rider is. LightOS goes on listening
         * for about a minute after a tool last asked, so a fix this new was taken on this
         * visit or just before it. Two minutes on foot is about 500 feet, and 97 stations
         * in 100 have no other station that close.
         */
        const val FRESH_FOR_MS = 2 * 60_000L

        /**
         * Past this a fix is not used at all. Within the hour a rider knows where they
         * were at the time given and can judge the list. Where the phone was this morning
         * says nothing about what is near now.
         */
        const val USE_FOR_MS = 60 * 60_000L

        /**
         * The widest a fix may be and still be trusted to pick the right station. The
         * typical station is 540 meters from its nearest neighbour, and 97 in 100 are more
         * than 200 from one. GPS and Wi-Fi fixes come in well under that. A fix from cell
         * towers alone is several times wider.
         */
        const val CLOSE_ENOUGH_METERS = 200.0

        /**
         * What LightOS's answers amount to. [fix] is its latest cached position, which can
         * be hours old or miles wide; [home] is the default location, asked for only when
         * there is no fix to go on.
         */
        fun from(
            fix: LightServiceMethod.GetCurrentLocation.Response?,
            home: LightServiceMethod.GetDefaultLocation.Response?,
            nowMs: Long,
        ): Whereabouts {
            val lat = fix?.latitude
            val lon = fix?.longitude
            // A fix that does not say when it was taken could be from last week, and is not used.
            val taken = fix?.timestampMs
            if (lat != null && lon != null && taken != null) {
                // One dated in the future means the clock was set back, and counts as new.
                val age = (nowMs - taken).coerceAtLeast(0)
                val accuracy = fix.accuracyMeters
                val close = accuracy != null && accuracy <= CLOSE_ENOUGH_METERS
                when {
                    age > USE_FOR_MS -> Unit
                    // Old outranks wide: a wide fix is near the rider, an old one may not be.
                    // A fix that is both stays wide, and does not turn exact by getting older.
                    age > FRESH_FOR_MS -> return Earlier(lat, lon, taken / 1000, close)
                    !close -> return Roughly(lat, lon)
                    else -> return Here(lat, lon)
                }
            }
            val homeLat = home?.latitude
            val homeLon = home?.longitude
            return if (homeLat != null && homeLon != null) DefaultLocation(homeLat, homeLon) else Unknown
        }
    }
}

/**
 * The line above the list when it is not measured from where the rider stands, so the list
 * never passes for more than it is. Null for a good fix, which needs no remark.
 */
fun basisNote(where: Whereabouts): String? = when (where) {
    is Whereabouts.Earlier -> {
        val taken = clockLabel(where.atSeconds)
        if (where.close) "Location from $taken." else "Approximate location from $taken."
    }
    is Whereabouts.Roughly -> "Approximate location."
    is Whereabouts.DefaultLocation -> "Using your default location."
    is Whereabouts.Here, Whereabouts.Unknown -> null
}

/**
 * The LightOS calls behind this screen. The real ones go through the SDK; a test answers
 * for the phone.
 */
interface PhoneLocation {
    suspend fun allowed(): Boolean

    /** LightOS only looks for the phone while some tool is asking it to. */
    suspend fun startListening()

    /** The latest position LightOS holds, however old. */
    suspend fun latest(): LightServiceMethod.GetCurrentLocation.Response?

    suspend fun stopListening()

    suspend fun default(): LightServiceMethod.GetDefaultLocation.Response?
}

private object LightOsLocation : PhoneLocation {
    override suspend fun allowed(): Boolean = checkPermission(LOCATION).asKotlinResult
        .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        .getOrDefault(false)

    override suspend fun startListening() {
        callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
    }

    override suspend fun latest() =
        callRemoteServiceMethod(LightServiceMethod.GetCurrentLocation, Unit).getOrNull()

    override suspend fun stopListening() {
        callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
    }

    override suspend fun default() =
        callRemoteServiceMethod(LightServiceMethod.GetDefaultLocation, Unit).getOrNull()
}

enum class Locating { SEARCHING, NEEDS_PERMISSION, NOT_FOUND, FOUND }

/**
 * A station on the list. [meters] is the walk from a spot the phone is known to have been,
 * now or at the time the list gives. From the default location, or from a fix only good to
 * within some blocks, there is no such walk to give a length for, and it is null.
 */
data class Near(val station: Station, val meters: Double?)

data class NearbyState(
    val status: Locating = Locating.SEARCHING,
    val stations: List<Near> = emptyList(),
    /** What [stations] were measured from. */
    val basis: Whereabouts = Whereabouts.Unknown,
)

/**
 * Finds the rider and the stations around them, and holds what the Nearby screen shows.
 *
 * The clock and the wait between tries are passed in so the whole search can be run in a
 * test without a phone and without waiting.
 */
class NearbySearch(
    private val catalog: suspend () -> Catalog,
    private val phone: PhoneLocation = LightOsLocation,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state

    /**
     * One search at a time. A search that was cut short has then told LightOS to stop
     * listening before the next one tells it to start.
     */
    private val turn = Mutex()

    /**
     * Looks for the rider and lists the stations nearest to where they are. Returns the
     * closest one, whose trains the screen shows, or null when there is no list.
     */
    suspend fun look(): Station? = turn.withLock {
        if (!phone.allowed()) {
            _state.update { it.copy(status = Locating.NEEDS_PERMISSION) }
            return@withLock null
        }
        // A list already on screen stays there while a new fix is looked for.
        _state.update { if (it.status == Locating.FOUND) it else it.copy(status = Locating.SEARCHING) }

        val where = locate()
        if (where !is Whereabouts.Place) {
            _state.update { it.copy(status = Locating.NOT_FOUND) }
            return@withLock null
        }
        val measured = where is Whereabouts.Here || (where is Whereabouts.Earlier && where.close)
        val nearest = catalog().nearest(where.lat, where.lon, limit = LISTED)
        _state.value = NearbyState(
            status = Locating.FOUND,
            stations = nearest.map { (station, meters) -> Near(station, meters.takeIf { measured }) },
            basis = where,
        )
        nearest.firstOrNull()?.first
    }

    /**
     * Asks LightOS where the phone is. LightOS answers at once with the last position it
     * has, so this keeps LightOS listening and asks again each second, up to [TRIES]
     * times, until the answer is new and close enough to be where the rider is. Failing
     * that it settles for what it was given, and with nothing at all, for the default
     * location.
     *
     * LightOS is always told to stop listening, also when the screen goes away mid-search.
     */
    suspend fun locate(): Whereabouts {
        var last: LightServiceMethod.GetCurrentLocation.Response? = null
        try {
            phone.startListening()
            repeat(TRIES) { attempt ->
                // An answer with no position in it does not wipe out an earlier one that had.
                phone.latest()?.takeIf { it.latitude != null && it.longitude != null }?.let { last = it }
                val soFar = Whereabouts.from(last, home = null, nowMs = clock())
                if (soFar is Whereabouts.Here) return soFar
                if (attempt < TRIES - 1) pause(BETWEEN_TRIES_MS)
            }
        } finally {
            withContext(NonCancellable) { phone.stopListening() }
        }
        val settled = Whereabouts.from(last, home = null, nowMs = clock())
        return if (settled is Whereabouts.Place) settled else Whereabouts.from(null, phone.default(), clock())
    }

    companion object {
        const val TRIES = 8
        const val BETWEEN_TRIES_MS = 1_000L

        /** How many stations the list holds. */
        const val LISTED = 8
    }
}

class NearbyViewModel(
    saved: SavedStore,
    catalog: suspend () -> Catalog,
) : LightViewModel<Unit>() {

    /** Live times for the closest station only; the rest are a tap away. */
    val live = LiveBoards(viewModelScope, catalog)

    private val search = NearbySearch(catalog)
    val state: StateFlow<NearbyState> get() = search.state

    val favorites: StateFlow<Set<String>> =
        saved.favoriteRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var looking: Job? = null
    private var prompted = false

    /** Looks for the rider again: each time the screen comes back, and on refresh. */
    private fun look() {
        looking?.cancel()
        looking = viewModelScope.launch(Dispatchers.Default) {
            search.look()?.let { closest -> live.follow(listOf(closest)) }
        }
    }

    /** The refresh button: where the rider is now, and the trains there now. */
    fun refresh() {
        look()
        live.refreshNow()
    }

    /** True once per visit: the permission prompt is shown unasked the first time, never in a loop. */
    fun promptOnce(): Boolean {
        if (prompted) return false
        prompted = true
        return true
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        live.resume()
        // Also where the rider lands after the permission prompt, so its answer is read here.
        look()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        live.pause()
        looking?.cancel()
    }

    override fun onAppPause() {
        super.onAppPause()
        live.pause()
        looking?.cancel()
    }
}

/**
 * The stations closest to where you are standing. The nearest one shows its trains right
 * here, because if you opened this on a platform that is the answer you came for.
 */
class NearbyScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, NearbyViewModel>(sealedActivity) {

    override val viewModelClass: Class<NearbyViewModel> get() = NearbyViewModel::class.java

    override fun createViewModel(): NearbyViewModel {
        val readAsset = lightContext::readAsset
        return NearbyViewModel(Subway.saved(lightContext.dataStore)) { Subway.catalog(readAsset) }
    }

    private val wheel = WheelBus()

    /**
     * Started afresh when the list is the one around another station, so it opens at its
     * top, where the nearest station's trains are, and not where the last list was left.
     */
    private var page = Page()

    /**
     * The nearest station of the list [page] is scrolled over. Held here and not in what is
     * drawn, so coming back from a station page to the same list is not taken for a new one.
     */
    private var scrolledOver: String? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val boards by viewModel.live.boards.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val snapshot by viewModel.live.alerts.collectAsState()
        val now by viewModel.live.now.collectAsState()
        val permission = rememberPermissionRequestLauncher(LOCATION)

        LaunchedEffect(state.status) {
            if (state.status == Locating.NEEDS_PERMISSION && viewModel.promptOnce()) {
                runCatching { permission?.launch() }
            }
        }

        val open: (Station) -> Unit = { station -> navigateTo({ StationScreen(it, station.id) }) }

        // Settled as the list is read, so the page this pass draws on is already the new one.
        val nearest = state.stations.firstOrNull()?.station?.id
        if (startsOver(scrolledOver, nearest)) page = Page()
        if (nearest != null) scrolledOver = nearest

        SubwayScreen(
            title = "Nearby",
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = if (state.status == Locating.NEEDS_PERMISSION) {
                listOf(LightBarButton.Text(text = "ALLOW LOCATION", onClick = { runCatching { permission?.launch() } }))
            } else {
                listOf(null, searchButton { navigateTo(::SearchScreen) }, refreshButton { viewModel.refresh() })
            },
            message = when (state.status) {
                Locating.SEARCHING -> "Finding stations near you…"
                Locating.NEEDS_PERMISSION -> "Allow location to see the stations closest to you.\n\nYour location stays on the phone."
                Locating.NOT_FOUND -> "Can’t find your location right now.\n\nTry again outside, or search for a station."
                Locating.FOUND -> null
            },
        ) {
            basisNote(state.basis)?.let { Note(it, Modifier.padding(bottom = 0.5f.gridUnitsAsDp())) }
            state.stations.forEachIndexed { index, (station, meters) ->
                val board = boards.firstOrNull { it.station.id == station.id }
                key(station.id) {
                    FadeIn {
                        if (index == 0 && board != null) {
                            // As on the home screen, station notices wait for the station page.
                            val alerts = remember(snapshot, station.id, now) {
                                snapshot.at(station, now).filterNot { it.notice }
                            }
                            StationBoard(
                                board = board,
                                nowSeconds = now,
                                favorites = favorites,
                                onOpen = { open(station) },
                                trailing = distanceLabel(meters),
                                alerts = alerts,
                            )
                        } else {
                            NearbyRow(station, meters, favorites) { open(station) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether Nearby's page has to start over at its top: it was scrolled over the list around
 * [scrolledOver], and the list is now the one around [nearest]. The first list a page is
 * given, the same list again and no list at all leave it where it is.
 */
internal fun startsOver(scrolledOver: String?, nearest: String?): Boolean =
    scrolledOver != null && nearest != null && nearest != scrolledOver

/** The distance as the list words it, and nothing where there is no distance to give. See [Near.meters]. */
internal fun distanceLabel(meters: Double?): String? = meters?.let { distanceLabel(it) }

@Composable
private fun NearbyRow(station: Station, meters: Double?, favorites: Set<String>, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            .padding(bottom = 0.75f.gridUnitsAsDp()),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LightText(
                text = station.name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            distanceLabel(meters)?.let { Note(it, Modifier.padding(start = 0.5f.gridUnitsAsDp())) }
        }
        RouteBadgeRow(
            routes = station.routes,
            favorites = favorites,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}
