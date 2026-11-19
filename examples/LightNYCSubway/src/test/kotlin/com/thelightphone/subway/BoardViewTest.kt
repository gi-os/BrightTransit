package com.thelightphone.subway

import com.thelightphone.subway.data.Arrival
import com.thelightphone.subway.data.ArrivalsRepository.Companion.KEEP_FOR
import com.thelightphone.subway.data.ArrivalsRepository.Companion.STALE_AFTER
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Board.Source
import com.thelightphone.subway.data.Board.View
import com.thelightphone.subway.data.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a board says, case by case. The clock here starts at the moment the MTA built the feed. */
class BoardViewTest {
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val built = 1_000_000L

    private fun train(minutes: Int, route: String = "L", feed: String = "") =
        Arrival(route, Direction.NORTH, built + minutes * 60L, "West Side", "8 Av", feed = feed)

    /** A board with one train three minutes off and one twenty-five minutes off. */
    private val live = Board(unionSq, listOf(train(3), train(25)), asOf = built)

    @Test
    fun timesFromAMinuteAgoAreShownAsLive() {
        val view = assertIs<View.Trains>(live.view(built + 60))
        assertFalse(view.stale)
        assertEquals(listOf("West Side"), view.groups.map { it.heading })
        assertEquals(listOf(2, 24), view.groups.single().arrivals.map { it.minutesFrom(built + 60) })
        assertNull(view.missingNote)
    }

    @Test
    fun beforeTheFirstAnswerTheBoardSaysLoading() {
        assertSame(View.Loading, Board(unionSq, loading = true).view(built))
    }

    @Test
    fun aLoadThatFailedSaysUnavailable() {
        assertSame(View.Unavailable, Board(unionSq, failed = true).view(built))
    }

    @Test
    fun noTrainsIsSaidOnlyFromLiveTimesThatCoverEveryLine() {
        val quiet = Board(unionSq, asOf = built)
        assertSame(View.NoTrains, quiet.view(built + STALE_AFTER))
        // One second older and the tool no longer knows that nothing is coming.
        assertSame(View.Unavailable, quiet.view(built + STALE_AFTER + 1))
        assertSame(View.Unavailable, quiet.copy(stale = true).view(built))
        assertSame(View.Unavailable, quiet.copy(missing = listOf("4", "5", "6")).view(built))
    }

    @Test
    fun timesFromAFailedDownloadAreMarkedNotLive() {
        assertTrue(assertIs<View.Trains>(live.copy(stale = true).view(built + 30)).stale)
    }

    @Test
    fun timesLeftInMemoryGoStaleOnTheirOwn() {
        // Nothing failed. The board was simply not refreshed: the phone was in a pocket.
        assertFalse(assertIs<View.Trains>(live.view(built + STALE_AFTER)).stale)
        assertTrue(assertIs<View.Trains>(live.view(built + STALE_AFTER + 1)).stale)
    }

    @Test
    fun whenEveryRememberedTrainHasLeftAnOldBoardIsUnavailableNotEmpty() {
        val oneTrain = Board(unionSq, listOf(train(3)), asOf = built)
        // Four minutes on, the train has gone and the times are too old to know what follows it.
        assertSame(View.Unavailable, oneTrain.view(built + 240))
        assertSame(View.Unavailable, oneTrain.copy(stale = true).view(built + 240))
    }

    @Test
    fun pastTwentyMinutesNothingIsShownEvenWithTrainsStillAhead() {
        val view = assertIs<View.Trains>(live.view(built + KEEP_FOR))
        assertEquals(listOf(5), view.groups.single().arrivals.map { it.minutesFrom(built + KEEP_FOR) })
        assertSame(View.Unavailable, live.view(built + KEEP_FOR + 1))
    }

    @Test
    fun linesWithNoTimesAreNamedAboveTheOnesThatHaveThem() {
        val view = assertIs<View.Trains>(live.copy(missing = listOf("4", "5", "6", "N", "Q", "R", "W")).view(built))
        assertEquals("No times for the 4 5 6 N Q R W right now.", view.missingNote)
        assertEquals("No times for the L right now.", view.copy(missing = listOf("L")).missingNote)
    }

    @Test
    fun aBoardWaitingForAnAnswerShowsWhatItHas() {
        // A station opened from Home starts with Home's times while its own are fetched.
        val seeded = live.copy(loading = true)
        assertFalse(assertIs<View.Trains>(seeded.view(built + 25)).stale)
        assertTrue(assertIs<View.Trains>(seeded.view(built + 300)).stale)
    }

    @Test
    fun aBoardWaitingForAnAnswerWithNothingItCanShowSaysLoading() {
        // Reopened after an hour, or with every train gone: the answer is on its way, so
        // this is not yet the time to blame the connection.
        assertSame(View.Loading, live.copy(loading = true).view(built + 3600))
        assertSame(View.Loading, Board(unionSq, listOf(train(3)), asOf = built, loading = true).view(built + 240))
        assertSame(View.Loading, Board(unionSq, failed = true, loading = true).view(built))
    }

    @Test
    fun theSentencesAreTheOnesRidersKnow() {
        assertEquals("Loading…", View.Loading.text)
        assertEquals("Times unavailable. Check your connection.", View.Unavailable.text)
        assertEquals("No trains due right now.", View.NoTrains.text)
    }

    @Test
    fun aFailedLoadLeavesTheTrainsOnScreenMarkedStale() {
        val failed = Board(unionSq, failed = true, missing = unionSq.routes)
        val kept = live.copy(loading = true).replacedBy(failed, built + 600)
        assertEquals(live.arrivals, kept.arrivals)
        assertTrue(kept.stale)
        assertFalse(kept.failed)
        assertFalse(kept.loading)
        assertTrue(assertIs<View.Trains>(kept.view(built + 600)).stale)
    }

    @Test
    fun aFailedLoadDoesNotKeepTrainsPastTwentyMinutes() {
        val failed = Board(unionSq, failed = true)
        assertTrue(live.replacedBy(failed, built + KEEP_FOR).stale)
        assertSame(failed, live.replacedBy(failed, built + KEEP_FOR + 1))
        assertSame(View.Unavailable, live.replacedBy(failed, built + KEEP_FOR + 1).view(built + KEEP_FOR + 1))
    }

    @Test
    fun trainsKeptThroughAFailedLoadStayMarkedWhenOneOfTheirFeedsGrowsTooOld() {
        // Union Sq with the N Q R W feed nineteen minutes old and the L feed new.
        val sources = listOf(Source("gtfs", built), Source("gtfs-l", built), Source("gtfs-nqrw", built - 19 * 60))
        val trains = listOf(train(3, "L", "gtfs-l"), train(4, "N", "gtfs-nqrw"), train(6, "4", "gtfs"))
        val kept = Board.of(unionSq, trains, sources, built).replacedBy(Board(unionSq, failed = true), built + 30)
        assertTrue(assertIs<View.Trains>(kept.view(built + 30)).stale)

        // The N goes when its feed passes twenty minutes. The L and the 4 are a minute
        // old, and still from before a load that failed.
        val later = assertIs<View.Trains>(kept.view(built + 61))
        assertEquals(listOf("4", "L"), later.groups.single().rows.map { it.route })
        assertTrue(later.stale)
        assertEquals("No times for the N Q R W right now.", later.missingNote)
    }

    @Test
    fun aBoardWithNoFeedThatCountsHasNothingToShow() {
        // Seneca Av is on the M. Only the A C E feed, which it is also looked for in, was had.
        val seneca = Fixtures.station("Seneca Av")
        val board = Board.of(seneca, emptyList(), listOf(Source("gtfs-ace", built), Source("gtfs-bdfm", null)), built)
        assertTrue(board.failed)
        assertEquals(listOf("M"), board.missing)
        assertSame(View.Unavailable, board.view(built))
    }

    @Test
    fun anAnswerWithTimesAlwaysReplacesTheBoard() {
        val fresh = Board(unionSq, listOf(train(12)), asOf = built + 300)
        assertSame(fresh, live.replacedBy(fresh, built + 300))
        // A failure has nothing to keep when the board never had trains.
        val failed = Board(unionSq, failed = true)
        assertSame(failed, Board(unionSq, loading = true).replacedBy(failed, built))
    }
}
