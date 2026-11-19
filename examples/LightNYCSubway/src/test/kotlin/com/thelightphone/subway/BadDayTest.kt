package com.thelightphone.subway

import com.thelightphone.subway.data.Board.View
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The days this tool is opened on: tunnels, pockets, weak signal. Each test is one of them
 * played through with the real feed cache and refresh loop, and says what is on the page at
 * each step. The rule they all check is the same: the page never states something it does
 * not know.
 *
 * The station is Morgan Av on the captured L feed. At the start the next trains are
 * 2, 9, 17, 25 and 33 minutes off toward Manhattan and 5 to 43 minutes off toward Brooklyn.
 */
class BadDayTest {
    private val phone = TestPhone()
    private val morgan = Fixtures.station("Morgan Av")
    private val unionSq = Fixtures.station("14 St-Union Sq")

    private val asCaptured = mapOf("Manhattan" to listOf(2, 9, 17, 25, 33), "Brooklyn" to listOf(5, 12, 19, 27, 35, 43))

    private fun LiveBoards.minutes() = says().minutes(now.value)

    private fun LiveBoards.trains() = assertIs<View.Trains>(says())

    /** A download that hangs until the test ends it, the way one does on a single bar of signal. */
    private fun weakSignal(): CompletableDeferred<ByteArray> =
        CompletableDeferred<ByteArray>().also { gate -> phone.trains = { gate.await() } }

    @Test
    fun anHourWithNoSignalAndThenItComesBack() {
        val screen = phone.screen(morgan)
        screen.resume()
        assertFalse(screen.trains().stale)
        assertEquals(asCaptured, screen.minutes())

        // Into the tunnel. After a minute the times are still up, counting down, marked not live.
        phone.offline()
        phone.elapse(60)
        assertTrue(screen.trains().stale)
        assertEquals(mapOf("Manhattan" to listOf(1, 8, 16, 24, 32), "Brooklyn" to listOf(4, 11, 18, 26, 34, 42)), screen.minutes())

        // Ten minutes: the trains that have gone are off the page, the rest still marked.
        phone.elapse(9 * 60)
        assertTrue(screen.trains().stale)
        assertEquals(mapOf("Manhattan" to listOf(7, 15, 23), "Brooklyn" to listOf(2, 9, 17, 25, 33)), screen.minutes())

        // Nineteen minutes, and the same.
        phone.elapse(9 * 60)
        assertTrue(screen.trains().stale)
        assertEquals(mapOf("Manhattan" to listOf(6, 14), "Brooklyn" to listOf(0, 8, 16, 24)), screen.minutes())

        // Twenty-one minutes. Trains are still ahead on paper, and the page stops showing
        // them: nobody should plan around a time that old. Twenty-five, the same.
        phone.elapse(2 * 60)
        assertSame(View.Unavailable, screen.says())
        phone.elapse(4 * 60)
        assertSame(View.Unavailable, screen.says())

        // The rest of the hour, with the tool left open. Every remembered train leaves, and
        // at no tick does the page claim there are no trains.
        repeat(35 * 6) {
            phone.elapse(10)
            assertSame(View.Unavailable, screen.says())
        }
        assertEquals(2, phone.waiting)

        // Out of the tunnel: the next pass brings the board back, live.
        phone.online()
        phone.elapse(30)
        assertFalse(screen.trains().stale)
        assertEquals(asCaptured, screen.minutes())
    }

    @Test
    fun thePhoneSleepsTenMinutesBetweenTwoTicks() {
        val screen = phone.screen(morgan)
        screen.resume()
        val shown = phone.now
        phone.elapse(14)
        assertEquals(shown + 10, screen.now.value)

        // Asleep in a pocket with the tick six seconds away. A wait does not count sleep,
        // so on waking it is still six seconds away, and the clock it would set is ten
        // minutes behind.
        phone.sleep(600)
        assertEquals(shown + 10, screen.now.value)

        // Waking shows the screen again, and that alone puts the clock right: before the
        // tick, and before the download comes back.
        val download = weakSignal()
        screen.resume()
        assertEquals(phone.now, screen.now.value)
        // The times from before the sleep are counted from now, less the trains that left,
        // and are marked not live. The train that read "9 min" is not on the page at all.
        assertTrue(screen.trains().stale)
        assertEquals(mapOf("Manhattan" to listOf(6, 14, 23), "Brooklyn" to listOf(2, 9, 17, 25, 33)), screen.minutes())

        download.complete(phone.feedNow())
        assertFalse(screen.trains().stale)
        assertEquals(asCaptured, screen.minutes())

        // The tick left over from before the sleep went with the old loop.
        phone.elapse(6)
        assertEquals(phone.now - 6, screen.now.value)
        assertEquals(2, phone.waiting)
    }

    @Test
    fun reopenedAfterAnHourItSaysLoadingAndNeverNoTrains() {
        val screen = phone.screen(morgan)
        screen.resume()
        screen.pause()
        phone.sleep(3600)

        // Every train it remembers has left. That is not the same as no trains running.
        val download = weakSignal()
        screen.resume()
        assertSame(View.Loading, screen.says())

        download.completeExceptionally(IOException("no signal"))
        assertSame(View.Unavailable, screen.says())

        phone.online()
        phone.elapse(30)
        assertEquals(asCaptured, screen.minutes())
    }

    @Test
    fun oneFeedOfThreeDoesNotArriveOnTheFirstLoad() {
        phone.trains = { url -> if (url.endsWith("gtfs-l")) phone.feedNow() else throw IOException("timed out") }
        val screen = phone.screen(unionSq)
        screen.resume()

        // The L times are good and are shown as live. The lines that are not there are named.
        val first = screen.trains()
        assertFalse(first.stale)
        assertEquals(listOf("West Side", "Brooklyn"), first.groups.map { it.heading })
        assertEquals("No times for the 4 5 6 N Q R W right now.", first.missingNote)

        phone.online()
        phone.elapse(30)
        assertNull(screen.trains().missingNote)
    }

    @Test
    fun aStationOpenedFromHomeInADeadZoneShowsWhatHomeHadAtOnce() {
        val home = phone.screen(morgan)
        home.resume()
        phone.offline()
        phone.elapse(180)
        home.pause()

        // The station page asks the network, which will take its time to say no.
        val download = weakSignal()
        val page = phone.screen()
        page.resume()
        page.following(morgan)
        assertTrue(page.trains().stale)
        assertEquals(mapOf("Manhattan" to listOf(6, 14, 22, 30), "Brooklyn" to listOf(2, 9, 16, 24, 32, 40)), page.minutes())

        download.completeExceptionally(IOException("timed out"))
        assertTrue(page.trains().stale)
    }

    @Test
    fun aStationOpenedAMinuteIntoADeadZoneIsNotLiveThereEither() {
        val home = phone.screen(morgan)
        home.resume()
        phone.offline()
        phone.elapse(60)
        assertTrue(home.trains().stale)
        val onHome = home.minutes()
        home.pause()

        // The copy is a minute old, which by its age alone would still pass for live. Home
        // has seen two downloads fail since, and the page starts from what Home knew.
        val download = weakSignal()
        val page = phone.screen()
        page.resume()
        page.following(morgan)
        assertTrue(page.trains().stale)
        assertEquals(onHome, page.minutes())

        download.completeExceptionally(IOException("timed out"))
        assertTrue(page.trains().stale)
    }

    @Test
    fun aWifiSignInPageIsNotMistakenForTrains() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.trains = { "<html><body>Sign in to Wi-Fi</body></html>".toByteArray() }
        phone.elapse(60)
        assertTrue(screen.trains().stale)

        // The same page on a phone that has never had a feed.
        val other = TestPhone()
        other.trains = phone.trains
        val fresh = other.screen(morgan)
        fresh.resume()
        assertSame(View.Unavailable, fresh.says())
    }

    @Test
    fun theClockIsSetBackFiveMinutesAndTheBoardKeepsRefreshing() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.setClockBack(300)

        phone.elapse(30)
        assertEquals(2, phone.trainDownloads)
        assertEquals(phone.now, screen.now.value)
        assertFalse(screen.trains().stale)
        assertEquals(asCaptured, screen.minutes())
        phone.elapse(30)
        assertEquals(3, phone.trainDownloads)
    }

    @Test
    fun whileTheScreenIsUpNothingOnItIsEverLoadingAgain() {
        val screen = phone.screen(morgan)
        screen.resume()
        phone.offline()
        repeat(30 * 6) {
            phone.elapse(10)
            assertNotSame(View.Loading, screen.says())
            assertNotSame(View.NoTrains, screen.says())
        }
    }
}
