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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.StationBoard
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.distanceLabel
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.subway.ui.rememberNow
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOCATION = Manifest.permission.ACCESS_FINE_LOCATION

enum class Locating { SEARCHING, NEEDS_PERMISSION, NOT_FOUND, FOUND }

data class NearbyState(
    val status: Locating = Locating.SEARCHING,
    val stations: List<Pair<Station, Double>> = emptyList(),
)

class NearbyViewModel(
    saved: SavedStore,
    private val catalog: suspend () -> Catalog,
) : LightViewModel<Unit>() {

    /** Live times for the closest station only; the rest are a tap away. */
    val live = LiveBoards(viewModelScope, catalog)

    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state

    val favorites: StateFlow<Set<String>> =
        saved.favoriteRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** The permission prompt is shown unasked once per visit, never in a loop. */
    var askedForPermission = false

    fun status(status: Locating) {
        // A list already on screen stays there while a new fix is looked for.
        if (status == Locating.SEARCHING && _state.value.status == Locating.FOUND) return
        _state.value = _state.value.copy(status = status)
    }

    fun located(lat: Double, lon: Double) {
        viewModelScope.launch(Dispatchers.Default) {
            val nearest = catalog().nearest(lat, lon, limit = 8)
            _state.value = NearbyState(Locating.FOUND, nearest)
            live.follow(nearest.take(1).map { it.first })
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        live.resume()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        live.pause()
    }

    override fun onAppPause() {
        super.onAppPause()
        live.pause()
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

    /** Goes up each time the screen comes back (also after the permission prompt) and on refresh. */
    private var attempt by mutableIntStateOf(0)

    override fun willShow() {
        attempt++
    }

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
        val now by rememberNow()
        val permission = rememberPermissionRequestLauncher(LOCATION)

        LaunchedEffect(attempt) {
            viewModel.status(Locating.SEARCHING)
            val granted = checkPermission(LOCATION).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            if (!granted) {
                viewModel.status(Locating.NEEDS_PERMISSION)
                if (!viewModel.askedForPermission) {
                    viewModel.askedForPermission = true
                    runCatching { permission?.launch() }
                }
                return@LaunchedEffect
            }
            val fix = lightOsLocation()
            if (fix == null) viewModel.status(Locating.NOT_FOUND) else viewModel.located(fix.first, fix.second)
        }

        val open: (Station) -> Unit = { station -> navigateTo({ StationScreen(it, station.id) }) }

        SubwayScreen(
            title = "Nearby",
            wheel = wheel,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = if (state.status == Locating.NEEDS_PERMISSION) {
                listOf(LightBarButton.Text(text = "ALLOW LOCATION", onClick = { runCatching { permission?.launch() } }))
            } else {
                listOf(null, searchButton { navigateTo(::SearchScreen) }, refreshButton { attempt++; viewModel.live.refreshNow() })
            },
            message = when (state.status) {
                Locating.SEARCHING -> "Finding stations near you…"
                Locating.NEEDS_PERMISSION -> "Allow location to see the stations closest to you.\n\nYour location stays on the phone."
                Locating.NOT_FOUND -> "Can’t find your location right now.\n\nTry again outside, or search for a station."
                Locating.FOUND -> null
            },
        ) {
            state.stations.forEachIndexed { index, (station, meters) ->
                val board = boards.firstOrNull { it.station.id == station.id }
                key(station.id) {
                    FadeIn(order = index) {
                        if (index == 0 && board != null) {
                            StationBoard(board, now, favorites, onOpen = { open(station) }, trailing = distanceLabel(meters))
                        } else {
                            NearbyRow(station, meters, favorites) { open(station) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(station: Station, meters: Double, favorites: Set<String>, onOpen: () -> Unit) {
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
            Note(distanceLabel(meters), Modifier.padding(start = 8.dp))
        }
        RouteBadgeRow(
            routes = station.routes,
            favorites = favorites,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}

/**
 * Asks LightOS where the phone is. Holds a location lease for up to eight seconds while it
 * waits for a fix, then gives the lease back. Without a fix it uses the default location
 * set on the Light dashboard. Null when neither is known.
 */
private suspend fun lightOsLocation(): Pair<Double, Double>? {
    try {
        callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
        repeat(8) {
            val fix = callRemoteServiceMethod(LightServiceMethod.GetCurrentLocation, Unit).getOrNull()
            val lat = fix?.latitude
            val lon = fix?.longitude
            if (lat != null && lon != null) return lat to lon
            delay(1_000)
        }
    } finally {
        withContext(NonCancellable) {
            callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
        }
    }
    val home = callRemoteServiceMethod(LightServiceMethod.GetDefaultLocation, Unit).getOrNull()
    val lat = home?.latitude
    val lon = home?.longitude
    return if (lat != null && lon != null) lat to lon else null
}
