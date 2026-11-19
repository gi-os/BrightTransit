package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.Action
import com.thelightphone.subway.ui.searchButton
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(
    saved: SavedStore,
    private val catalog: suspend () -> Catalog,
    from: String? = null,
) : LightViewModel<Unit>() {

    /** The station whose page search was opened from, once the stations are parsed. Null when it was opened anywhere else. */
    private val _origin = MutableStateFlow<Station?>(null)
    val origin: StateFlow<Station?> = _origin

    /** Null until something has been searched for. */
    private val _query = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> = _query

    private val _results = MutableStateFlow<List<Station>>(emptyList())
    val results: StateFlow<List<Station>> = _results

    val favorites: StateFlow<Set<String>> =
        saved.favoriteRoutes.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** The keyboard opens by itself the first time only; coming back from a station must not reopen it. */
    var keyboardOffered = false

    /** True from the moment a search is submitted, before its results are ready. */
    @Volatile
    var hasSearched = false

    init {
        // Have the catalog parsed by the time the first search is typed.
        viewModelScope.launch(Dispatchers.Default) { catalog() }
        // Coming from a station's page the stations are parsed already, so this is in
        // place before the keyboard opens.
        if (from != null) viewModelScope.launch { _origin.value = catalog().station(from) }
    }

    fun search(text: String) {
        val trimmed = text.trim()
        hasSearched = true
        viewModelScope.launch(Dispatchers.Default) {
            _results.value = catalog().search.search(trimmed)
            _query.value = trimmed
        }
    }
}

/**
 * Search goes straight to the keyboard. The results come back on this screen, and the
 * search button at the bottom starts over.
 *
 * [from] is the station whose page search was opened from, if it was one. It is handed to
 * the station that is picked, which then says which trains run to it from there. One
 * quiet line says so, where the rider types and over what they find: nothing else in the
 * tool tells them it can do this. Opened from anywhere else, search says nothing extra.
 */
class SearchScreen(
    sealedActivity: SealedLightActivity,
    private val from: String? = null,
) : LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel> get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel {
        val readAsset = lightContext::readAsset
        return SearchViewModel(Subway.saved(lightContext.dataStore), { Subway.catalog(readAsset) }, from)
    }

    private val wheel = WheelBus()

    /** Started afresh by each search, so new results open at their top and not where the last list was left. */
    private var page = Page()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    /** Set while the keyboard is the screen on top of this one. */
    private var keyboardOpen = false

    /** Goes up each time the keyboard closes and this screen is showing again. */
    private var keyboardReturns by mutableIntStateOf(0)

    private fun openKeyboard() {
        keyboardOpen = true
        navigateTo<String?>(
            screenFactory = { TextInputScreen(it, "Search stations", viewModel.query.value.orEmpty(), viewModel.origin.value?.let(PlanWords::searching)) },
            resultCallback = { typed ->
                if (!typed.isNullOrBlank()) {
                    page = Page()
                    viewModel.search(typed)
                }
            },
        )
    }

    override fun willShow() {
        if (keyboardOpen) {
            keyboardOpen = false
            keyboardReturns++
        }
    }

    @Composable
    override fun Content() {
        val query by viewModel.query.collectAsState()
        val results by viewModel.results.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val origin by viewModel.origin.collectAsState()

        LaunchedEffect(Unit) {
            if (!viewModel.keyboardOffered) {
                viewModel.keyboardOffered = true
                openKeyboard()
            }
        }

        // Backing out of the keyboard with nothing ever searched leaves no results page to
        // land on, so carry on back to where search was opened. The SDK does not report a
        // keyboard closed with back (no result is delivered), so this is checked when the
        // screen reappears rather than in the keyboard's callback.
        LaunchedEffect(keyboardReturns) {
            if (keyboardReturns > 0 && !viewModel.hasSearched) goBack()
        }

        val searched = query
        SubwayScreen(
            title = if (searched == null) "Search" else "“$searched”",
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            bottom = listOf(searchButton { openKeyboard() }),
            message = when {
                searched == null -> ""
                results.isEmpty() -> "No stations found for “$searched”.\n\nTry part of the name, a cross street or a line."
                else -> null
            },
            actions = if (searched != null && results.isEmpty()) listOf(Action("SEARCH AGAIN") { openKeyboard() }) else emptyList(),
        ) {
            origin?.let { Note(PlanWords.searching(it), Modifier.padding(bottom = 0.75f.gridUnitsAsDp())) }
            results.forEach { station ->
                key(searched, station.id) {
                    FadeIn {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(onClick = { navigateTo({ StationScreen(it, station.id, from) }) })
                                .padding(bottom = 0.75f.gridUnitsAsDp()),
                        ) {
                            LightText(text = station.name, variant = LightTextVariant.Copy)
                            RouteBadgeRow(
                                routes = station.routes,
                                favorites = favorites,
                                modifier = Modifier.padding(vertical = 0.25f.gridUnitsAsDp()),
                            )
                            Note(station.boroLabel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The full-screen Light keyboard, set up the way the person has it in LightOS. Returns what
 * was typed, or null if they backed out. The Return key searches, as the button does.
 *
 * With [note], one grey line stands under the line being typed on, in the room the kit
 * leaves empty above its keyboard.
 */
class TextInputScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String = "",
    private val note: String? = null,
) : SimpleLightScreen<String?>(sealedActivity) {

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initialValue)
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Box {
                LightTextInputEditor(
                    title = title,
                    state = textState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    // An empty box is not a search. Return is one tap from the space bar, and
                    // without this a stray press would close the keyboard and the search with it.
                    onSubmit = { typed -> if (typed.isNotBlank()) goBack(typed.toString()) },
                    onBack = { goBack(null) },
                    submitIcon = LightIcons.SEARCH,
                    // One line, so Return submits instead of starting a second one.
                    singleLine = true,
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                )
                // The kit's editor has no place for a line of the tool's own, so it is laid
                // over it: under the one line being typed on, at the editor's own margins.
                if (note != null) {
                    Note(note, Modifier.padding(start = NOTE_SIDE.gridUnitsAsDp(), end = NOTE_SIDE.gridUnitsAsDp(), top = NOTE_TOP.gridUnitsAsDp()))
                }
            }
        }
    }
}

/** The editor's side margin, in grid units. */
private const val NOTE_SIDE = 2f

/** How far down the screen the editor's underline ends, in grid units, with a little air under it. */
private const val NOTE_TOP = 7.5f
