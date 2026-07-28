package com.thelightphone.subway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.runtime.rememberCoroutineScope
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

private const val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

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

enum class LocalStatus { IDLE, LOADING, NEED_PERMISSION, DONE }

data class LocalItem(val station: Station, val meters: Double?)

data class LocalUiState(
    val status: LocalStatus = LocalStatus.IDLE,
    val located: Boolean = false,
    val source: String = "",     // "GPS" or "approximate"
    val city: String = "",
    val boro: String? = null,    // set when browsing a borough manually
    val items: List<LocalItem> = emptyList(),
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

    private val _local = MutableStateFlow(LocalUiState())
    val local: StateFlow<LocalUiState> = _local

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

    fun setLocalStatus(status: LocalStatus) {
        _local.value = _local.value.copy(status = status)
    }

    /** Nearest stations from precise coordinates (GPS). */
    fun onCoords(lat: Double, lon: Double, source: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _local.value = _local.value.copy(status = LocalStatus.LOADING)
            val store = store()
            val favs = runCatching { store.favoriteRoutes() }.getOrDefault(emptySet())
            val items = store.nearest(lat, lon).map { LocalItem(it, it.distanceMetersTo(lat, lon)) }
            _local.value = LocalUiState(LocalStatus.DONE, true, source, "", null, items, favs)
        }
    }

    /** Fallback: approximate location from IP, else browse Manhattan. */
    fun loadViaIp() {
        viewModelScope.launch(Dispatchers.Default) {
            _local.value = _local.value.copy(status = LocalStatus.LOADING)
            val store = store()
            val favs = runCatching { store.favoriteRoutes() }.getOrDefault(emptySet())
            val geo = runCatching { repo.ipLocation() }.getOrNull()
            val la = geo?.latitude
            val lo = geo?.longitude
            if (la != null && lo != null) {
                val items = store.nearest(la, lo).map { LocalItem(it, it.distanceMetersTo(la, lo)) }
                _local.value = LocalUiState(LocalStatus.DONE, true, "approximate", geo.city, null, items, favs)
            } else {
                val list = store.all.filter { it.boro == "M" }.sortedBy { it.name }.map { LocalItem(it, null) }
                _local.value = LocalUiState(LocalStatus.DONE, false, "", "", "M", list, favs)
            }
        }
    }

    fun loadBorough(code: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _local.value = _local.value.copy(status = LocalStatus.LOADING)
            val store = store()
            val favs = runCatching { store.favoriteRoutes() }.getOrDefault(emptySet())
            val list = store.all.filter { it.boro == code }.sortedBy { it.name }.map { LocalItem(it, null) }
            _local.value = LocalUiState(LocalStatus.DONE, false, "", "", code, list, favs)
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
        val local by viewModel.local.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val lastCrash = remember { CrashReporter.lastCrash() }
        var showCrash by remember { mutableStateOf(lastCrash != null) }
        var tab by remember { mutableStateOf(HomeTab.STARRED) }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val locationLauncher = rememberPermissionRequestLauncher(LOCATION_PERMISSION)

        // Try precise GPS; if the permission isn't granted or there's no fix,
        // fall straight through to IP-approximate — never a "denied" dead end.
        val locate: () -> Unit = {
            scope.launch {
                viewModel.setLocalStatus(LocalStatus.LOADING)
                val gps = lastKnownLocation(context)
                if (gps != null) viewModel.onCoords(gps.first, gps.second, "GPS")
                else viewModel.loadViaIp()
            }
        }

        LaunchedEffect(tab) {
            // No permission popup on open — use GPS only if already granted, else IP.
            if (tab == HomeTab.LOCAL && local.status == LocalStatus.IDLE) locate()
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
                        HomeTab.LOCAL -> LocalContent(
                            local = local,
                            onNear = locate,
                            onEnableLocation = { runCatching { locationLauncher?.launch() } },
                            onBoro = { viewModel.loadBorough(it) },
                            onOpen = { id -> navigateTo({ sealed -> StationScreen(sealed, id) }) },
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "Search", onClick = { navigateTo(::SearchScreen) }),
                        if (tab == HomeTab.STARRED) {
                            LightBarButton.Text(
                                text = if (state.loading) "Refreshing…" else "Refresh",
                                onClick = { if (!state.loading) viewModel.refresh() },
                            )
                        } else {
                            LightBarButton.Text(
                                text = if (local.status == LocalStatus.LOADING) "Locating…" else "Refresh",
                                onClick = { if (local.status != LocalStatus.LOADING) locate() },
                            )
                        },
                    ),
                )
            }
        }
    }
}

private enum class HomeTab { STARRED, LOCAL }

private val BOROUGHS = listOf(
    "M" to "MAN",
    "Bk" to "BRK",
    "Q" to "QNS",
    "Bx" to "BRX",
    "SI" to "S-I",
)

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
private fun LocalContent(
    local: LocalUiState,
    onNear: () -> Unit,
    onEnableLocation: () -> Unit,
    onBoro: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    // "Near me" + borough acronym chips
    Row(modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp())) {
        val nearSel = local.located
        LightText(
            text = "Near me",
            variant = if (nearSel) LightTextVariant.Copy else LightTextVariant.Detail,
            lighten = !nearSel,
            modifier = Modifier.padding(end = 14.dp).lightClickable(onClick = onNear),
        )
        BOROUGHS.forEach { (code, label) ->
            val sel = code == local.boro
            LightText(
                text = label,
                variant = if (sel) LightTextVariant.Copy else LightTextVariant.Detail,
                lighten = !sel,
                modifier = Modifier.padding(end = 12.dp).lightClickable(onClick = { onBoro(code) }),
            )
        }
    }

    when (local.status) {
        LocalStatus.IDLE, LocalStatus.LOADING ->
            LightText(text = "Finding stations near you…", variant = LightTextVariant.Copy, lighten = true)

        LocalStatus.NEED_PERMISSION -> Column {
            LightText(
                text = "Allow location to see the stops closest to you.",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = "Allow location",
                variant = LightTextVariant.Heading,
                modifier = Modifier.lightClickable(onClick = onEnableLocation),
            )
            LightText(
                text = "Then tap Refresh.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }

        LocalStatus.DONE -> {
            if (local.located && (local.city.isNotBlank() || local.source.isNotBlank())) {
                val where = if (local.city.isNotBlank()) "Near ${local.city}" else "Near you"
                LightText(
                    text = "$where · ${local.source}",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
            }
            if (local.source == "approximate") {
                LightText(
                    text = "Use precise location →",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier
                        .padding(bottom = 0.5f.gridUnitsAsDp())
                        .lightClickable(onClick = onEnableLocation),
                )
            }
            if (local.items.isEmpty()) {
                LightText(text = "No stations found.", variant = LightTextVariant.Copy, lighten = true)
            } else {
                local.items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable(onClick = { onOpen(item.station.id) })
                            .padding(bottom = 0.75f.gridUnitsAsDp()),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            LightText(
                                text = item.station.name,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.weight(1f),
                            )
                            if (item.meters != null) {
                                LightText(text = distanceLabel(item.meters), variant = LightTextVariant.Detail, lighten = true)
                            }
                        }
                        RouteBadgeRow(
                            routes = item.station.routes,
                            favorites = local.favoriteRoutes,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                    }
                }
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
                DirectionColumn("Uptown", board.north, nowSeconds, favoriteRoutes, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                DirectionColumn("Downtown", board.south, nowSeconds, favoriteRoutes, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DirectionColumn(
    label: String,
    arrivals: List<Arrival>,
    nowSeconds: Long,
    favoriteRoutes: Set<String>,
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
                ArrivalRowLine(
                    route = a.route,
                    minutesText = minutesLabel(a.minutesFrom(nowSeconds)),
                    favorite = a.route in favoriteRoutes,
                    diameter = 20.dp,
                    modifier = Modifier.padding(vertical = 0.15f.gridUnitsAsDp()),
                )
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

/** Best-effort last-known location from the system providers (permission required). */
@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.FUSED_PROVIDER,
    )
    for (p in providers) {
        try {
            lm.getLastKnownLocation(p)?.let { return it.latitude to it.longitude }
        } catch (_: Exception) {
        }
    }
    return null
}
