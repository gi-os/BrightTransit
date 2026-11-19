package com.thelightphone.subway

import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Planner
import com.thelightphone.subway.data.Station
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The way from one station to another, kept current through the real repository: which
 * feeds it costs, what it says before they are in, and what it says when they cannot be had.
 * The MTA here is the recorded weekday afternoon, moved along so that it is always now.
 */
class LivePlanTest {
    private val day = Fixtures.weekdayAfternoon
    private val catalog = Fixtures.catalog
    private val phone = TestPhone(start = day.time)

    private val morgan = Fixtures.station("Morgan Av")
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val jay = Fixtures.station("Jay St-MetroTech")
    private val west4 = Fixtures.station("W 4 St-Wash Sq")

    /** The feeds asked for, in order. */
    private val asked = ArrayList<String>()

    /** That afternoon's feed, as it would be were that afternoon now. */
    private fun recorded(slug: String): ByteArray = TrainFeeds.bytes(TrainFeeds.later(day.feeds.getValue(slug), phone.now - day.time))

    /** The MTA answering, but for the feeds named in [down]. */
    private fun online(vararg down: String) {
        phone.trains = { url ->
            val slug = url.substringAfterLast("%2F")
            asked += slug
            if (slug in down) throw IOException("timed out") else recorded(slug)
        }
    }

    init {
        online()
    }

    private fun LivePlan.following(from: Station, to: Station) = runBlocking { follow(from.id, to.id) }

    private fun LivePlan.says(): Plan.View? = following.value.plan?.view(phone.now)

    private fun LivePlan.offered(): List<String> = (says() as? Plan.View.Trains)?.choices.orEmpty().map { it.train.id }

    private fun board(station: Station): Board = runBlocking { phone.arrivals.board(station, catalog) }

    @Test
    fun aHiddenScreenDownloadsNothing() {
        val way = phone.way()
        way.following(morgan, unionSq)
        phone.elapse(120)
        assertEquals(emptyList(), asked)
        assertEquals(0, phone.waiting)
        assertEquals(LivePlan.Following(plan = null, loading = true), way.following.value)
    }

    @Test
    fun showingFetchesTheFeedsOfBothStationsAndThenAgainEveryHalfMinute() {
        val way = phone.way()
        way.following(morgan, unionSq)
        way.resume()
        assertEquals(listOf("gtfs", "gtfs-l", "gtfs-nqrw"), asked.sorted())
        assertFalse(way.following.value.loading)
        assertEquals(listOf("094300_L..N"), way.offered())

        phone.elapse(29)
        assertEquals(3, asked.size)
        phone.elapse(1)
        assertEquals(6, asked.size)
        phone.elapse(60)
        assertEquals(12, asked.size)
        // The afternoon has moved along with the clock, so the same train is still two minutes off.
        assertEquals(listOf("094300_L..N"), way.offered())
        assertEquals(0, phone.alertDownloads)
    }

    @Test
    fun theWayIsWorkedOutAtOnceWhenTheFeedsInHandAreEnough() {
        // Morgan Av's page has just been up, so the L's feed is in hand. No other line runs between the two.
        board(morgan)
        assertEquals(listOf("gtfs-l"), asked)
        val way = phone.way()
        way.following(morgan, unionSq)
        assertEquals(listOf("094300_L..N"), way.offered())
        assertTrue(way.following.value.loading)
        assertEquals(listOf("gtfs-l"), asked)
        // The other feeds are still fetched once the page shows: a train from another line could be running there today.
        way.resume()
        assertEquals(listOf("gtfs", "gtfs-l", "gtfs-nqrw"), asked.distinct().sorted())
        assertEquals(3, asked.size)
    }

    @Test
    fun nothingIsSaidWhileALineBetweenThemHasNotBeenLookedAt() {
        // From Jay St the A, C and F go to W 4 St. With only the R's feed in hand there is no way to say yet.
        runBlocking { phone.arrivals.feed("gtfs-nqrw") }
        val way = phone.way()
        way.following(jay, west4)
        assertNull(way.following.value.plan)
        // The page says so from its first frame: the answer is on its way.
        assertEquals(WayHere.Checking, WayHere.of(way.following.value, phone.now))

        val gate = CompletableDeferred<Unit>()
        phone.trains = { url ->
            gate.await()
            recorded(url.substringAfterLast("%2F"))
        }
        way.resume()
        assertNull(way.following.value.plan)
        assertEquals(WayHere.Checking, WayHere.of(way.following.value, phone.now))
        gate.complete(Unit)
        assertEquals(setOf("A", "C", "F"), assertNotNull(way.following.value.plan).trains.mapTo(HashSet()) { it.route })
        assertIs<WayHere.Trains>(WayHere.of(way.following.value, phone.now))
    }

    @Test
    fun aDownloadThatFailsTakesItsLinesOutOfTheWayAtOnce() {
        val way = phone.way()
        way.following(morgan, unionSq)
        way.resume()
        assertEquals(listOf("094300_L..N"), way.offered())

        online("gtfs-l")
        phone.elapse(30)
        // The copy from half a minute ago is still held, and a board goes on showing it, marked. A way there is not made from it.
        assertTrue(board(morgan).let { it.stale && it.arrivals.isNotEmpty() })
        assertEquals(Plan.View.Unchecked(listOf("L")), way.says())
        assertEquals(WayHere.Unchecked(listOf("L")), WayHere.of(way.following.value, phone.now))

        online()
        phone.elapse(30)
        assertIs<Plan.View.Trains>(way.says())
    }

    @Test
    fun aFeedNoLineBetweenThemRidesInCanFailWithoutASound() {
        online("gtfs", "gtfs-nqrw")
        val way = phone.way()
        way.following(morgan, unionSq)
        way.resume()
        assertEquals(emptyList(), assertIs<Plan.View.Trains>(way.says()).unchecked)
        assertEquals(listOf("094300_L..N"), way.offered())
    }

    @Test
    fun withSomeLinesCheckedAndSomeNotBothAreSaid() {
        online("gtfs-bdfm")
        val way = phone.way()
        way.following(jay, west4)
        way.resume()
        val view = assertIs<Plan.View.Trains>(way.says())
        assertEquals(listOf("F"), view.unchecked)
        assertTrue(view.choices.all { it.route in setOf("A", "C") })
        val said = assertIs<WayHere.Trains>(WayHere.of(way.following.value, phone.now))
        assertEquals(view.choices.first(), said.first)
        assertEquals(listOf("F"), said.unchecked)
    }

    @Test
    fun aWayAndABoardShowingTogetherShareTheirDownloads() {
        val boards = phone.screen(unionSq)
        val way = phone.way()
        way.following(morgan, unionSq)
        boards.resume()
        way.resume()
        assertEquals(listOf("gtfs", "gtfs-l", "gtfs-nqrw"), asked.sorted())
        phone.elapse(30)
        assertEquals(6, asked.size)
        // The refresh button fetches for both and again each feed is fetched once.
        phone.elapse(5)
        boards.refreshNow()
        way.refreshNow()
        assertEquals(9, asked.size)
    }

    @Test
    fun aPlanLeftInMemoryIsJudgedAgainWhenItIsLookedAt() {
        val way = phone.way()
        way.following(morgan, unionSq)
        way.resume()
        way.pause()
        assertIs<WayHere.Trains>(WayHere.of(way.following.value, phone.now))

        // Two minutes on its feeds are old, and so is everything it had to offer.
        phone.sleep(Planner.FRESH_FOR + 30)
        assertEquals(Plan.View.Unchecked(listOf("L")), way.says())
        assertEquals(WayHere.Unchecked(listOf("L")), WayHere.of(way.following.value, phone.now))

        // Coming back to the page asks again. Until the answer is in, an old plan has failed
        // at nothing: it is being checked, and the block keeps its place on the page.
        val gate = CompletableDeferred<Unit>()
        phone.trains = { url ->
            gate.await()
            recorded(url.substringAfterLast("%2F"))
        }
        way.resume()
        assertTrue(way.following.value.loading)
        assertEquals(WayHere.Checking, WayHere.of(way.following.value, phone.now))
        gate.complete(Unit)
        assertFalse(way.following.value.loading)
        assertIs<WayHere.Trains>(WayHere.of(way.following.value, phone.now))
    }

    @Test
    fun theBlockIsNeverTakenOffThePageWhileADownloadFailsAndComesBack() {
        val way = phone.way()
        way.following(morgan, unionSq)
        way.resume()
        val said = ArrayList<WayHere?>()
        said += WayHere.of(way.following.value, phone.now)
        online("gtfs-l")
        phone.elapse(30)
        said += WayHere.of(way.following.value, phone.now)
        // The refresh button, pressed while the line cannot be checked: asked again, not gone.
        val gate = CompletableDeferred<Unit>()
        phone.trains = { url ->
            gate.await()
            recorded(url.substringAfterLast("%2F"))
        }
        phone.elapse(5)
        way.refreshNow()
        said += WayHere.of(way.following.value, phone.now)
        gate.complete(Unit)
        said += WayHere.of(way.following.value, phone.now)
        assertTrue(said.none { it == null })
        assertIs<WayHere.Trains>(said[0])
        assertEquals(WayHere.Unchecked(listOf("L")), said[1])
        assertEquals(WayHere.Checking, said[2])
        assertIs<WayHere.Trains>(said[3])
    }

    @Test
    fun theTrainOfferedMovesOnAsTrainsLeave() {
        val way = phone.way()
        way.following(unionSq, morgan)
        way.resume()
        // The L for Brooklyn is leaving Union Sq in nine seconds. The next is five minutes behind it.
        assertEquals(listOf("095750_L..S"), way.offered())
        phone.elapse(30)
        assertEquals(listOf("095750_L..S"), way.offered())
        // Between downloads the clock alone takes a train off the list once it is half a minute gone.
        way.pause()
        phone.sleep(45)
        assertEquals(listOf("096250_L..S"), way.offered())
    }

    @Test
    fun whatAStationsPageSaysOfTheWayThere() {
        // A page that was not opened from another station has nothing to say of the way to it.
        assertNull(WayHere.of(null, phone.now))
        // One that was says something from its first frame.
        assertEquals(WayHere.Checking, WayHere.of(LivePlan.Following(), phone.now))

        fun planned(from: Station, to: Station, vararg down: String): LivePlan.Following {
            online(*down)
            // Long enough that the refresh button fetches again.
            phone.elapse(3)
            return LivePlan.Following(runBlocking { phone.arrivals.plan(from, to, catalog, force = true) }, loading = false)
        }
        // One train, and how many more are worth a look.
        val uptown = assertIs<WayHere.Trains>(WayHere.of(planned(Fixtures.station("96 St", "1"), Fixtures.station("Times Sq-42 St")), phone.now))
        assertEquals("091550_2..S17R", uptown.first.train.id)
        assertEquals(1, uptown.others)
        assertEquals(emptyList(), uptown.unchecked)
        // No train runs direct: that is said, and goes on being said while it is asked again.
        val none = planned(morgan, Fixtures.station("Times Sq-42 St"))
        assertEquals(WayHere.NoTrain, WayHere.of(none, phone.now))
        assertEquals(WayHere.NoTrain, WayHere.of(none.copy(loading = true), phone.now))
        // A line that could not be checked is named, unless the answer is still on its way.
        val down = planned(morgan, unionSq, "gtfs-l")
        assertEquals(WayHere.Unchecked(listOf("L")), WayHere.of(down, phone.now))
        assertEquals(WayHere.Checking, WayHere.of(down.copy(loading = true), phone.now))
        // Between two stations that share no line, a feed that could not be had leaves no line to name.
        // It is not "no direct train": the page says it could not check.
        val blind = planned(morgan, Fixtures.station("Times Sq-42 St"), "gtfs")
        assertEquals(WayHere.Unchecked(emptyList()), WayHere.of(blind, phone.now))
    }

    @Test
    fun aStationWithNoLineInCommonIsCheckedBeforeItIsSaidToHaveNoDirectTrain() {
        // From Morgan Av's page, with only the L's feed in hand, Times Sq is opened.
        board(morgan)
        val way = phone.way()
        way.following(morgan, Fixtures.station("Times Sq-42 St"))
        // No line runs between them, so there is nothing unchecked to wait for. But its own feeds have not been looked in yet.
        assertEquals(WayHere.Checking, WayHere.of(way.following.value, phone.now))
        way.resume()
        assertEquals(WayHere.NoTrain, WayHere.of(way.following.value, phone.now))
    }

    @Test
    fun theRepositoryPlansFromWhatItsLastDownloadsBroughtAndNothingOlder() {
        val repo = phone.arrivals
        assertNull(repo.peekPlan(morgan, unionSq, catalog))
        val first = runBlocking { repo.plan(morgan, unionSq, catalog) }
        assertEquals(setOf("gtfs", "gtfs-l", "gtfs-nqrw"), first.built.keys)
        assertEquals(first, repo.peekPlan(morgan, unionSq, catalog))

        // A second asking inside twenty seconds costs nothing.
        phone.elapse(10)
        runBlocking { repo.plan(morgan, unionSq, catalog) }
        assertEquals(3, asked.size)

        online("gtfs-l")
        phone.elapse(15)
        val second = runBlocking { repo.plan(morgan, unionSq, catalog) }
        assertEquals(6, asked.size)
        assertEquals(setOf("gtfs", "gtfs-nqrw"), second.built.keys)
        assertEquals(emptyList(), second.trains)
        assertEquals(listOf("L"), second.unchecked(phone.now))
        // The copy from before the failure is still there for a board, and is no answer here.
        assertNotNull(repo.peekFeed("gtfs-l"))
        assertNull(repo.peekPlan(morgan, unionSq, catalog))
    }
}
