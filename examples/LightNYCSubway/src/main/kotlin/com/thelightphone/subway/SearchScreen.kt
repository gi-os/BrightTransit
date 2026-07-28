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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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

class SearchViewModel(
    private val dataStore: DataStore<Preferences>,
    private val readAsset: (String) -> ByteArray,
) : LightViewModel<Unit>() {

    @Volatile
    private var store: StationStore? = null

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _results = MutableStateFlow<List<Station>>(emptyList())
    val results: StateFlow<List<Station>> = _results
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    init {
        // Warm the catalog off the main thread so the first keystroke is instant.
        viewModelScope.launch(Dispatchers.Default) {
            val s = StationStore(dataStore, StationCatalog.load(readAsset)).also { store = it }
            _favorites.value = runCatching { s.favoriteRoutes() }.getOrDefault(emptySet())
            if (_query.value.isNotBlank()) {
                _results.value = s.search(_query.value)
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        val ready = store
        if (ready != null) {
            _results.value = ready.search(q)
        } else {
            viewModelScope.launch(Dispatchers.Default) {
                val s = StationStore(dataStore, StationCatalog.load(readAsset)).also { store = it }
                _results.value = s.search(q)
            }
        }
    }
}

class SearchScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel> get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel =
        SearchViewModel(lightContext.dataStore, lightContext::readAsset)

    private fun openKeyboard() {
        navigateTo<String?>(
            screenFactory = { TextInputScreen(it, "Search stations", viewModel.query.value) },
            resultCallback = { typed -> typed?.let { viewModel.setQuery(it) } },
        )
    }

    @Composable
    override fun Content() {
        val query by viewModel.query.collectAsState()
        val results by viewModel.results.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

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
                    center = LightTopBarCenter.Text(if (query.isEmpty()) "Search" else "\"$query\""),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = { openKeyboard() },
                        contentDescription = "Type",
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
                        query.isEmpty() ->
                            LightText(
                                text = "Tap the search icon and type a station name.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )

                        results.isEmpty() ->
                            LightText(
                                text = "No stations match \"$query\".",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )

                        else -> results.forEach { station ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable(onClick = {
                                        navigateTo({ StationScreen(it, station.id) })
                                    })
                                    .padding(bottom = 0.75f.gridUnitsAsDp()),
                            ) {
                                LightText(text = station.name, variant = LightTextVariant.Copy)
                                RouteBadgeRow(
                                    routes = station.routes,
                                    favorites = favorites,
                                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                                )
                                LightText(
                                    text = station.boroLabel,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "Type", onClick = { openKeyboard() }),
                    ),
                )
            }
        }
    }
}
