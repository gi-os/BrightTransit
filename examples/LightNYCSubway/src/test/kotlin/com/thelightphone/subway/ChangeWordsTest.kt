package com.thelightphone.subway

import com.thelightphone.subway.ByHand.ID
import com.thelightphone.subway.ByHand.JEFFERSON
import com.thelightphone.subway.ByHand.MONTROSE
import com.thelightphone.subway.ByHand.MORGAN
import com.thelightphone.subway.ByHand.SIXTH_AV
import com.thelightphone.subway.ByHand.T0
import com.thelightphone.subway.ByHand.THIRD_AV
import com.thelightphone.subway.ByHand.UNION_SQ
import com.thelightphone.subway.ByHand.feed
import com.thelightphone.subway.ByHand.seen
import com.thelightphone.subway.ByHand.trip
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Onward
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.clockLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ride that ends in a change: how long the MTA allows for one, how the lines to change to
 * are named, how such a ride is kept, and what it says on the way.
 */
class ChangeWordsTest {
    private val catalog = Fixtures.catalog
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val timesSq = Fixtures.station("Times Sq-42 St")

    private fun Train.then(feed: GtfsRealtime.Feed) = after(feed, catalog)

    /** Leaving Jefferson St at +30, due at Morgan Av at +120 and at Union Sq at +840. */
    private val coming = seen(feed(T0, trip(JEFFERSON, T0 + 30)))

    private val aboard = coming.then(feed(T0 + 150, trip(MONTROSE, T0 + 210)))

    /** On to Times Sq from Union Sq's Broadway platform. */
    private val onward = Onward(at = unionSq.id, to = timesSq.id, platform = "R20", lines = listOf("N", "Q", "R", "W"))

    private fun said(train: Train, at: Long): List<String?> =
        RideWords.statement(Ride(train, MORGAN, UNION_SQ, onward), at, catalog)
            .let { listOf(it.figure, it.headline) + it.body + it.aside + listOfNotNull(it.note) }
            .map { it?.replace('\u00A0', ' ') }

    private fun clock(time: Long) = clockLabel(time).replace('\u00A0', ' ')

    @Test
    fun theMtasTimeToChangeIsTakenFromTheStationFile() {
        // The L and the 4 5 6 at Union Sq are three minutes apart; the 4 5 6 platform is cross-platform, no time at all.
        assertEquals(180, unionSq.change("L03", "635"))
        assertEquals(0, unionSq.change("635", "635"))
        // A platform the file leaves out is three minutes to change on.
        assertEquals(Station.SAME_PLATFORM, unionSq.change("L03", "L03"))
        // Longer walks are the file's too: the 1 2 3 and the F M at 14 St.
        assertEquals(300, Fixtures.station("14 St", "1").change("132", "D19"))
        // A pair the file does not list is no change at all, and nor is a platform of another station.
        val southFerry = Fixtures.station("Whitehall St-South Ferry")
        assertNull(southFerry.change("142", "R27"))
        assertNull(unionSq.change("L03", "L06"))
    }

    @Test
    fun theLinesToChangeToAreNamedAsARiderReadsThem() {
        assertEquals("4", PlanWords.lines(listOf("4")))
        assertEquals("4 or 5", PlanWords.lines(listOf("4", "5")))
        assertEquals("N, Q, R or W", PlanWords.lines(listOf("N", "Q", "R", "W")))
        // An express is its line.
        assertEquals("6", PlanWords.lines(listOf("6X", "6")))
        // A ride saved with no lines, which nothing writes, still reads.
        assertEquals("", PlanWords.lines(emptyList()))
    }

    @Test
    fun aRideKeepsItsChangeWhenItIsWrittenDownAndOneFromBeforeChangesStillReads() {
        val ride = Ride(aboard, MORGAN, UNION_SQ, onward)
        assertEquals(ride, Ride.decode(ride.encode()))
        // Another train named for the same ride keeps the change.
        assertEquals(onward, ride.on(coming).then)
        // A ride written down by a version with no changes has none.
        val old = Ride(aboard, MORGAN, UNION_SQ).encode()
        assertFalse(old.contains("then"))
        assertNull(Ride.decode(old)?.then)
        assertEquals(Ride(aboard, MORGAN, UNION_SQ), Ride.decode(old))
    }

    @Test
    fun onTheWayTheChangeIsTheLastAsideSaid() {
        fun aside(train: Train, at: Long) = RideWords.statement(Ride(train, MORGAN, UNION_SQ, onward), at, catalog).aside.last().replace('\u00A0', ' ')
        assertEquals("stops to 14 St-Union Sq", said(aboard, T0 + 151)[1])
        assertEquals("Then change to the N, Q, R or W for Times Sq-42 St.", aside(aboard, T0 + 151))
        // Waiting on the platform for the first train, too.
        assertEquals("Then change to the N, Q, R or W for Times Sq-42 St.", aside(coming, T0))
    }

    @Test
    fun atTheStopTheChangeIsWhatIsSaidAndTheButtonGoesToTheTrainsFromThere() {
        val before = aboard.then(feed(T0 + 700, trip(THIRD_AV, T0 + 695), standing = mapOf(ID to THIRD_AV)))
        assertEquals(
            listOf("Next", "stop is 14 St-Union Sq", "Due about ${clock(T0 + 785)}.", "At 3 Av now.", "Then change to the N, Q, R or W for Times Sq-42 St."),
            said(before, T0 + 700),
        )
        assertTrue(RideWords.atTheChange(Ride(before, MORGAN, UNION_SQ, onward).state(T0 + 700)))
        val there = before
            .then(feed(T0 + 760, trip(UNION_SQ, T0 + 800)))
            .then(feed(T0 + 800, trip(UNION_SQ, T0 + 795), standing = mapOf(ID to UNION_SQ)))
        assertEquals(listOf("Now", "at 14 St-Union Sq", "Change here to the N, Q, R or W for Times Sq-42 St."), said(there, T0 + 800))
        val gone = there.then(feed(T0 + 860, trip(SIXTH_AV, T0 + 900)))
        assertEquals(
            listOf(null, "This train has left 14 St-Union Sq.", "Its next stop is 6 Av.", "If you got off, change there to the N, Q, R or W."),
            said(gone, T0 + 860),
        )
        assertTrue(RideWords.atTheChange(Ride(gone, MORGAN, UNION_SQ, onward).state(T0 + 860)))
        // Further back the button is still the one for another train.
        assertFalse(RideWords.atTheChange(Ride(aboard, MORGAN, UNION_SQ, onward).state(T0 + 211)))
        assertIs<RideState.Riding>(Ride(aboard, MORGAN, UNION_SQ, onward).state(T0 + 211))
    }

    @Test
    fun aRideWithNoChangeSaysWhatItAlwaysSaid() {
        val plain = RideWords.statement(Ride(aboard, MORGAN, UNION_SQ), T0 + 211, catalog)
        assertTrue(plain.aside.none { it.contains("change") })
    }
}
