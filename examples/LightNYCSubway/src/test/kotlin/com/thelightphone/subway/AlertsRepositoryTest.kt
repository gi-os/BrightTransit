package com.thelightphone.subway

import com.thelightphone.subway.data.Alerts
import com.thelightphone.subway.data.AlertsRepository
import com.thelightphone.subway.data.ArrivalsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlertsRepositoryTest {
    private val morgan = Fixtures.station("Morgan Av")
    private val built = Fixtures.ALERTS_BUILT
    private var now = built
    private var downloads = 0

    /** What the MTA sends back. Swapped for a failure or a web page to take the phone offline. */
    private var answer: suspend () -> ByteArray = { Fixtures.alertBytes }

    /**
     * The repository's own scope would finish a download on another thread. Unconfined runs
     * it inside refresh() up to the first place it has to wait, so a download that never
     * waits is done by the time refresh() returns.
     */
    private val repo = AlertsRepository(
        download = {
            downloads++
            answer()
        },
        clock = { now },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private val held get() = repo.current.value

    private fun offline() {
        answer = { throw IOException("no signal") }
    }

    @Test
    fun theFirstCallDownloadsTheAlertsInEffect() {
        assertSame(Alerts.NONE, held)
        assertNotNull(repo.refresh())
        assertEquals(1, downloads)
        assertEquals(built, held.checkedAt)
        assertEquals(14, held.all.size)
        assertTrue(held.at(morgan, now).isNotEmpty())
    }

    @Test
    fun oneDownloadLastsThreeMinutes() {
        repo.refresh()
        now += AlertsRepository.FRESH_FOR - 1
        assertNull(repo.refresh())
        assertEquals(1, downloads)
        now += 1
        assertNotNull(repo.refresh())
        assertEquals(2, downloads)
        assertEquals(now, held.checkedAt)
    }

    @Test
    fun theRefreshButtonAsksAtMostOnceAMinute() {
        repo.refresh()
        now += 59
        assertNull(repo.refresh(force = true))
        assertNull(repo.refresh(force = true))
        assertEquals(1, downloads)
        now += 1
        assertNotNull(repo.refresh(force = true))
        assertEquals(2, downloads)
    }

    @Test
    fun aFailedDownloadKeepsTheAlertsAndBacksOff() {
        repo.refresh()
        val good = held

        offline()
        now += AlertsRepository.FRESH_FOR
        repo.refresh()
        assertEquals(2, downloads)
        assertSame(good, held)
        assertTrue(held.at(morgan, now).isNotEmpty())

        // Two minutes after the first failure, four after the second.
        now += 119
        assertNull(repo.refresh())
        now += 1
        repo.refresh()
        assertEquals(3, downloads)
        now += 239
        assertNull(repo.refresh())
        now += 1
        repo.refresh()
        assertEquals(4, downloads)
        assertSame(good, held)
    }

    @Test
    fun theWaitStopsDoublingAtFourMinutes() {
        offline()
        repo.refresh()
        val waits = (1..6).map {
            var waited = 0L
            do {
                now += 60
                waited += 60
            } while (repo.refresh() == null)
            waited
        }
        assertEquals(listOf(120L, 240L, 240L, 240L, 240L, 240L), waits)
    }

    @Test
    fun aGoodDownloadEndsTheBackOff() {
        offline()
        repo.refresh()
        now += 120
        repo.refresh()
        answer = { Fixtures.alertBytes }
        now += 240
        repo.refresh()
        assertEquals(3, downloads)
        assertEquals(14, held.all.size)

        offline()
        now += AlertsRepository.FRESH_FOR
        repo.refresh()
        now += 120
        repo.refresh()
        assertEquals(5, downloads)
    }

    @Test
    fun theRefreshButtonCutsABackOffShort() {
        offline()
        repo.refresh()
        now += 59
        assertNull(repo.refresh(force = true))
        now += 1
        assertNotNull(repo.refresh(force = true))
        assertEquals(2, downloads)
    }

    @Test
    fun aWebPageInsteadOfTheFeedChangesNothingAndCountsAsAFailure() {
        answer = { "<html><body>Sign in to Wi-Fi</body></html>".toByteArray() }
        repo.refresh()
        assertSame(Alerts.NONE, held)
        // A good download would not be asked for again this soon. A failed one is.
        now += AlertsRepository.RETRY_AFTER
        assertNotNull(repo.refresh())
        assertEquals(2, downloads)
        assertSame(Alerts.NONE, held)

        answer = { Fixtures.alertBytes }
        now += 240
        repo.refresh()
        val good = held
        assertEquals(14, good.all.size)

        answer = { "{\"message\":\"Forbidden\"}".toByteArray() }
        now += AlertsRepository.FRESH_FOR
        repo.refresh()
        assertEquals(4, downloads)
        assertSame(good, held)
    }

    @Test
    fun twoScreensAskingWhileOneDownloadIsInFlightShareIt() {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            Fixtures.alertBytes
        }
        val first = repo.refresh()
        val second = repo.refresh()
        val pressed = repo.refresh(force = true)
        assertNotNull(first)
        assertSame(first, second)
        assertSame(first, pressed)
        assertEquals(1, downloads)
        assertSame(Alerts.NONE, held)

        gate.complete(Unit)
        assertTrue(first.isCompleted)
        assertEquals(14, held.all.size)
    }

    @Test
    fun aScreenGoingAwayDoesNotCancelTheDownloadItStarted() {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            Fixtures.alertBytes
        }
        runBlocking {
            val screen = launch(start = CoroutineStart.UNDISPATCHED) {
                repo.refresh()
                awaitCancellation()
            }
            screen.cancelAndJoin()
        }
        assertEquals(1, downloads)
        assertSame(Alerts.NONE, held)

        gate.complete(Unit)
        assertEquals(14, held.all.size)
    }

    @Test
    fun aDownloadStuckForOverAMinuteIsReplacedAndWritesNothing() {
        val stuck = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        answer = {
            if (downloads == 1) {
                // A socket that does not notice it was given up on, and answers much later.
                withContext(NonCancellable) { stuck.await() }
                Fixtures.alertBytes
            } else {
                second.await()
                throw IOException("no signal")
            }
        }
        val first = repo.refresh()
        assertNotNull(first)
        now += AlertsRepository.STUCK_AFTER
        assertSame(first, repo.refresh())
        assertEquals(1, downloads)

        now += 1
        val replacement = repo.refresh()
        assertNotNull(replacement)
        assertNotSame(first, replacement)
        assertTrue(first.isCancelled)
        assertEquals(2, downloads)

        // The first download comes back with a whole, good feed. It is too late to count.
        stuck.complete(Unit)
        assertTrue(first.isCompleted)
        assertSame(Alerts.NONE, held)

        // Only the replacement's failure was counted: the next wait is two minutes, not four.
        second.complete(Unit)
        assertSame(Alerts.NONE, held)
        now += AlertsRepository.RETRY_AFTER
        assertNotNull(repo.refresh())
        assertEquals(3, downloads)
    }

    @Test
    fun aClockSetBackDoesNotFreezeTheCache() {
        repo.refresh()
        now -= 1000
        assertNotNull(repo.refresh())
        assertEquals(2, downloads)
        assertEquals(now, held.checkedAt)
    }

    @Test
    fun trainsAndAlertsFailSeparately() {
        val trainsAt = Fixtures.lFeed.timestamp
        val trains = ArrivalsRepository(download = { Fixtures.lFeedBytes }, clock = { trainsAt })
        val noTrains = ArrivalsRepository(download = { throw IOException("no signal") }, clock = { trainsAt })

        offline()
        repo.refresh()
        val board = runBlocking { trains.board(morgan, Fixtures.catalog) }
        assertTrue(board.arrivals.isNotEmpty())
        assertFalse(board.failed)
        assertFalse(board.stale)
        assertSame(Alerts.NONE, held)

        answer = { Fixtures.alertBytes }
        now += AlertsRepository.RETRY_AFTER
        repo.refresh()
        assertTrue(runBlocking { noTrains.board(morgan, Fixtures.catalog) }.failed)
        assertTrue(held.at(morgan, now).isNotEmpty())
    }
}
