package com.thelightphone.subway

import com.thelightphone.subway.data.Alerts
import com.thelightphone.subway.data.AlertsRepository
import com.thelightphone.subway.data.ArrivalsRepository
import com.thelightphone.subway.data.Board
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The refresh loop behind every screen: when it fetches, when it stops, and what it keeps. */
class LiveBoardsTest {
    private val phone = TestPhone()
    private val morgan = Fixtures.station("Morgan Av")
    private val bedford = Fixtures.station("Bedford Av", "L")
    private val shown = phone.now

    /** A download that does not come back until the test lets it. */
    private fun slowLink(): CompletableDeferred<ByteArray> =
        CompletableDeferred<ByteArray>().also { gate -> phone.trains = { gate.await() } }

    @Test
    fun aHiddenScreenDownloadsNothing() {
        val screen = phone.screen(morgan)
        phone.elapse(120)
        assertEquals(0, phone.trainDownloads)
        assertEquals(0, phone.alertDownloads)
        assertEquals(0, phone.waiting)
        assertSame(Board.View.Loading, screen.says())
    }

    @Test
    fun showingFetchesAtOnceAndThenEveryHalfMinute() {
        val screen = phone.screen(morgan)
        screen.resume()
        assertEquals(1, phone.trainDownloads)
        assertFalse(assertIs<Board.View.Trains>(screen.says()).stale)

        phone.elapse(29)
        assertEquals(1, phone.trainDownloads)
        phone.elapse(1)
        assertEquals(2, phone.trainDownloads)
        phone.elapse(60)
        assertEquals(4, phone.trainDownloads)
        assertEquals(mapOf("Manhattan" to listOf(2, 9, 17, 25, 33), "Brooklyn" to listOf(5, 12, 19, 27, 35, 43)), screen.says().minutes(phone.now))
    }

    @Test
    fun theClockMovesEveryTenSecondsWhileShowingAndNotAtAllWhileHidden() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.elapse(9)
        assertEquals(shown, screen.now.value)
        phone.elapse(1)
        assertEquals(shown + 10, screen.now.value)
        phone.elapse(14)
        assertEquals(shown + 20, screen.now.value)

        screen.pause()
        phone.elapse(60)
        assertEquals(shown + 20, screen.now.value)
    }

    @Test
    fun theClockIsReadTheMomentTheScreenShows() {
        val screen = phone.screen(morgan)
        phone.elapse(600)
        slowLink()
        screen.resume()
        // Nothing has come back and no tick has passed, and the clock is already right.
        assertEquals(shown + 600, screen.now.value)
    }

    @Test
    fun pauseStopsTheLoop() {
        val screen = phone.screen(morgan)
        screen.resume()
        assertEquals(2, phone.waiting)
        screen.pause()
        assertEquals(0, phone.waiting)
        phone.elapse(300)
        assertEquals(1, phone.trainDownloads)

        screen.resume()
        assertEquals(2, phone.trainDownloads)
        assertEquals(2, phone.waiting)
    }

    @Test
    fun pauseInTheMiddleOfALoadLeavesTheDownloadToFinishForTheNextScreen() {
        val gate = slowLink()
        val screen = phone.screen(morgan)
        screen.resume()
        screen.pause()
        gate.complete(phone.feedNow())

        // The hidden screen was not handed the board, and is not waiting for anything.
        assertSame(Board.View.Loading, screen.says())
        assertEquals(0, phone.waiting)
        // The feed arrived all the same, and is what the next screen starts from.
        assertIs<Board.View.Trains>(phone.screen(morgan).says())
        assertEquals(1, phone.trainDownloads)
    }

    @Test
    fun refreshDownloadsAgainHoweverNewTheCopy() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.elapse(5)
        screen.refreshNow()
        assertEquals(2, phone.trainDownloads)
        // The MTA answered at once. The dimming stays up a moment anyway, so the press is seen.
        assertTrue(screen.refreshing.value)
        phone.elapse(1)
        assertFalse(screen.refreshing.value)
    }

    @Test
    fun theDimmingStaysUntilASlowRefreshIsBack() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.elapse(5)
        val gate = slowLink()
        screen.refreshNow()
        phone.elapse(5)
        assertTrue(screen.refreshing.value)
        gate.complete(phone.feedNow())
        assertFalse(screen.refreshing.value)
    }

    @Test
    fun refreshOnAHiddenScreenDoesNothing() {
        val screen = phone.screen(morgan)
        screen.refreshNow()
        assertEquals(0, phone.trainDownloads)
        assertFalse(screen.refreshing.value)
        assertEquals(0, phone.waiting)
    }

    @Test
    fun hidingTheScreenEndsARefreshInFlight() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.elapse(5)
        slowLink()
        screen.refreshNow()
        assertTrue(screen.refreshing.value)
        screen.pause()
        assertFalse(screen.refreshing.value)
        assertEquals(0, phone.waiting)
    }

    @Test
    fun aPressedRefreshIsCarriedOverWhenTheStationsAreSetAgain() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.elapse(5)
        val gate = slowLink()
        screen.refreshNow()
        assertEquals(2, phone.trainDownloads)

        // Nearby looks the location up again on the same press, and finds another station closest.
        screen.following(bedford)
        assertTrue(screen.refreshing.value)
        assertEquals(2, phone.trainDownloads)

        gate.complete(phone.feedNow())
        phone.elapse(1)
        assertFalse(screen.refreshing.value)
        // Bedford Av got the feed the press asked for, not the copy from before it.
        val board = screen.boards.value.single()
        assertEquals(bedford.id, board.station.id)
        assertEquals(Fixtures.lFeed.timestamp + 5, board.asOf)
    }

    @Test
    fun stationsAlreadyOnScreenKeepTheirBoards() {
        val screen = phone.screen(morgan)
        screen.resume()
        val before = screen.boards.value.single()
        screen.following(bedford, morgan)
        assertEquals(listOf(bedford.id, morgan.id), screen.boards.value.map { it.station.id })
        assertEquals(before.arrivals, screen.boards.value[1].arrivals)
        assertEquals(1, phone.trainDownloads)
    }

    @Test
    fun aStationOpenedFromHomeStartsWithTheTimesHomeHad() {
        val home = phone.screen(morgan)
        home.resume()
        // Long enough that the copy is no longer used as is, and a new screen has to fetch.
        phone.elapse(25)
        home.pause()

        val gate = slowLink()
        val page = phone.screen()
        page.resume()
        page.following(morgan)
        assertEquals(2, phone.trainDownloads)
        val first = assertIs<Board.View.Trains>(page.says())
        assertFalse(first.stale)
        assertEquals(mapOf("Manhattan" to listOf(1, 8, 16, 24, 32), "Brooklyn" to listOf(5, 12, 19, 27, 35, 43)), first.minutes(phone.now))

        gate.complete(phone.feedNow())
        assertEquals(phone.now - 5, page.boards.value.single().asOf)
        assertFalse(page.boards.value.single().loading)
    }

    @Test
    fun aStationNobodyHasLoadedStartsAsLoading() {
        slowLink()
        val page = phone.screen()
        page.resume()
        page.following(morgan)
        assertSame(Board.View.Loading, page.says())
    }

    @Test
    fun alertsAreAskedForAfterTheTrainsAndNeverWaitedFor() {
        val trains = slowLink()
        val alerts = CompletableDeferred<ByteArray>()
        phone.alertsAnswer = { alerts.await() }
        val screen = phone.screen(morgan)
        screen.resume()
        assertEquals(0, phone.alertDownloads)

        trains.complete(phone.feedNow())
        assertEquals(1, phone.alertDownloads)
        // The alerts are still on their way. The times are up and the next pass is not held.
        assertIs<Board.View.Trains>(screen.says())
        assertSame(Alerts.NONE, screen.alerts.value)
        phone.online()
        phone.elapse(30)
        assertEquals(2, phone.trainDownloads)

        alerts.complete(Fixtures.alertBytes)
        assertNotSame(Alerts.NONE, screen.alerts.value)
    }

    @Test
    fun noAlertsAreAskedForWithNothingFollowed() {
        val screen = phone.screen()
        screen.resume()
        phone.elapse(120)
        assertEquals(0, phone.trainDownloads)
        assertEquals(0, phone.alertDownloads)

        screen.following(morgan)
        assertEquals(1, phone.trainDownloads)
        assertEquals(1, phone.alertDownloads)
    }

    @Test
    fun theRefreshButtonAsksForAlertsTooButAPassDoesNotUntilTheyAreDue() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.elapse(AlertsRepository.FORCED_FRESH_FOR)
        assertEquals(3, phone.trainDownloads)
        assertEquals(1, phone.alertDownloads)
        screen.refreshNow()
        // Once the trains are back and the dimming has had its moment.
        phone.elapse(1)
        assertEquals(2, phone.alertDownloads)
    }

    @Test
    fun callsFromSeveralThreadsNeverLeaveALoopThatPauseCannotStop() {
        // A real thread of its own this time, as a view model's scope has, with the calls
        // coming from other threads the way follow() does from a screen.
        val own = Executors.newSingleThreadExecutor()
        val confined = own.asCoroutineDispatcher()
        try {
            val at = Fixtures.lFeed.timestamp
            val parked = AtomicInteger()
            val live = LiveBoards(
                scope = CoroutineScope(confined),
                catalog = { Fixtures.catalog },
                arrivals = ArrivalsRepository(download = { Fixtures.lFeedBytes }, clock = { at }, dispatcher = confined),
                alertsRepository = AlertsRepository(download = { Fixtures.alertBytes }, clock = { at }, scope = CoroutineScope(confined)),
                clock = { at },
                wait = {
                    parked.incrementAndGet()
                    try {
                        awaitCancellation()
                    } finally {
                        parked.decrementAndGet()
                    }
                },
            )
            // Everything handed to the thread so far has run by the time this returns, and a
            // few rounds cover what that work handed on in turn.
            val settle = { repeat(20) { runBlocking(confined) { } } }

            val callers = listOf(
                thread { repeat(300) { runBlocking { live.follow(listOf(morgan)) } } },
                thread { repeat(300) { live.resume(); live.pause() } },
                thread { repeat(300) { live.refreshNow() } },
            )
            repeat(300) { live.resume() }
            callers.forEach { it.join() }

            live.pause()
            settle()
            assertEquals(0, parked.get(), "a loop outlived pause()")

            // One loop when showing: its tick and the wait before its next pass.
            live.resume()
            settle()
            assertEquals(2, parked.get())
            live.pause()
            settle()
            assertEquals(0, parked.get())
        } finally {
            own.shutdownNow()
        }
    }
}
