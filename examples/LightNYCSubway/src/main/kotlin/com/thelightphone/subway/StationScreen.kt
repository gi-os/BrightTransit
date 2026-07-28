package com.thelightphone.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class StationUiState(
    val station: Station? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val starred: Boolean = false,
    val north: List<Arrival> = emptyList(),
    val south: List<Arrival> = emptyList(),
    val nowSeconds: Long = System.currentTimeMillis() / 1000,
)

class StationViewModel(
    private val dataStore: DataStore<Preferences>,
    private val readAsset: (String) -> ByteArray,
    private val stationId: String,
) : LightViewModel<Unit>() {

    private val repo = ArrivalsRepository()
    private val _state = MutableStateFlow(StationUiState())
    val state: StateFlow<StationUiState> = _state

    @Volatile
    private var store: StationStore? = null

    private fun store(): StationStore =
        store ?: StationStore(dataStore, StationCatalog.load(readAsset)).also { store = it }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.Default) {
            val s = store()
            val station = s.station(stationId)
            if (station == null) {
                _state.value = StationUiState(station = null, loading = false, failed = true)
                return@launch
            }
            _state.value = _state.value.copy(station = station, loading = true)
            val starred = runCatching { s.isStarred(stationId) }.getOrDefault(false)
            val now = System.currentTimeMillis() / 1000
            runCatching { repo.arrivalsFor(station, now) }.fold(
                onSuccess = { arrivals ->
                    _state.value = StationUiState(
                        station = station,
                        loading = false,
                        starred = starred,
                        north = arrivals.filter { it.direction == Direction.NORTH }.take(6),
                        south = arrivals.filter { it.direction == Direction.SOUTH }.take(6),
                        nowSeconds = now,
                    )
                },
                onFailure = {
                    _state.value = StationUiState(
                        station = station, loading = false, failed = true, starred = starred,
                    )
                },
            )
        }
    }

    fun toggleStar() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                store().toggleStar(stationId)
                _state.value = _state.value.copy(starred = store().isStarred(stationId))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repo.close()
    }
}

class StationScreen(
    sealedActivity: SealedLightActivity,
    private val stationId: String,
) : LightScreen<Unit, StationViewModel>(sealedActivity) {

    override val viewModelClass: Class<StationViewModel> get() = StationViewModel::class.java

    override fun createViewModel(): StationViewModel =
        StationViewModel(lightContext.dataStore, lightContext::readAsset, stationId)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val station = state.station

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text(station?.name ?: "Station"),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (station == null) {
                        LightText(
                            text = if (state.loading) "Loading…" else "Unknown station.",
                            variant = LightTextVariant.Copy,
                        )
                    } else {
                        RouteBadgeRow(
                            routes = station.routes,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = station.boroLabel,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()),
                        )
                        when {
                            state.loading ->
                                LightText(text = "Loading…", variant = LightTextVariant.Copy)
                            state.failed ->
                                LightText(
                                    text = "Live times unavailable right now.",
                                    variant = LightTextVariant.Copy, lighten = true,
                                )
                            else -> {
                                DirectionSection(
                                    label = station.nl.ifEmpty { "Northbound" },
                                    arrivals = state.north,
                                    nowSeconds = state.nowSeconds,
                                )
                                DirectionSection(
                                    label = station.sl.ifEmpty { "Southbound" },
                                    arrivals = state.south,
                                    nowSeconds = state.nowSeconds,
                                )
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = if (state.starred) "Unstar" else "Star",
                            onClick = { viewModel.toggleStar() },
                        ),
                        LightBarButton.Text(
                            text = "Refresh",
                            onClick = { viewModel.load() },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun DirectionSection(label: String, arrivals: List<Arrival>, nowSeconds: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        if (arrivals.isEmpty()) {
            LightText(text = "No trains", variant = LightTextVariant.Detail, lighten = true)
        } else {
            arrivals.forEach { arrival ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.25f.gridUnitsAsDp()),
                ) {
                    RouteBadge(arrival.route)
                    Spacer(Modifier.weight(1f))
                    LightText(
                        text = minutesLabel(arrival.minutesFrom(nowSeconds)),
                        variant = LightTextVariant.Copy,
                    )
                }
            }
        }
    }
}
