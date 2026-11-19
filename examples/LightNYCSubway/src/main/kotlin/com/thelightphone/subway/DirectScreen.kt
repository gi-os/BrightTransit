package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Change
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.ChangeRow
import com.thelightphone.subway.ui.DirectRow
import com.thelightphone.subway.ui.FadeIn
import com.thelightphone.subway.ui.Note
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.RouteBadgeRow
import com.thelightphone.subway.ui.StationLabel
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.subway.ui.dimWhileRefreshing
import com.thelightphone.subway.ui.refreshButton
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DirectViewModel(
    private val catalog: suspend () -> Catalog,
    from: String,
    to: String,
) : LightViewModel<Unit>() {

    val live = LivePlan(viewModelScope, catalog)

    private val _names = MutableStateFlow<Catalog?>(null)
    val names: StateFlow<Catalog?> = _names

    init {
        // The station page this was opened from has just fetched the same feeds, so the
        // list is up on the first frame.
        viewModelScope.launch {
            _names.value = catalog()
            live.follow(from, to)
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

/** The sentences on the list of direct trains that are not about one train. */
internal object DirectWords {
    const val TAP = "Tap a train to count down its stops."

    /**
     * What the list says in place of its trains, or null when it has trains to show.
     * [view] is what the plan in hand offers, and [later] says the list is of later trains.
     * [changes] says it has ways with a change to show, which are shown with or without
     * direct trains above them.
     *
     * A plan with nothing to offer says "loading" while an answer is on its way, and "no
     * direct train" only once every feed one could ride in has been looked in.
     */
    fun instead(following: LivePlan.Following, view: Plan.View?, later: Boolean, changes: Boolean = false): String? {
        val plan = following.plan
        return when {
            view is Plan.View.Trains -> null
            changes && plan != null && view != null -> null
            plan == null || view == null || following.loading -> Board.View.Loading.text
            view is Plan.View.Unchecked -> "${PlanWords.unchecked(view.lines)}\n\nCheck your connection."
            else -> PlanWords.none(plan, later)
        }
    }
}

/**
 * The trains that run direct from one station to another, the one that gets there first
 * at the top, and below them the ways with one change that get there sooner. Tapping one
 * starts a ride on it with the stop already chosen: for a change, the stop to change at.
 *
 * [from] and [to] are station ids. With [after], the trains passed over there are left
 * out: the button in the middle opens the same list without the first train to go, for a
 * rider who will not make it, and back returns to the list before.
 */
class DirectScreen(
    sealedActivity: SealedLightActivity,
    private val from: String,
    private val to: String,
    private val after: Plan.After? = null,
) : LightScreen<Unit, DirectViewModel>(sealedActivity) {

    override val viewModelClass: Class<DirectViewModel> get() = DirectViewModel::class.java

    override fun createViewModel(): DirectViewModel {
        val readAsset = lightContext::readAsset
        return DirectViewModel({ Subway.catalog(readAsset) }, from, to)
    }

    private val wheel = WheelBus()
    private val page = Page()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val following by viewModel.live.following.collectAsState()
        val refreshing by viewModel.live.refreshing.collectAsState()
        val now by viewModel.live.now.collectAsState()
        val catalog by viewModel.names.collectAsState()
        val names = catalog
        val plan = following.plan
        val view = remember(plan, now) { plan?.view(now, after) }
        val changes = remember(plan, now) { plan?.changes(now, after).orEmpty() }
        val trains = view as? Plan.View.Trains

        SubwayScreen(
            title = names?.station(to)?.let(PlanWords::to).orEmpty(),
            subtitle = names?.station(from)?.let { "from ${it.name}" }.orEmpty(),
            wheel = wheel,
            page = page,
            onBack = { goBack() },
            refreshing = refreshing,
            bottom = listOf(
                null,
                trains?.later?.let { later ->
                    LightBarButton.Text(text = "LATER TRAINS", onClick = { navigateTo({ DirectScreen(it, from, to, after = later) }) })
                },
                refreshButton { viewModel.live.refreshNow() },
            ),
            message = if (names == null) "" else DirectWords.instead(following, view, later = after != null, changes = changes.isNotEmpty()),
        ) {
            if (names != null && plan != null && view != null && (trains != null || changes.isNotEmpty())) {
                DirectPage(plan, view, changes, now, names)
            }
        }
    }

    @Composable
    private fun DirectPage(plan: Plan, view: Plan.View, changes: List<Change>, now: Long, names: Catalog) {
        val trains = view as? Plan.View.Trains
        val unchecked = when (view) {
            is Plan.View.Trains -> view.unchecked.takeIf { it.isNotEmpty() }
            is Plan.View.Unchecked -> view.lines
            Plan.View.None -> null
        }
        // The station's own bullets and borough, as on its page: 138 stations share a name.
        RouteBadgeRow(routes = plan.to.routes)
        Note(plan.to.boroLabel, Modifier.padding(bottom = 0.25f.gridUnitsAsDp()))
        if (names.hasNamesake(plan.from)) StationLabel(PlanWords.from(plan.from), plan.from, bullets = true)
        FadeIn {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp())) {
                // What has to be read before the trains goes above them, where it cannot be scrolled past.
                if (unchecked != null) Note(PlanWords.unchecked(unchecked))
                if (after != null) Note(PlanWords.after(after.time))
                if (view == Plan.View.None) Note(PlanWords.none(plan, later = after != null))
                Column(modifier = Modifier.dimWhileRefreshing()) {
                    trains?.choices?.forEach { train ->
                        key(train.train.feed, train.train.id) {
                            DirectRow(
                                route = train.route,
                                words = PlanWords.row(train, plan, now, names),
                                onClick = { navigateTo({ RideScreen(it, train.ride()) }) },
                            )
                        }
                    }
                    if (trains != null && changes.isNotEmpty()) Note(PlanWords.WITH_A_CHANGE, Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                    changes.forEach { way ->
                        key(way.key) {
                            ChangeRow(
                                first = way.first.route,
                                second = way.second.route,
                                words = PlanWords.change(way, plan, now, names),
                                onClick = { navigateTo({ RideScreen(it, way.ride(to)) }) },
                            )
                        }
                    }
                }
                if (trains != null) Note(DirectWords.TAP, Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                if (changes.isNotEmpty()) Note(PlanWords.CHANGE_TAP, Modifier.padding(top = if (trains != null) 0f.gridUnitsAsDp() else 0.5f.gridUnitsAsDp()))
            }
        }
    }
}
