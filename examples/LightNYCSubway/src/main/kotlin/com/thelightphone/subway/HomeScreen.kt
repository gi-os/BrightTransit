package com.thelightphone.subway

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val LOCATION_PERMISSION = "android.permission.ACCESS_COARSE_LOCATION"

data class StationBoard(
    val station: Station,
    val north: List<Arrival> = emptyList(),
    val south: List<Arrival> = emptyList(),
    val failed: Boolean = false,
)

data class HomeUiState(
    val loading: Boolean = true,
    val boards: List<StationBoard> = emptyList(),
    val favoriteRoutes: Set<String> = emptySet(),
    val nowSeconds: Long = System.currentTimeMillis() / 1000,
)

enum class NearbyStatus { IDLE, LOADING, NO_PERMISSION, UNAVAILABLE, LOADED }

data class NearbyItem(val station: Station, val meters: Double)

data class NearbyUiState(
    val status: NearbyStatus = NearbyStatus.IDLE,
    val items: List<NearbyItem> = emptyList(),
    val favoriteRoutes: Set<String> = emptySet(),
)

class HomeViewModel(
    private val dataStore: DataStore<Preferences>,
    private val readAsset: (String) -> ByteArray,
) : LightViewModel<Unit>() {

    private val repo = ArrivalsRepository()

    @Volatile
    private var storeCache: StationStore? = null

    private suspend fun store(): StationStore =
        storeCache ?: StationStore(dataStore, StationCatalog.load(readAsset)).also { storeCache = it }

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private val _nearby = MutableStateFlow(NearbyUiState())
    val nearby: StateFlow<NearbyUiState> = _nearby

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.Default) {
            val store = store()
            val favs = runCatching { store.favoriteRoutes() }.getOrDefault(emptySet())
            val starred = runCatching { store.starredStations() }.getOrDefault(emptyList())
            if (starred.isEmpty()) {
                _state.value = HomeUiState(loading = false, boards = emptyList(), favoriteRoutes = favs)
                return@launch
            }
            _state.value = _state.value.copy(loading = true, favoriteRoutes = favs)
            val now = System.currentTimeMillis() / 1000
            val boards = starred.map { station ->
                runCatching { repo.arrivalsFor(station, now) }.fold(
                    onSuccess = { arrivals ->
                        StationBoard(
                            station,
                            north = arrivals.filter { it.direction == Direction.NORTH }.take(4),
                            south = arrivals.filter { it.direction == Direction.SOUTH }.take(4),
                        )
                    },
                    onFailure = { StationBoard(station, failed = true) },
                )
            }
            _state.value = HomeUiState(loading = false, boards = boards, favoriteRoutes = favs, nowSeconds = now)
        }
    }

    fun setNearbyStatus(status: NearbyStatus) {
        _nearby.value = _nearby.value.copy(status = status)
    }

    fun loadNearby(lat: Double, lon: Double) {
        viewModelScope.launch(Dispatchers.Default) {
            _nearby.value = _nearby.value.copy(status = NearbyStatus.LOADING)
            val store = store()
            val favs = runCatching { store.favoriteRoutes() }.getOrDefault(emptySet())
            val items = store.nearest(lat, lon).map { NearbyItem(it, it.distanceMetersTo(lat, lon)) }
            _nearby.value = NearbyUiState(NearbyStatus.LOADED, items, favs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repo.close()
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel> get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel {
        CrashReporter.install(lightContext.filesDir)
        return HomeViewModel(lightContext.dataStore, lightContext::readAsset)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val nearby by viewModel.nearby.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val lastCrash = remember { CrashReporter.lastCrash() }
        var showCrash by remember { mutableStateOf(lastCrash != null) }
        var tab by remember { mutableStateOf(HomeTab.STARRED) }

        val context = LocalContext.current
        val locationLauncher = rememberPermissionRequestLauncher(LOCATION_PERMISSION)

        val tryNearby: () -> Unit = tryNearby@{
            if (context.checkSelfPermission(LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                viewModel.setNearbyStatus(NearbyStatus.LOADING)
                fetchLocation(context) { lat, lon ->
                    if (lat != null && lon != null) viewModel.loadNearby(lat, lon)
                    else viewModel.setNearbyStatus(NearbyStatus.UNAVAILABLE)
                }
            } else {
                viewModel.setNearbyStatus(NearbyStatus.NO_PERMISSION)
            }
        }

        LaunchedEffect(tab) {
            if (tab == HomeTab.LOCAL && nearby.status == NearbyStatus.IDLE) tryNearby()
        }

        LightTheme(colors = themeColors) {
            if (showCrash && lastCrash != null) {
                CrashReportView(lastCrash) { CrashReporter.clear(); showCrash = false }
                return@LightTheme
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Subway Times"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = { navigateTo(::SearchScreen) },
                        contentDescription = "Search stations",
                    ),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )

                TabRow(tab) { tab = it }

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    when (tab) {
                        HomeTab.STARRED -> StarredContent(state) { id ->
                            navigateTo({ sealed -> StationScreen(sealed, id) })
                        }
                        HomeTab.LOCAL -> NearbyContent(
                            nearby = nearby,
                            onOpen = { id -> navigateTo({ sealed -> StationScreen(sealed, id) }) },
                            onEnableLocation = { locationLauncher?.launch(); tryNearby() },
                            onRetry = tryNearby,
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "Search",
                            onClick = { navigateTo(::SearchScreen) },
                        ),
                        if (tab == HomeTab.STARRED) {
                            LightBarButton.Text(
                                text = if (state.loading) "Refreshing…" else "Refresh",
                                onClick = { if (!state.loading) viewModel.refresh() },
                            )
                        } else {
                            LightBarButton.Text(
                                text = if (nearby.status == NearbyStatus.LOADING) "Locating…" else "Refresh",
                                onClick = { if (nearby.status != NearbyStatus.LOADING) tryNearby() },
                            )
                        },
                    ),
                )
            }
        }
    }
}

private enum class HomeTab { STARRED, LOCAL }

@Composable
private fun TabRow(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    ) {
        TabLabel("Starred", selected == HomeTab.STARRED) { onSelect(HomeTab.STARRED) }
        Spacer(Modifier.width(24.dp))
        TabLabel("Local", selected == HomeTab.LOCAL) { onSelect(HomeTab.LOCAL) }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = if (selected) LightTextVariant.Heading else LightTextVariant.Copy,
        lighten = !selected,
        modifier = Modifier.lightClickable(onClick = onClick),
    )
}

@Composable
private fun StarredContent(state: HomeUiState, onOpen: (String) -> Unit) {
    when {
        state.loading && state.boards.isEmpty() ->
            LightText(text = "Loading…", variant = LightTextVariant.Copy)

        state.boards.isEmpty() ->
            LightText(
                text = "No starred stations yet.\nTap search to find the stops you use and star them.",
                variant = LightTextVariant.Copy,
                lighten = true,
            )

        else -> {
            if (state.loading) {
                LightText(
                    text = "Refreshing…",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
            }
            state.boards.forEach { board ->
                StationBoardView(board, state.nowSeconds, state.favoriteRoutes) { onOpen(board.station.id) }
            }
        }
    }
}

@Composable
private fun NearbyContent(
    nearby: NearbyUiState,
    onOpen: (String) -> Unit,
    onEnableLocation: () -> Unit,
    onRetry: () -> Unit,
) {
    when (nearby.status) {
        NearbyStatus.LOADING, NearbyStatus.IDLE ->
            LightText(text = "Finding stations near you…", variant = LightTextVariant.Copy, lighten = true)

        NearbyStatus.NO_PERMISSION -> Column {
            LightText(
                text = "Location is off. Turn it on to see the stops closest to you.",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = "Enable location",
                variant = LightTextVariant.Heading,
                modifier = Modifier.lightClickable(onClick = onEnableLocation),
            )
        }

        NearbyStatus.UNAVAILABLE -> Column {
            LightText(
                text = "Couldn't get your location. Try again in a moment.",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = "Try again",
                variant = LightTextVariant.Heading,
                modifier = Modifier.lightClickable(onClick = onRetry),
            )
        }

        NearbyStatus.LOADED -> nearby.items.forEach { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = { onOpen(item.station.id) })
                    .padding(bottom = 1f.gridUnitsAsDp()),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LightText(
                        text = item.station.name,
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.weight(1f),
                    )
                    LightText(text = distanceLabel(item.meters), variant = LightTextVariant.Detail, lighten = true)
                }
                RouteBadgeRow(
                    routes = item.station.routes,
                    favorites = nearby.favoriteRoutes,
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun StationBoardView(
    board: StationBoard,
    nowSeconds: Long,
    favoriteRoutes: Set<String>,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            .padding(bottom = 1.5f.gridUnitsAsDp()),
    ) {
        LightText(text = board.station.name, variant = LightTextVariant.Heading)
        RouteBadgeRow(
            routes = board.station.routes,
            favorites = favoriteRoutes,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
        )
        if (board.failed) {
            LightText(text = "Times unavailable", variant = LightTextVariant.Detail, lighten = true)
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                DirectionColumn("Uptown", board.north, nowSeconds, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                DirectionColumn("Downtown", board.south, nowSeconds, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DirectionColumn(
    label: String,
    arrivals: List<Arrival>,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        if (arrivals.isEmpty()) {
            LightText(text = "—", variant = LightTextVariant.Detail, lighten = true)
        } else {
            arrivals.forEach { a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 0.15f.gridUnitsAsDp()),
                ) {
                    RouteBadge(a.route, diameter = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    LightText(
                        text = minutesLabel(a.minutesFrom(nowSeconds)),
                        variant = LightTextVariant.Copy,
                    )
                }
            }
        }
    }
}

/** Shows the last captured crash so it can be read/screenshotted off-device. */
@Composable
private fun CrashReportView(trace: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Last error"),
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = trace, variant = LightTextVariant.Detail)
        }
        LightBottomBar(
            items = listOf(LightBarButton.Text(text = "Dismiss", onClick = onDismiss)),
        )
    }
}

/** Best-effort current location via LocationManager; calls back with null on failure. */
@SuppressLint("MissingPermission")
private fun fetchLocation(context: Context, onResult: (Double?, Double?) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return onResult(null, null)
    val provider = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> LocationManager.FUSED_PROVIDER
    }
    try {
        val last = lm.getLastKnownLocation(provider)
        if (last != null) {
            onResult(last.latitude, last.longitude)
            return
        }
        lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
            onResult(loc?.latitude, loc?.longitude)
        }
    } catch (e: Exception) {
        onResult(null, null)
    }
}
