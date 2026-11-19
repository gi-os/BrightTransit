package com.thelightphone.subway

import com.thelightphone.subway.ByHand.JEFFERSON
import com.thelightphone.subway.ByHand.MORGAN
import com.thelightphone.subway.ByHand.T0
import com.thelightphone.subway.ByHand.UNION_SQ
import com.thelightphone.subway.ByHand.feed
import com.thelightphone.subway.ByHand.seen
import com.thelightphone.subway.ByHand.trip
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Onward
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.BoardTrouble
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.notLive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nothing that goes wrong, and nothing that ends, is left without a next step: what a page
 * says when its times could not be had, and what a ride offers when it is over or lost.
 */
class NextStepsTest {
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val timesSq = Fixtures.station("Times Sq-42 St")

    private fun trains(stale: Boolean = false, missing: List<String> = emptyList()) =
        Board.View.Trains(groups = emptyList(), stale = stale, missing = missing, asOf = T0)

    @Test
    fun aBoardThatCouldNotGetEverythingSaysSoInPlainWordsAndNamesTheConnection() {
        assertNull(BoardTrouble.of(trains()))
        assertEquals("${notLive(T0)} Check your connection.", BoardTrouble.of(trains(stale = true)))
        assertEquals("No times for the E right now. Check your connection.", BoardTrouble.of(trains(missing = listOf("E"))))
        assertEquals("${notLive(T0)} No times for the E right now. Check your connection.", BoardTrouble.of(trains(stale = true, missing = listOf("E"))))
    }

    @Test
    fun overAListOfBoardsOneLineSaysWhatIsWrongAndNeverTheEmptyState() {
        // Nothing wrong, and boards still loading, say nothing.
        assertNull(BoardTrouble.over(emptyList()))
        assertNull(BoardTrouble.over(listOf(trains(), Board.View.NoTrains)))
        assertNull(BoardTrouble.over(listOf(Board.View.Loading, Board.View.Loading)))
        // Every board without its times: the times are unavailable, which is the page's whole story.
        assertEquals(Board.View.Unavailable.text, BoardTrouble.over(listOf(Board.View.Unavailable, Board.View.Unavailable, Board.View.Loading)))
        // Every board old or short of a line: none of the times are live.
        assertEquals("Times are not live. Check your connection.", BoardTrouble.over(listOf(trains(stale = true), trains(missing = listOf("E")))))
        assertEquals("Times are not live. Check your connection.", BoardTrouble.over(listOf(trains(stale = true), Board.View.Unavailable)))
        // Some: the rest are still shown, and the line says only some are not live.
        assertEquals("Some times are not live. Check your connection.", BoardTrouble.over(listOf(trains(), Board.View.Unavailable)))
        assertEquals("Some times are not live. Check your connection.", BoardTrouble.over(listOf(trains(), trains(stale = true))))
        // The words are short and plain, with no colon in a sentence.
        listOf(Board.View.Unavailable.text, BoardTrouble.over(listOf(trains(), Board.View.Unavailable))!!).forEach { assertTrue(':' !in it, it) }
    }

    @Test
    fun aRideThatIsOverOrLostOffersTheNextThingToDo() {
        val coming = seen(feed(T0, trip(JEFFERSON, T0 + 30)))
        val plain = Ride(coming, MORGAN, UNION_SQ)
        val changing = plain.copy(then = Onward(unionSq.id, timesSq.id, "R20", listOf("N", "Q")))
        val stop = Train.Stop("L03N", T0 + 840)
        val where = Train.Where(stop, Train.How.COMING)

        // Going as it should: nothing to offer.
        assertNull(RideWords.next(plain, RideState.Riding(3, T0 + 840, where, sure = true)))
        assertNull(RideWords.next(plain, RideState.Next(T0 + 840, where, sure = true)))
        assertNull(RideWords.next(plain, RideState.There(T0 + 840, sure = true)))
        assertNull(RideWords.next(plain, RideState.Unknown(T0, T0 + 840)))
        // Past the stop: done, or the trains on from the change.
        assertEquals(RideWords.Next.DONE, RideWords.next(plain, RideState.Past(T0 + 840, null)))
        assertEquals(RideWords.Next.NEXT_TRAIN, RideWords.next(changing, RideState.Past(T0 + 840, null)))
        assertEquals(RideWords.Next.NEXT_TRAIN, RideWords.next(changing, RideState.There(T0 + 840, sure = true)))
        // A train no use now: another train.
        assertEquals(RideWords.Next.OTHER_TRAIN, RideWords.next(plain, RideState.Lost(T0, T0 + 840)))
        assertEquals(RideWords.Next.OTHER_TRAIN, RideWords.next(changing, RideState.Dropped(UNION_SQ, emptyList())))
        // Text buttons are in capitals, as in the rest of LightOS.
        RideWords.Next.entries.forEach { assertEquals(it.label.uppercase(), it.label) }
    }

    @Test
    fun takingAStationOffHomeIsAskedFirstInPlainWords() {
        assertEquals("Remove Morgan Av from your saved stations?", ConfirmWords.unsave("Morgan Av"))
        assertEquals("REMOVE", ConfirmWords.REMOVE)
        assertEquals("KEEP", ConfirmWords.KEEP)
    }
}
