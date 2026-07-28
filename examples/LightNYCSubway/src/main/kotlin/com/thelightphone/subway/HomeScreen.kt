package com.thelightphone.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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

data class StationBoard(
    val station: Station,
    val north: List<Arrival> = emptyList(),
    val south: List<Arrival> = emptyList(),
    val failed: Boolean = false,
)

data class HomeUiState(
    val loading: Boolean = true,
    val boards: List<StationBoard> = emptyList(),
    val nowSeconds: Long = System.currentTimeMillis() / 1000,
)

class HomeViewModel(
    private val dataStore: DataStore<Preferences>,
    private val readAsset: (String) -> ByteArray,
) : LightViewModel<Unit>() {

    private val repo = ArrivalsRepository()
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        // All heavy work (catalog parse, network) happens off the main thread so
        // the first frame renders immediately.
        viewModelScope.launch(Dispatchers.Default) {
            val stations = StationCatalog.load(readAsset)
            val store = StationStore(dataStore, stations)
            val starred = runCatching { store.starredStations() }.getOrDefault(emptyList())
            if (starred.isEmpty()) {
                _state.value = HomeUiState(loading = false, boards = emptyList())
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
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
            _state.value = HomeUiState(loading = false, boards = boards, nowSeconds = now)
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
        val themeColors by LightThemeController.colors.collectAsState()
        val lastCrash = remember { CrashReporter.lastCrash() }
        var showCrash by remember { mutableStateOf(lastCrash != null) }

        LightTheme(colors = themeColors) {
            if (showCrash && lastCrash != null) {
                CrashReportView(lastCrash) {
                    CrashReporter.clear()
                    showCrash = false
                }
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

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    when {
                        state.loading && state.boards.isEmpty() ->
                            LightText(text = "Loading…", variant = LightTextVariant.Copy)

                        state.boards.isEmpty() ->
                            LightText(
                                text = "No starred stations yet.\nTap search to find the stops you " +
                                    "use and star them.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )

                        else -> state.boards.forEach { board ->
                            StationBoardView(board, state.nowSeconds) {
                                navigateTo({ StationScreen(it, board.station.id) })
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "Search",
                            onClick = { navigateTo(::SearchScreen) },
                        ),
                        LightBarButton.Text(
                            text = "Refresh",
                            onClick = { viewModel.refresh() },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun StationBoardView(
    board: StationBoard,
    nowSeconds: Long,
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

/** One labeled column (Uptown / Downtown) of upcoming trains: bullet + minutes. */
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
            items = listOf(
                LightBarButton.Text(text = "Dismiss", onClick = onDismiss),
            ),
        )
    }
}
