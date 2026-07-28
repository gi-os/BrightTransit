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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SearchViewModel(val store: StationStore) : LightViewModel<Unit>() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _results = MutableStateFlow<List<Station>>(emptyList())
    val results: StateFlow<List<Station>> = _results

    fun setQuery(q: String) {
        _query.value = q
        _results.value = store.search(q)
    }
}

class SearchScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel> get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel {
        val catalog = String(lightContext.readAsset("stations.json"), Charsets.UTF_8)
        return SearchViewModel(StationStore(lightContext.dataStore, catalog))
    }

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
                                LightText(
                                    text = station.routes.joinToString(" ") +
                                        "   ·   " + station.boroLabel,
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
