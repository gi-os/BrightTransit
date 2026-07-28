package com.thelightphone.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    val arrivals: List<Arrival> = emptyList(),
    val failed: Boolean = false,
)

data class HomeUiState(
    val loading: Boolean = true,
    val boards: List<StationBoard> = emptyList(),
    val nowSeconds: Long = System.currentTimeMillis() / 1000,
)

class HomeViewModel(
    private val store: StationStore,
) : LightViewModel<Unit>() {

    private val repo = ArrivalsRepository()
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val starred = store.starredStations()
            if (starred.isEmpty()) {
                _state.value = HomeUiState(loading = false, boards = emptyList())
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            val now = System.currentTimeMillis() / 1000
            val boards = starred.map { station ->
                runCatching { repo.arrivalsFor(station, now) }
                    .fold(
                        onSuccess = { StationBoard(station, it.take(5)) },
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
        val catalog = String(lightContext.readAsset("stations.json"), Charsets.UTF_8)
        return HomeViewModel(StationStore(lightContext.dataStore, catalog))
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Subway"),
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
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = board.station.name, variant = LightTextVariant.Heading)
        LightText(
            text = board.station.routes.joinToString(" "),
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        when {
            board.failed ->
                LightText(text = "Times unavailable", variant = LightTextVariant.Detail, lighten = true)
            board.arrivals.isEmpty() ->
                LightText(text = "No trains scheduled", variant = LightTextVariant.Detail, lighten = true)
            else -> board.arrivals.forEach { ArrivalRow(it, nowSeconds) }
        }
    }
}
