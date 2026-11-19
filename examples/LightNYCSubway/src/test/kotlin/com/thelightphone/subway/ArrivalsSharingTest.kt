package com.thelightphone.subway

import com.thelightphone.subway.data.ArrivalsRepository
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Feeds
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the train feeds are shared between the screens that want them, and what a board is
 * told when one of its feeds cannot be had.
 */
class ArrivalsSharingTest {
    private val morgan = Fixtures.station("Morgan Av")
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private var now = Fixtures.lFeed.timestamp
    private var downloads = 0

    /** What the MTA sends back for a feed, given its address. */
    private var answer: suspend (String) -> ByteArray = { Fixtures.lFeedBytes }

    /** Unconfined runs a download on the test's own thread, up to the first place it has to wait. */
    private val repo = ArrivalsRepository(
        download = {
            downloads++
            answer(it)
        },
        clock = { now },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun board(force: Boolean = false) = runBlocking { repo.board(morgan, Fixtures.catalog, force) }

    private fun offline() {
        answer = { throw IOException("no signal") }
    }

    private val day = Fixtures.weekdayAfternoon

    /** The feed an address is for, as it was recorded that afternoon. */
    private fun recorded(url: String): ByteArray = day.bytes.getValue(url.substringAfterLast("%2F"))

    /** The whole system as it was recorded, at the moment it was, with the feeds named in [down] timing out. */
    private fun thatAfternoon(vararg down: String) {
        now = day.time
        answer = { url -> if (down.any { url.endsWith(it) }) throw IOException("timed out") else recorded(url) }
    }

    @Test
    fun screensAskingWhileADownloadIsInFlightShareIt() {
        val gate = CompletableDeferred<ByteArray>()
        answer = { gate.await() }
        runBlocking {
            val home = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) }
            val page = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) }
            val pressed = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog, force = true) }
            assertEquals(1, downloads)
            assertFalse(home.isCompleted)

            gate.complete(Fixtures.lFeedBytes)
            listOf(home, page, pressed).awaitAll().forEach {
                assertTrue(it.arrivals.isNotEmpty())
                assertFalse(it.stale)
            }
        }
        assertEquals(1, downloads)
    }

    @Test
    fun aDownloadThatFailsIsOneTimeoutForEveryoneWaitingOnIt() {
        val gate = CompletableDeferred<ByteArray>()
        answer = { gate.await() }
        runBlocking {
            val waiting = List(6) { async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) } }
            gate.completeExceptionally(IOException("timed out"))
            assertTrue(waiting.awaitAll().all { it.failed })
        }
        assertEquals(1, downloads)
    }

    @Test
    fun aFailureSharedByScreensThatHadTimesLeavesThemAllStale() {
        board()
        val gate = CompletableDeferred<ByteArray>()
        answer = { gate.await() }
        now += 60
        runBlocking {
            val waiting = List(3) { async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) } }
            gate.completeExceptionally(IOException("timed out"))
            waiting.awaitAll().forEach {
                assertTrue(it.stale)
                assertTrue(it.arrivals.isNotEmpty())
            }
        }
        assertEquals(2, downloads)
    }

    @Test
    fun aScreenGoingAwayLeavesItsDownloadForTheNextOne() {
        val gate = CompletableDeferred<ByteArray>()
        answer = { gate.await() }
        var answered = false
        runBlocking {
            val screen = launch(start = CoroutineStart.UNDISPATCHED) {
                repo.board(morgan, Fixtures.catalog)
                answered = true
            }
            screen.cancelAndJoin()
            assertTrue(screen.isCancelled)
            assertFalse(answered, "a cancelled screen is not handed a board")

            val next = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) }
            assertEquals(1, downloads)
            gate.complete(Fixtures.lFeedBytes)
            assertTrue(next.await().arrivals.isNotEmpty())
        }
        // And the copy it brought back is there for whoever asks after that.
        now += 5
        assertTrue(board().arrivals.isNotEmpty())
        assertEquals(1, downloads)
    }

    @Test
    fun aDownloadNobodyIsWaitingForStillFillsTheCache() {
        val gate = CompletableDeferred<ByteArray>()
        answer = { gate.await() }
        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) }.cancelAndJoin()
        }
        assertNull(repo.peek(morgan, Fixtures.catalog))
        gate.complete(Fixtures.lFeedBytes)
        assertNotNull(repo.peek(morgan, Fixtures.catalog))
        assertEquals(1, downloads)
    }

    @Test
    fun aSecondPressOfRefreshJoinsTheDownloadTheFirstStarted() {
        board()
        now += 5
        val gate = CompletableDeferred<ByteArray>()
        answer = { gate.await() }
        runBlocking {
            val first = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog, force = true) }
            now += 3
            val second = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog, force = true) }
            assertEquals(2, downloads)
            // Anyone not in a hurry is given the copy in hand, and does not wait.
            assertTrue(repo.board(morgan, Fixtures.catalog).arrivals.isNotEmpty())
            assertFalse(first.isCompleted)

            gate.complete(Fixtures.lFeedBytes)
            listOf(first, second).awaitAll()
        }
        assertEquals(2, downloads)
    }

    @Test
    fun aDownloadThePhoneSleptThroughIsReplacedAndWritesNothing() {
        val stuck = CompletableDeferred<ByteArray>()
        answer = {
            if (downloads == 1) {
                // A socket that does not notice it was given up on, and answers much later.
                withContext(NonCancellable) { stuck.await() }
            } else {
                throw IOException("no signal")
            }
        }
        runBlocking {
            val asleep = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) }
            now += ArrivalsRepository.STUCK_AFTER
            val joined = async(start = CoroutineStart.UNDISPATCHED) { repo.board(morgan, Fixtures.catalog) }
            assertEquals(1, downloads)

            now += 1
            assertTrue(repo.board(morgan, Fixtures.catalog).failed)
            assertEquals(2, downloads)

            // The first download comes back with a whole, good feed. It is too late to count,
            // and the screens that were waiting on it are told it failed, not left hanging.
            stuck.complete(Fixtures.lFeedBytes)
            assertTrue(asleep.await().failed)
            assertTrue(joined.await().failed)
        }
        assertNull(repo.peek(morgan, Fixtures.catalog))
    }

    @Test
    fun aClockSetBackDoesNotFreezeTheCache() {
        board()
        now -= 1000
        assertTrue(board().arrivals.isNotEmpty())
        assertEquals(2, downloads)
        // Nor does it take the refresh button away.
        now -= 1000
        board(force = true)
        assertEquals(3, downloads)
    }

    @Test
    fun withNothingInHandThereIsNothingToPeekAt() {
        assertNull(repo.peek(morgan, Fixtures.catalog))
        assertEquals(0, downloads)
    }

    @Test
    fun aStationOpenedFromHomeHasItsTimesWithoutADownload() {
        val home = board()
        // Too old to be used as is: a screen that asks now has to fetch.
        now += ArrivalsRepository.FRESH_FOR + 5
        val peeked = assertNotNull(repo.peek(morgan, Fixtures.catalog))
        assertEquals(1, downloads)
        assertEquals(home.asOf, peeked.asOf)
        assertTrue(peeked.arrivals.isNotEmpty())
        assertFalse(assertIs<Board.View.Trains>(peeked.view(now)).stale)
    }

    @Test
    fun peekedTimesAreMarkedNotLiveOnceTheyAre() {
        board()
        now += ArrivalsRepository.STALE_AFTER + 1
        val peeked = assertNotNull(repo.peek(morgan, Fixtures.catalog))
        assertTrue(peeked.stale)
        assertTrue(assertIs<Board.View.Trains>(peeked.view(now)).stale)
        assertEquals(1, downloads)
    }

    @Test
    fun timesTooOldToShowAreNotPeekedAtEither() {
        board()
        now += ArrivalsRepository.KEEP_FOR - 1
        assertNotNull(repo.peek(morgan, Fixtures.catalog))
        now += 1
        assertNull(repo.peek(morgan, Fixtures.catalog))
    }

    @Test
    fun aStationIsNotPeekedAtWithOnlySomeOfItsFeedsInHand() {
        // Home had Morgan Av, so the L feed is here. Union Sq also needs two feeds that are not.
        board()
        assertNull(repo.peek(unionSq, Fixtures.catalog))
    }

    @Test
    fun oneFeedOfSeveralFailingNamesTheLinesItTookWithIt() {
        answer = { url -> if (url.endsWith("gtfs-l")) Fixtures.lFeedBytes else throw IOException("timed out") }
        val board = runBlocking { repo.board(unionSq, Fixtures.catalog) }
        assertEquals(3, downloads)
        assertFalse(board.failed)
        assertFalse(board.stale)
        assertTrue(board.arrivals.isNotEmpty())
        assertEquals(setOf("L"), board.arrivals.map { it.route }.toSet())
        assertEquals(listOf("4", "5", "6", "N", "Q", "R", "W"), board.missing)

        val view = assertIs<Board.View.Trains>(board.view(now))
        assertEquals("No times for the 4 5 6 N Q R W right now.", view.missingNote)
    }

    @Test
    fun aFeedThatFailsWithACopyInHandIsOldNotMissing() {
        runBlocking { repo.board(unionSq, Fixtures.catalog) }
        answer = { url -> if (url.endsWith("gtfs-l")) Fixtures.lFeedBytes else throw IOException("timed out") }
        now += 60
        val board = runBlocking { repo.board(unionSq, Fixtures.catalog) }
        assertTrue(board.stale)
        assertEquals(emptyList(), board.missing)
    }

    @Test
    fun aPeekSaysTheLastDownloadFailedUntilOneSucceeds() {
        board()
        offline()
        now += 30
        assertTrue(board().stale)
        // The copy is half a minute old, which by its age alone would pass for live.
        val peeked = assertNotNull(repo.peek(morgan, Fixtures.catalog))
        assertTrue(peeked.stale)
        assertTrue(assertIs<Board.View.Trains>(peeked.view(now)).stale)

        answer = { Fixtures.lFeedBytes }
        now += 30
        assertFalse(board().stale)
        assertFalse(assertNotNull(repo.peek(morgan, Fixtures.catalog)).stale)
    }

    @Test
    fun aCopyFromBeforeAFailedRefreshIsNotHandedOutAsLive() {
        board()
        now += 5
        offline()
        assertTrue(board(force = true).stale)
        // Seconds later another screen asks. The copy is new enough to be used as is, and
        // it is still the one from before the download that failed.
        now += 5
        assertTrue(board().stale)
        assertEquals(2, downloads)
    }

    @Test
    fun aLineWhoseFeedFailedIsNamedMissingEvenWithAStrayTrainInAnotherFeed() {
        // That afternoon one E, bound for Kings Hwy down the F line, rode in the B D F M
        // feed and called at W 4 St. Every other E was in the A C E feed.
        val w4 = Fixtures.station("W 4 St-Wash Sq")
        val strays = day.board(w4).arrivals.filter { it.route == "E" && it.feed == "gtfs-bdfm" }
        assertEquals(listOf("Kings Hwy"), strays.map { it.destination })

        thatAfternoon("gtfs-ace")
        val board = runBlocking { repo.board(w4, Fixtures.catalog) }
        assertEquals(listOf("A", "C", "E"), board.missing)
        // The stray is not left on the board to pass for the next E.
        assertEquals(setOf("B", "D", "F", "M"), board.arrivals.map { Routes.plain(it.route) }.toSet())
        val view = assertIs<Board.View.Trains>(board.view(now))
        assertFalse(view.stale)
        assertEquals("No times for the A C E right now.", view.missingNote)
        assertTrue(view.groups.flatMap { it.rows }.none { it.route == "E" })
    }

    @Test
    fun aStationWhoseOnlyLineIsMissingNeverSaysNoTrains() {
        // Jamaica-Van Wyck is on the E alone. The same stray E was due there in three minutes.
        val vanWyck = Fixtures.station("Jamaica-Van Wyck")
        val stray = day.board(vanWyck).arrivals.single { it.feed == "gtfs-bdfm" }
        assertEquals("E", stray.route)

        thatAfternoon("gtfs-ace")
        val board = runBlocking { repo.board(vanWyck, Fixtures.catalog) }
        assertEquals(listOf("E"), board.missing)
        assertTrue(board.arrivals.isEmpty())
        // Unavailable while that train is still ahead, and no less so once it has gone.
        listOf(now, now + 20, stray.epochSeconds + 31, stray.epochSeconds + 60, now + 600).forEach { later ->
            assertEquals(Board.View.Unavailable, board.view(later))
        }
    }

    @Test
    fun aFeedNoneOfTheStationsLinesRideInDoesNotMarkItsBoard() {
        // Seneca Av is on the M alone, and is looked for in the A C E feed all the same.
        val seneca = Fixtures.station("Seneca Av")
        assertEquals(listOf("gtfs-ace", "gtfs-bdfm"), Feeds.slugsFor(seneca))
        thatAfternoon()
        runBlocking { repo.board(seneca, Fixtures.catalog) }

        // On the next pass the A C E feed times out, and the copy from the pass before is used.
        thatAfternoon("gtfs-ace")
        now += 30
        val board = runBlocking { repo.board(seneca, Fixtures.catalog) }
        assertEquals(setOf("M"), board.arrivals.map { it.route }.toSet())
        assertFalse(board.stale)
        assertEquals(day.feeds.getValue("gtfs-bdfm").timestamp, board.asOf)
        assertEquals(emptyList(), board.missing)
        assertFalse(assertIs<Board.View.Trains>(board.view(now)).stale)
    }

    @Test
    fun theFeedAShuttleDoesNotRideInDoesNotMarkItsBoard() {
        val parkPl = Fixtures.station("Park Pl", "S")
        fun shuttle() = GtfsRealtime.Feed(
            timestamp = now,
            trips = listOf(
                GtfsRealtime.Trip("FS", listOf(GtfsRealtime.StopTime("S03N", now + 240), GtfsRealtime.StopTime("S01N", now + 420))),
            ),
        )
        answer = { url -> TrainFeeds.bytes(if (url.endsWith("gtfs-bdfm")) shuttle() else GtfsRealtime.Feed(now, emptyList())) }
        runBlocking { repo.board(parkPl, Fixtures.catalog) }

        // The shuttle's feed arrives again. The other one fails, and its copy is old.
        now += 30
        answer = { url -> if (url.endsWith("gtfs-bdfm")) TrainFeeds.bytes(shuttle()) else throw IOException("timed out") }
        val board = runBlocking { repo.board(parkPl, Fixtures.catalog) }
        assertEquals(listOf("S"), board.arrivals.map { it.route })
        assertFalse(board.stale)
        assertEquals(now, board.asOf)
        assertEquals(emptyList(), board.missing)
    }

    @Test
    fun aFeedTheMtaHasStoppedBuildingTakesOnlyItsOwnLinesWithIt() {
        // Every download succeeds. The N Q R W feed that comes back was built 25 minutes ago.
        thatAfternoon()
        val frozen = TrainFeeds.bytes(day.feeds.getValue("gtfs-nqrw").copy(timestamp = now - 25 * 60))
        answer = { url -> if (url.endsWith("gtfs-nqrw")) frozen else recorded(url) }
        val board = runBlocking { repo.board(unionSq, Fixtures.catalog) }
        assertEquals(listOf("N", "Q", "R", "W"), board.missing)
        assertFalse(board.stale)

        // The L and the 4 5 6 are shown, and shown as live.
        val view = assertIs<Board.View.Trains>(board.view(now))
        assertFalse(view.stale)
        assertEquals("No times for the N Q R W right now.", view.missingNote)
        assertEquals(setOf("4", "5", "6", "L"), view.groups.flatMap { it.rows }.map { Routes.plain(it.route) }.toSet())

        // The same feed failing outright says the same thing.
        val other = ArrivalsRepository(
            download = { url -> if (url.endsWith("gtfs-nqrw")) throw IOException("timed out") else recorded(url) },
            clock = { now },
            dispatcher = Dispatchers.Unconfined,
        )
        val failed = runBlocking { other.board(unionSq, Fixtures.catalog) }
        assertEquals(board.view(now), failed.view(now))
    }

    @Test
    fun aFeedBuiltNineteenMinutesAgoIsStillShownAndMarked() {
        thatAfternoon()
        val slow = TrainFeeds.bytes(day.feeds.getValue("gtfs-nqrw").copy(timestamp = now - 19 * 60))
        answer = { url -> if (url.endsWith("gtfs-nqrw")) slow else recorded(url) }
        val board = runBlocking { repo.board(unionSq, Fixtures.catalog) }
        // It put trains on this board, so the board as a whole is not live.
        val shown = assertIs<Board.View.Trains>(board.view(now))
        assertTrue(shown.stale)
        assertEquals(now - 19 * 60, shown.asOf)
        assertNull(shown.missingNote)
        assertTrue(shown.groups.flatMap { it.rows }.any { it.route == "N" })

        // A minute on, with the board still on screen and no new answer, that feed is too
        // old to show. Its lines go and are named, and the others are live again.
        val later = assertIs<Board.View.Trains>(board.view(now + 61))
        assertFalse(later.stale)
        assertEquals("No times for the N Q R W right now.", later.missingNote)
        assertEquals(setOf("4", "5", "6", "L"), later.groups.flatMap { it.rows }.map { Routes.plain(it.route) }.toSet())
        assertEquals(minOf(day.feeds.getValue("gtfs").timestamp, day.feeds.getValue("gtfs-l").timestamp), later.asOf)
    }

    @Test
    fun anOldCopyWithNoTrainsInItIsUnavailableNotNoTrains() {
        // The L feed arrives with no trains in it, and that is live word that none are due.
        val quiet = TrainFeeds.bytes(Fixtures.lFeed.copy(trips = emptyList()))
        answer = { quiet }
        assertEquals(Board.View.NoTrains, board().view(now))

        // The next download fails. The copy in hand can no longer say so.
        offline()
        now += 30
        val old = board()
        assertTrue(old.stale)
        assertEquals(Board.View.Unavailable, old.view(now))
    }

    @Test
    fun everyFeedFailingIsAFailedBoardWithEveryLineMissing() {
        offline()
        val board = runBlocking { repo.board(unionSq, Fixtures.catalog) }
        assertTrue(board.failed)
        assertEquals(unionSq.routes, board.missing)
        assertEquals(Board.View.Unavailable, board.view(now))
    }

    @Test
    fun aShuttleIsNotCalledMissingWhileItsTrainsAreOnTheBoard() {
        // Park Pl is on the Franklin Av shuttle, which is looked for in two feeds and rides
        // in one. Here the one that carries it arrives and the other does not.
        val parkPl = Fixtures.station("Park Pl", "S")
        val shuttle = GtfsRealtime.Feed(
            timestamp = now,
            trips = listOf(
                GtfsRealtime.Trip("FS", listOf(GtfsRealtime.StopTime("S03N", now + 240), GtfsRealtime.StopTime("S01N", now + 420))),
            ),
        )
        answer = { url -> if (url.endsWith("gtfs-bdfm")) TrainFeeds.bytes(shuttle) else throw IOException("timed out") }
        val running = runBlocking { repo.board(parkPl, Fixtures.catalog) }
        assertEquals(listOf("S"), running.arrivals.map { it.route })
        assertEquals(emptyList(), running.missing)

        // With no shuttle listed, the feed that did not arrive may be the one that has it.
        answer = { url -> if (url.endsWith("gtfs-bdfm")) TrainFeeds.bytes(shuttle.copy(trips = emptyList())) else throw IOException("timed out") }
        now += ArrivalsRepository.FRESH_FOR
        val unknown = runBlocking { repo.board(parkPl, Fixtures.catalog) }
        assertEquals(listOf("S"), unknown.missing)
        assertEquals(Board.View.Unavailable, unknown.view(now))
    }

    @Test
    fun theFeedWriterWritesWhatTheReaderReads() {
        assertEquals(Fixtures.lFeed, GtfsRealtime.parse(TrainFeeds.bytes(Fixtures.lFeed)))
        val later = GtfsRealtime.parse(TrainFeeds.lFeedLater(90))
        assertEquals(Fixtures.lFeed.timestamp + 90, later.timestamp)
        assertEquals(Fixtures.lFeed.trips.first().stops.first().time + 90, later.trips.first().stops.first().time)
    }
}
