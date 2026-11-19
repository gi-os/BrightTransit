package com.thelightphone.subway

import com.thelightphone.subway.ByHand.BEDFORD
import com.thelightphone.subway.ByHand.EIGHTH_AV
import com.thelightphone.subway.ByHand.GRAHAM
import com.thelightphone.subway.ByHand.GRAND
import com.thelightphone.subway.ByHand.ID
import com.thelightphone.subway.ByHand.JEFFERSON
import com.thelightphone.subway.ByHand.LINE
import com.thelightphone.subway.ByHand.LORIMER
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
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Train
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a rider is told, from Morgan Av to 14 St-Union Sq: every state of the ride, and
 * what each of the feed's lies does to it. The train's stops are a minute and a half apart.
 */
class RideTest {
    private val catalog = Fixtures.catalog

    private fun Train.then(feed: GtfsRealtime.Feed) = after(feed, catalog)

    private fun ride(train: Train, from: String = MORGAN, to: String = UNION_SQ) = Ride(train, from, to)

    /** Leaving Jefferson St at +30, due at Morgan Av at +120 and at Union Sq at +840. */
    private val coming = seen(feed(T0, trip(JEFFERSON, T0 + 30)))

    /** The same train a stop past Morgan Av: Montrose Av at +210, Union Sq still at +840. */
    private val aboard = coming.then(feed(T0 + 150, trip(MONTROSE, T0 + 210)))

    /** The ride in one word and a number, for following it across feeds. */
    private fun RideState.brief(): String = when (this) {
        is RideState.Waiting -> when (val train = train) {
            is Sighting.Coming -> "waiting ${train.stopsAway}"
            else -> "scheduled"
        }
        is RideState.Riding -> "riding $stops"
        is RideState.Next -> "next"
        is RideState.There -> "there"
        is RideState.Past -> "past"
        is RideState.Dropped -> "dropped $platform"
        is RideState.Lost -> "lost"
        is RideState.Unknown -> "unknown"
    }

    /** How many stops the rider is from getting off, for the states that count. */
    private fun RideState.count(): Int? = when (this) {
        is RideState.Waiting -> ((train as? Sighting.Coming)?.stopsAway ?: 99) + stops
        is RideState.Riding -> stops
        is RideState.Next -> 1
        is RideState.There -> 0
        is RideState.Past -> -1
        else -> null
    }

    @Test
    fun beforeTheTrainReachesTheRiderItIsCountedToTheirPlatform() {
        val waiting = assertIs<RideState.Waiting>(ride(coming).state(T0 + 10))
        val train = assertIs<Sighting.Coming>(waiting.train)
        assertEquals(2, train.stopsAway)
        assertEquals(T0 + 120, train.due)
        assertEquals(JEFFERSON, train.next.platform)
        assertTrue(train.sure)
        // From Morgan Av it is eight stops to Union Sq.
        assertEquals(8, waiting.stops)
        assertEquals(T0 + 840, waiting.arrives)
    }

    @Test
    fun theTrainStandingAtTheRidersPlatformIsNoStopsAway() {
        val here = seen(feed(T0, trip(MORGAN, T0 - 5), standing = mapOf(ID to MORGAN)))
        val waiting = assertIs<RideState.Waiting>(ride(here).state(T0))
        assertEquals(0, assertIs<Sighting.Coming>(waiting.train).stopsAway)
        assertEquals(8, waiting.stops)
    }

    @Test
    fun aTripWithNoTrainIsWaitedForAsScheduledAndNeverCountedAsMoving() {
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 600, assigned = false)))
        val waiting = assertIs<RideState.Waiting>(ride(slot).state(T0))
        val scheduled = assertIs<Sighting.Scheduled>(waiting.train)
        assertEquals(T0 + 690, scheduled.due)
        assertEquals(JEFFERSON, scheduled.origin.platform)
        assertEquals(8, waiting.stops)

        // Minutes after it was due, with no new word and no train, it is still only scheduled,
        // and nothing about it is reckoned forward: not where it is, not how far the ride will be.
        val overdue = slot.copy(stops = slot.stops.map { it.copy(time = it.time - 900) })
        (0L..300L step 10).forEach { later ->
            val still = assertIs<RideState.Waiting>(ride(overdue).state(T0 + later), "$later s on")
            assertEquals(Sighting.Scheduled(T0 - 210, overdue.stops.first()), still.train, "$later s on")
            assertEquals(8, still.stops)
        }
        // Once the feed gives it a train it is counted like one.
        val running = slot.then(feed(T0 + 60, trip(JEFFERSON, T0 + 100)))
        assertEquals("waiting 2", ride(running).state(T0 + 60).brief())
    }

    @Test
    fun aSlotTakenOutOfTheFeedWithoutRunningIsLostLikeAnyTrain() {
        // One in nine of the lettered lines' slots went this way on the afternoon studied.
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 600, assigned = false)))
        val other = trip(JEFFERSON, T0 + 900, id = "091000_L..N")
        val gone = slot.then(feed(T0 + 60, other)).then(feed(T0 + 120, other))
        assertFalse(gone.assigned)
        assertIs<RideState.Lost>(ride(gone).state(T0 + 120))
    }

    @Test
    fun aSlotIsStillOnlyScheduledWhenTheRidersPlatformIsNotInItsList() {
        val slot = seen(feed(T0, trip(MONTROSE, T0 + 600, assigned = false)))
        val waiting = assertIs<RideState.Waiting>(ride(slot).state(T0))
        assertIs<Sighting.Scheduled>(waiting.train)
    }

    @Test
    fun ridingIsACountOfStopsToTheRidersOwn() {
        val riding = assertIs<RideState.Riding>(ride(aboard).state(T0 + 150))
        assertEquals(8, riding.stops)
        assertEquals(T0 + 840, riding.arrives)
        assertEquals(MONTROSE, riding.next.platform)
        assertFalse(riding.standing)
        assertTrue(riding.sure)

        // In a station, the stops left are the ones after it.
        val inStation = aboard.then(feed(T0 + 200, trip(MONTROSE, T0 + 195), standing = mapOf(ID to MONTROSE)))
        val standing = assertIs<RideState.Riding>(ride(inStation).state(T0 + 200))
        assertEquals(7, standing.stops)
        assertTrue(standing.standing)
        assertEquals(MONTROSE, standing.next.platform)
    }

    @Test
    fun aTrainFirstFollowedPastTheRidersPlatformIsBeingRidden() {
        val past = seen(feed(T0, trip(GRAND, T0 + 30)))
        assertEquals("riding 7", ride(past).state(T0).brief())
    }

    @Test
    fun aRideToAStopInNoneOfTheTrainsListsHasBeenLeftAndTheTrainIsNotCalledLost() {
        // First heard of at Grand St. A ride to Montrose Av, the stop before, is to a stop it had already left.
        val past = seen(feed(T0, trip(GRAND, T0 + 30)))
        assertEquals(RideState.Past(0, past.stops.first()), ride(past, to = MONTROSE).state(T0))
        // Old word of it does not turn that into a train gone missing, and neither does one download without it.
        // Where it stops next is only said while that is news.
        assertEquals(RideState.Past(0, null), ride(past, to = MONTROSE).state(T0 + 200))
        assertEquals(RideState.Past(0, past.stops.first()), ride(past.then(feed(T0 + 30)), to = MONTROSE).state(T0 + 30))
        // It is lost only when the MTA has in fact stopped reporting it.
        val gone = past.then(feed(T0 + 30)).then(feed(T0 + 60)).then(feed(T0 + 90))
        assertTrue(gone.lost)
        assertIs<RideState.Lost>(ride(gone, to = MONTROSE).state(T0 + 90))
        // With no time for a stop left unseen, the ride is over ten minutes after the first time known for a stop after it.
        assertFalse(ride(past, to = MONTROSE).over(T0 + 30 + Ride.OVER_AFTER))
        assertTrue(ride(past, to = MONTROSE).over(T0 + 30 + Ride.OVER_AFTER + 1))
    }

    @Test
    fun theWholeRideCountsDownAndEnds() {
        // One download as the train leaves each stop and one as it stands at the next.
        val said = ArrayList<String>()
        var train = coming
        var at = T0
        said += ride(train).state(at).brief()
        LINE.forEachIndexed { index, platform ->
            at += 45
            train = train.then(feed(at, trip(platform, at - 5), standing = mapOf(ID to platform)))
            said += ride(train).state(at).brief()
            LINE.getOrNull(index + 1)?.let { next ->
                at += 45
                train = train.then(feed(at, trip(next, at + 40)))
                said += ride(train).state(at).brief()
            }
        }
        assertEquals(
            listOf(
                "waiting 2", "waiting 1", "waiting 1", "waiting 0",
                "riding 8", "riding 7", "riding 7", "riding 6", "riding 6", "riding 5", "riding 5", "riding 4",
                "riding 4", "riding 3", "riding 3", "riding 2", "riding 2", "next", "next", "there",
                "past", "past", "past", "past",
            ),
            said,
        )
    }

    @Test
    fun theNextStopIsTheRidersOnceOneStopIsLeft() {
        val before = aboard.then(feed(T0 + 700, trip(THIRD_AV, T0 + 695), standing = mapOf(ID to THIRD_AV)))
        val next = assertIs<RideState.Next>(ride(before).state(T0 + 700))
        assertTrue(next.standing)
        assertEquals(T0 + 695 + 90, next.arrives)

        val between = before.then(feed(T0 + 760, trip(UNION_SQ, T0 + 800)))
        val moving = assertIs<RideState.Next>(ride(between).state(T0 + 760))
        assertFalse(moving.standing)
        assertTrue(moving.sure)
    }

    @Test
    fun theTrainStandingAtTheRidersStopIsThere() {
        val arrived = aboard.then(feed(T0 + 800, trip(UNION_SQ, T0 + 795), standing = mapOf(ID to UNION_SQ)))
        val there = assertIs<RideState.There>(ride(arrived).state(T0 + 800))
        assertTrue(there.sure)
        assertEquals(T0 + 795, there.due)
    }

    @Test
    fun onceTheTrainHasLeftTheRidersStopItHasLeftForGood() {
        val gone = aboard.then(feed(T0 + 860, trip(SIXTH_AV, T0 + 900)))
        val past = assertIs<RideState.Past>(ride(gone).state(T0 + 860))
        assertEquals(SIXTH_AV, past.next!!.platform)
        assertEquals(T0 + 840, past.at)
        // Old, lost or ended, it is still past. What the train has done stays said, and
        // where it was going next is no longer news.
        assertNull(assertIs<RideState.Past>(ride(gone).state(T0 + 5000)).next)
        assertIs<RideState.Past>(ride(gone.then(feed(T0 + 920)).then(feed(T0 + 1100))).state(T0 + 1100))
        // The stop coming back to the front of the list for a minute does not bring the ride back.
        val flapped = gone.then(feed(T0 + 920, trip(UNION_SQ, T0 + 915), standing = mapOf(ID to UNION_SQ)))
        assertIs<RideState.Past>(ride(flapped).state(T0 + 920))
    }

    @Test
    fun aRideToTheLastStopEndsThereWithNoNextStop() {
        val last = seen(feed(T0, trip(EIGHTH_AV, T0 + 40)))
        assertEquals("next", ride(last, to = EIGHTH_AV).state(T0).brief())
        val arrived = last.then(feed(T0 + 60, trip(EIGHTH_AV, T0 + 55), standing = mapOf(ID to EIGHTH_AV)))
        assertEquals("there", ride(arrived, to = EIGHTH_AV).state(T0 + 60).brief())
    }

    @Test
    fun aTrainTheFeedLetsGoOfBeforeItsLastStopIsReckonedIn() {
        // The L leaves the feed as it pulls out of 6 Av. The rider for 8 Av is not told it is lost.
        val leavingSixth = seen(feed(T0, trip(SIXTH_AV, T0 - 20), standing = mapOf(ID to SIXTH_AV)))
        val toTheEnd = ride(leavingSixth, to = EIGHTH_AV)
        assertEquals("next", toTheEnd.state(T0).brief())
        val gone = toTheEnd.on(leavingSixth.then(feed(T0 + 30)))
        assertTrue(gone.train.ended)
        val next = assertIs<RideState.Next>(gone.state(T0 + 30))
        assertFalse(next.sure)
        // 8 Av was due at +70. Half a minute before that the train is taken to be there.
        assertIs<RideState.Next>(gone.state(T0 + 39))
        val there = assertIs<RideState.There>(gone.state(T0 + 40))
        assertFalse(there.sure)
        assertEquals(T0 + 70, there.due)
        // Nothing newer is coming, so that is what it goes on saying until the ride is over.
        assertIs<RideState.There>(gone.state(T0 + 301))
        assertIs<RideState.There>(gone.state(T0 + 70 + Ride.OVER_AFTER))
        assertTrue(gone.over(T0 + 70 + Ride.OVER_AFTER + 1))
    }

    @Test
    fun aListOverAMinuteOldIsCountedFromItsTimesAndSaysSo() {
        // Aboard at +150: Montrose Av at +210, Grand St at +300, Graham Av at +390, Lorimer St at +480.
        val ride = ride(aboard)
        assertTrue(assertIs<RideState.Riding>(ride.state(T0 + 210)).sure)
        val reckoned = assertIs<RideState.Riding>(ride.state(T0 + 211))
        assertFalse(reckoned.sure)
        // Montrose Av was due a second ago, so it counts as passed and Grand St is next.
        assertEquals(7, reckoned.stops)
        assertEquals(GRAND, reckoned.next.platform)
        assertFalse(reckoned.standing)
        // Each stop counts as passed half a minute before its time.
        assertEquals(7, ride.state(T0 + 269).count())
        assertEquals(6, ride.state(T0 + 270).count())
        assertEquals(5, ride.state(T0 + 360).count())
        assertEquals(4, ride.state(T0 + 450).count())
    }

    @Test
    fun aListOverFiveMinutesOldIsNotCountedFromAtAll() {
        val ride = ride(aboard)
        assertIs<RideState.Riding>(ride.state(T0 + 450))
        val unknown = assertIs<RideState.Unknown>(ride.state(T0 + 451))
        assertEquals(T0 + 150, unknown.since)
        assertEquals(T0 + 840, unknown.due)
        // The stop before the rider's is still known, and is what they are left with.
        assertEquals(THIRD_AV, ride.train.before(UNION_SQ)!!.platform)
        // While waiting it is the same: no count of how far off the train is.
        assertIs<RideState.Unknown>(ride(coming).state(T0 + 301))
    }

    @Test
    fun reckonedTheTrainIsTakenToBeAtTheRidersStopButNeverPastIt() {
        // Third Av in a minute, Union Sq in two and a half.
        val close = seen(feed(T0, trip(THIRD_AV, T0 + 60)))
        val ride = ride(close)
        assertEquals("riding 2", ride.state(T0).brief())
        assertEquals("riding 2", ride.state(T0 + 60).brief())
        assertEquals("next", ride.state(T0 + 61).brief())
        assertEquals("next", ride.state(T0 + 119).brief())
        val there = assertIs<RideState.There>(ride.state(T0 + 120))
        assertFalse(there.sure)
        assertEquals(T0 + 150, there.due)
        // It has not been seen to leave, so it is not said to have left.
        assertEquals("there", ride.state(T0 + 300).brief())
        assertEquals("unknown", ride.state(T0 + 301).brief())
    }

    @Test
    fun aTrainSeenStandingDoesNotCountUpWhenItsListGrowsOld() {
        // Seen stopped at Montrose Av, whose listed time is still a minute off, as the L's feed can have it.
        val standing = aboard.then(feed(T0 + 200, trip(MONTROSE, T0 + 270), standing = mapOf(ID to MONTROSE)))
        val ride = ride(standing)
        assertEquals(7, ride.state(T0 + 260).count())
        assertTrue(assertIs<RideState.Riding>(ride.state(T0 + 260)).sure)
        val old = assertIs<RideState.Riding>(ride.state(T0 + 261))
        assertFalse(old.sure)
        assertEquals(7, old.stops)
        assertEquals(GRAND, old.next.platform)
    }

    @Test
    fun aTrainMissingFromTheFeedIsRiddenThroughThenLost() {
        val other = trip(JEFFERSON, T0 + 900, id = "091000_L..N")
        val missed = aboard.then(feed(T0 + 210, other))
        // One download without it: the last list still stands, a minute old.
        assertEquals("riding 8", ride(missed).state(T0 + 210).brief())
        val missedTwice = missed.then(feed(T0 + 270, other))
        val lost = assertIs<RideState.Lost>(ride(missedTwice).state(T0 + 270))
        assertEquals(T0 + 150, lost.since)
        assertEquals(T0 + 840, lost.due)
        // Back in the feed, it is counted again.
        val back = missedTwice.then(feed(T0 + 330, other, trip(GRAND, T0 + 340)))
        assertEquals("riding 7", ride(back).state(T0 + 330).brief())
    }

    @Test
    fun theRidersStopTakenAwayIsSaidAndNotCountedDownTo() {
        val skipping = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(THIRD_AV, UNION_SQ))))
        val dropped = assertIs<RideState.Dropped>(ride(skipping).state(T0 + 210))
        assertEquals(UNION_SQ, dropped.platform)
        assertEquals(listOf(MONTROSE, GRAND), dropped.next.map { it.platform })
        // It stays said while the train runs on, and when the word of it grows old.
        val later = skipping.then(feed(T0 + 300, trip(GRAND, T0 + 310, skipping = setOf(THIRD_AV, UNION_SQ))))
        assertEquals(listOf(GRAND, GRAHAM), assertIs<RideState.Dropped>(ride(later).state(T0 + 300)).next.map { it.platform })
        // Where it stops next is only said while that is news: a minute on, those stops are behind it.
        assertEquals(listOf(GRAND, GRAHAM), assertIs<RideState.Dropped>(ride(later).state(T0 + 360)).next.map { it.platform })
        assertEquals(RideState.Dropped(UNION_SQ, emptyList()), ride(later).state(T0 + 361))
        assertEquals(RideState.Dropped(UNION_SQ, emptyList()), ride(later).state(T0 + 3000))
        // If the feed gives the stop back, the count comes back with it.
        val restored = later.then(feed(T0 + 360, trip(GRAND, T0 + 370)))
        assertEquals("riding 7", ride(restored).state(T0 + 360).brief())
    }

    @Test
    fun aTrainCutShortOfTheRidersStopHasDroppedIt() {
        val cut = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(UNION_SQ, SIXTH_AV, EIGHTH_AV))))
        assertEquals("dropped $UNION_SQ", ride(cut).state(T0 + 210).brief())
        // The same train is still good for a rider getting off before the cut.
        assertEquals("riding 7", ride(cut, to = THIRD_AV).state(T0 + 210).brief())
    }

    @Test
    fun theStopTheRiderIsWaitingAtTakenAwayIsSaidToo() {
        // Made to skip Morgan Av while the rider stands there. It still stops at Union Sq.
        val skipping = coming.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(MORGAN, MONTROSE))))
        val dropped = assertIs<RideState.Dropped>(ride(skipping).state(T0 + 60))
        assertEquals(MORGAN, dropped.platform)
        // A rider already aboard is not told about a stop behind them.
        val aboardAlready = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215)))
        assertEquals("riding 8", ride(aboardAlready).state(T0 + 210).brief())
    }

    @Test
    fun stopsTakenAwayBeforeTheRidersShortenTheCountAndStopsAddedLengthenIt() {
        val skipping = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(GRAND, GRAHAM, LORIMER))))
        assertEquals("riding 5", ride(skipping).state(T0 + 210).brief())
        assertEquals(T0 + 210, skipping.changed)
        val local = skipping.then(feed(T0 + 270, trip(MONTROSE, T0 + 275)))
        assertEquals("riding 8", ride(local).state(T0 + 270).brief())
        assertEquals(T0 + 270, local.changed)
    }

    @Test
    fun aPassedStopComingBackDoesNotRaiseTheCount() {
        val flapped = aboard.then(feed(T0 + 210, trip(MORGAN, T0 + 205), standing = mapOf(ID to MORGAN)))
        assertEquals("riding 8", ride(flapped).state(T0 + 210).brief())
        assertEquals(0, flapped.changed)
    }

    @Test
    fun theRailwaysLastStopIsCountedToWithNoTimeGiven() {
        val day = Fixtures.weekdayAfternoon.feeds.getValue("gtfs-si")
        val trip = day.trips.first { it.stops.size > 5 }
        val train = Train.of("gtfs-si", day, trip, catalog)
        val last = train.stops.last().platform
        val ride = Ride(train, from = "S00", to = last)
        val riding = assertIs<RideState.Riding>(ride.state(day.timestamp))
        assertEquals(train.stops.size, riding.stops)
        assertEquals(0, riding.arrives)
        // With no time for it, the last stop is never reckoned reached: the count stops at "next".
        val soon = ride.on(train.copy(stops = train.stops.map { if (it.time > 0) it.copy(time = day.timestamp + 20) else it }))
        assertEquals("next", soon.state(day.timestamp + 200).brief())
        assertEquals("unknown", soon.state(day.timestamp + 301).brief())
    }

    @Test
    fun withNoNewWordTheCountNeverGoesUpAndTheReckonedCountIsNeverAboveTheLiveOne() {
        val random = Random(20261113)
        repeat(400) {
            val start = random.nextInt(LINE.size - 2)
            val from = LINE[start + random.nextInt(LINE.size - start - 1)]
            val to = LINE[LINE.indexOf(from) + 1 + random.nextInt(LINE.size - LINE.indexOf(from) - 1)]
            val first = T0 + random.nextInt(400) - 200
            val standing = random.nextBoolean()
            val train = seen(
                feed(T0, trip(LINE[start], first, every = 30L + random.nextInt(150)), standing = if (standing) mapOf(ID to LINE[start]) else emptyMap()),
            )
            val ride = ride(train, from, to)
            val live = ride.state(T0).count()!!
            var last = live
            (0L..300L).forEach { later ->
                val state = ride.state(T0 + later)
                val count = state.count() ?: error("$state at +$later")
                assertTrue(count <= last, "from ${LINE[start]} for $from to $to: $last then $count at +$later")
                assertTrue(count <= live)
                // Nothing is said to have happened that was not seen to.
                assertTrue(count >= 0, "$state")
                last = count
            }
            assertIs<RideState.Unknown>(ride.state(T0 + 301))
            // The stop named as coming before the rider's is the one before it in the list.
            val before = train.before(to)
            if (train.place(to) > 0) assertEquals(LINE[LINE.indexOf(to) - 1], before!!.platform) else assertNull(before)
        }
    }

    @Test
    fun whateverBecomesOfTheTrainARiderStillWaitingIsToldHowItStandsOrThatItIsScheduled() {
        // The feed can let go of a train, or the phone lose its signal, at any point of a
        // ride. Whenever the rider is still counted as waiting, there is a sentence for it.
        val random = Random(20261113)
        repeat(400) {
            val start = random.nextInt(LINE.size - 2)
            val from = LINE[start + random.nextInt(LINE.size - start - 1)]
            val to = LINE[LINE.indexOf(from) + 1 + random.nextInt(LINE.size - LINE.indexOf(from) - 1)]
            val seen = seen(feed(T0, trip(LINE[start], T0 + random.nextInt(900) - 300, every = 30L + random.nextInt(300), assigned = random.nextInt(4) > 0)))
            // Only a train can have ended its run. A slot that leaves the feed is lost.
            val train = when (random.nextInt(3)) {
                0 -> seen
                1 -> seen.copy(ended = seen.assigned, checked = T0 + 60L + random.nextInt(900))
                else -> seen.copy(stops = seen.stops.dropLast(1) + seen.stops.last().copy(time = 0), ended = seen.assigned && random.nextBoolean())
            }
            (0L..1200L step 7).forEach { later ->
                val state = ride(train, from, to).state(T0 + later)
                if (state is RideState.Waiting) {
                    assertTrue(state.train is Sighting.Coming || state.train is Sighting.Scheduled, "$train at +$later: ${state.train}")
                    assertTrue(state.stops >= 1, "$train at +$later")
                }
            }
        }
    }

    @Test
    fun aRideIsOverTenMinutesAfterTheTrainWasDueAtTheRidersStop() {
        val ride = ride(aboard)
        assertFalse(ride.over(T0 + 840))
        assertFalse(ride.over(T0 + 840 + 600))
        assertTrue(ride.over(T0 + 840 + 601))
        // However it ended: past the stop, lost, or the stop taken away, it goes by when the stop was due.
        val past = ride.on(aboard.then(feed(T0 + 860, trip(SIXTH_AV, T0 + 900))))
        assertFalse(past.over(T0 + 1000))
        assertTrue(past.over(T0 + 840 + 601))
        val dropped = ride.on(aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(UNION_SQ)))))
        assertFalse(dropped.over(T0 + 1000))
        assertTrue(dropped.over(T0 + 840 + 601))
        // A long ride is still worth coming back to after a long time away.
        assertFalse(ride(coming, to = EIGHTH_AV).over(T0 + 1500))
    }

    @Test
    fun aRideToAStopWithNoTimeIsOverTenMinutesAfterTheLastTimeTheTrainHas() {
        // The railway gives its last stop no time. A ride to St George is most of what the railway is for.
        val day = Fixtures.weekdayAfternoon.feeds.getValue("gtfs-si")
        val train = Train.of("gtfs-si", day, day.trips.first { it.stops.size > 5 }, catalog)
        val ride = Ride(train, from = "S00", to = train.stops.last().platform)
        assertEquals(0, train.stops.last().time)
        val last = train.stops.maxOf { it.time }
        assertEquals(day.timestamp + 683, last)
        // Ten minutes with the phone in a pocket do not end it: the train is nowhere near.
        assertFalse(ride.over(day.timestamp + 601))
        assertFalse(ride.over(last + 600))
        assertTrue(ride.over(last + 601))
        // With nothing but the untimed stop to go by, it is ten minutes from the last word of the train.
        val bare = ride.on(train.copy(stops = train.stops.takeLast(1)))
        assertFalse(bare.over(day.timestamp + 600))
        assertTrue(bare.over(day.timestamp + 601))
    }

    @Test
    fun aRideIsNotOverWhileItsTrainIsStillHeardOfShortOfTheStop() {
        // Held one stop short: the time first given for the rider's stop went by twenty minutes ago.
        val held = aboard.then(feed(T0 + 2000, trip(THIRD_AV, T0 + 700), standing = mapOf(ID to THIRD_AV)))
        assertEquals(T0 + 790, held.stop(UNION_SQ)!!.time)
        assertFalse(ride(held).over(T0 + 2000))
        assertFalse(ride(held).over(T0 + 2600))
        // Ten minutes after the last word that still had the stop ahead, it is.
        assertTrue(ride(held).over(T0 + 2601))
        // Once the train has left the stop, or dropped it, it goes by the stop's own time again.
        val past = held.then(feed(T0 + 2030, trip(SIXTH_AV, T0 + 2100)))
        assertTrue(ride(past).over(T0 + 2030))
        // A ride that looks over is only forgotten unasked when its train has not been heard of for an hour.
        assertFalse(ride(aboard).forgotten(T0 + 840 + 601))
        assertFalse(ride(aboard).forgotten(T0 + 150 + 3600))
        assertTrue(ride(aboard).forgotten(T0 + 150 + 3601))
        assertFalse(ride(coming, to = EIGHTH_AV).forgotten(T0 + 1500))
    }

    @Test
    fun aRideIsWrittenDownAndReadBackTheSame() {
        val skipping = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(GRAND)), standing = mapOf(ID to MONTROSE)))
        val ride = ride(skipping)
        assertTrue(ride.train.behind.isNotEmpty() && ride.train.dropped.isNotEmpty() && ride.train.standing)
        assertEquals(ride, Ride.decode(ride.encode()))
        assertEquals(ride.state(T0 + 220), Ride.decode(ride.encode())!!.state(T0 + 220))
        assertEquals(ride(coming), Ride.decode(ride(coming).encode()))
    }

    @Test
    fun whatIsNotARideIsReadAsNone() {
        assertNull(Ride.decode(""))
        assertNull(Ride.decode("625|L14"))
        assertNull(Ride.decode("{\"to\":\"L03\"}"))
        assertNull(Ride.decode(ride(coming).encode().dropLast(5)))
        // A later version may write more than this one reads.
        val extended = ride(coming).encode().replaceFirst("{", "{\"car\":3,")
        assertEquals(ride(coming), Ride.decode(extended))
    }

    @Test
    fun switchingTrainsKeepsTheRidersStops() {
        val later = seen(feed(T0, trip(JEFFERSON, T0 + 500, id = "091000_L..N")), "091000_L..N")
        val switched = ride(aboard).on(later)
        assertEquals(MORGAN, switched.from)
        assertEquals(UNION_SQ, switched.to)
        // The later train has not reached Morgan Av, so the rider is waiting again.
        assertEquals("waiting 2", switched.state(T0).brief())
        // One further along has passed it, and is being ridden.
        val ahead = seen(feed(T0, trip(BEDFORD, T0 + 30, id = "089000_L..N")), "089000_L..N")
        assertEquals("riding 4", ride(coming).on(ahead).state(T0).brief())
    }

    @Test
    fun theOtherTrainsOfferedAreTheOnesAroundTheFollowedTrainOnTheirWayToTheRidersStop() {
        val day = Fixtures.weekdayAfternoon
        val feed = day.feeds.getValue("gtfs-l")
        // Nine L trains were on their way to Union Sq, one more than the list holds.
        val all = Train.callingAt("gtfs-l", feed, "L03N", catalog)
        assertEquals(9, all.size)
        fun offered(following: Train) = OtherTrains.of(Ride(following, from = "L14", to = "L03"), feed, catalog, day.time)

        // Following the nearest, the list opens with it.
        assertEquals(all.take(OtherTrains.MOST).map { it.id }, offered(all.first()).map { it.id })
        // Following the furthest, it is still in the list, at the end of it.
        assertEquals(all.drop(1).map { it.id }, offered(all.last()).map { it.id })
        // Every one is a train with the stop ahead of it, nearest first: the order they will reach it in.
        offered(all[4]).let { others ->
            assertTrue(others.all { it.assigned && it.place("L03") >= 0 })
            assertEquals(others.map { it.stop("L03")!!.time }.sorted(), others.map { it.stop("L03")!!.time })
        }

        // A record hours old that still lists the stop is not a train to be on.
        val ghost = all.first()
        fun shifted(by: Long) = feed.copy(trips = feed.trips.map { trip -> if (trip.id == ghost.id) trip.copy(stops = trip.stops.map { it.copy(time = it.time - by) }) else trip })
        assertTrue(OtherTrains.of(Ride(all[1], from = "L14", to = "L03"), shifted(5 * 3600), catalog, day.time).none { it.id == ghost.id })
        // A train a few minutes past its time is one held short of the stop, and is offered, first.
        val due = ghost.stop("L03")!!.time - day.time
        listOf(4 * 60L, 10 * 60L).forEach { late ->
            assertEquals(ghost.id, OtherTrains.of(Ride(all[1], from = "L14", to = "L03"), shifted(due + late), catalog, day.time).first().id, "$late s late")
        }
        assertTrue(OtherTrains.of(Ride(all[1], from = "L14", to = "L03"), shifted(due + 21 * 60), catalog, day.time).none { it.id == ghost.id })
        // The train being followed is in the list for as long as it is listed for the stop, whatever its times.
        assertEquals(ghost.id, OtherTrains.of(Ride(ghost, from = "L14", to = "L03"), shifted(5 * 3600), catalog, day.time).first().id)

        // On the numbered lines 36 trains listed Union Sq. The rider sees three ahead of theirs, theirs, and four behind.
        val numbered = day.feeds.getValue("gtfs")
        val downtown = Train.callingAt("gtfs", numbered, "635S", catalog)
        assertEquals(36, downtown.size)
        val around = OtherTrains.of(Ride(downtown[10], from = "631", to = "635"), numbered, catalog, day.time)
        assertEquals(downtown.subList(7, 15).map { it.id }, around.map { it.id })
        // A train no longer in the feed leaves the nearest ones to choose from.
        val gone = downtown[10].copy(id = "000000_6..S")
        assertEquals(downtown.take(OtherTrains.MOST).map { it.id }, OtherTrains.of(Ride(gone, from = "631", to = "635"), numbered, catalog, day.time).map { it.id })
    }

    @Test
    fun aTrainStandingAtTheRidersOwnStopLongPastItsTimeHasArrivedAndIsNotOffered() {
        // Records linger at the end of a line. One stopped at the stop itself is not on its way to it.
        val arrived = trip(UNION_SQ, T0 - 300, id = "089000_L..N")
        val held = trip(THIRD_AV, T0 - 300, id = "089500_L..N")
        val day = feed(T0, arrived, held, trip(JEFFERSON, T0 + 30), standing = mapOf("089000_L..N" to UNION_SQ, "089500_L..N" to THIRD_AV))
        val offered = OtherTrains.of(ride(coming), day, catalog, T0).map { it.id }
        assertEquals(listOf("089500_L..N", ID), offered)
        // Two minutes is how long it is given.
        val justIn = feed(T0, trip(UNION_SQ, T0 - 100, id = "089000_L..N"), trip(JEFFERSON, T0 + 30), standing = mapOf("089000_L..N" to UNION_SQ))
        assertEquals(listOf("089000_L..N", ID), OtherTrains.of(ride(coming), justIn, catalog, T0).map { it.id })
    }

    @Test
    fun theListOfOtherTrainsRemembersHowLongATrainHasBeenWhereItIs() {
        fun listed(at: Long, known: List<Train>): List<Train> {
            val jz = GtfsRealtime.Feed(at, listOf(trip(JEFFERSON, at - 10)), mapOf(ID to GtfsRealtime.Position("${JEFFERSON}N", GtfsRealtime.Status.STOPPED, at - 10)))
            return OtherTrains.of(Ride(coming.copy(feed = "gtfs-jz"), MORGAN, UNION_SQ), jz, catalog, at, known)
        }
        val first = listed(T0, emptyList())
        assertEquals(T0 - 10, first.single().stoodSince)
        // Read afresh a minute later, it has still been there since it was first listed there.
        assertEquals(T0 - 10, listed(T0 + 60, first).single().stoodSince)
        assertEquals(T0 + 50, listed(T0 + 60, emptyList()).single().stoodSince)

        // The train being followed has been followed all along. The ride knows how long it has stood where it is,
        // and the list says the same of it the first time it opens.
        val jz = Train.find("gtfs-jz", feed(T0, trip(JEFFERSON, T0 - 100), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        assertEquals(T0 - 100, jz.stoodSince)
        val later = GtfsRealtime.Feed(T0 + 60, listOf(trip(JEFFERSON, T0 + 50)), mapOf(ID to GtfsRealtime.Position("${JEFFERSON}N", GtfsRealtime.Status.STOPPED, T0 + 50)))
        val row = OtherTrains.of(Ride(jz, MORGAN, UNION_SQ), later, catalog, T0 + 60).single()
        assertEquals(T0 - 100, row.stoodSince)
        assertEquals(Train.How.REACHED, row.whereAt(T0 + 60)!!.how)
        // Another train on the list is not given the followed train's time.
        val other = OtherTrains.of(Ride(jz.copy(id = "000000_L..N"), MORGAN, UNION_SQ), later, catalog, T0 + 60).single()
        assertEquals(T0 + 50, other.stoodSince)
    }

    @Test
    fun theListOfOtherTrainsSaysLoadingWhileItIsAskedForAndUnavailableOnlyWhenThatFailed() {
        val trains = listOf(coming)
        assertEquals(OtherTrains.Choice.View.Loading, OtherTrains.Choice().view(T0))
        assertEquals(OtherTrains.Choice.View.Unavailable, OtherTrains.Choice(failed = true).view(T0))
        assertEquals(OtherTrains.Choice.View.Trains(trains, live = true), OtherTrains.Choice(trains, asOf = T0).view(T0 + 60))
        assertEquals(OtherTrains.Choice.View.Trains(trains, live = false), OtherTrains.Choice(trains, asOf = T0).view(T0 + 61))
        // A list exactly five minutes old is still shown. A second older, its next stops are the wrong ones.
        assertEquals(OtherTrains.Choice.View.Trains(trains, live = false), OtherTrains.Choice(trains, asOf = T0).view(T0 + 300))
        assertEquals(OtherTrains.Choice.View.Unavailable, OtherTrains.Choice(trains, asOf = T0).view(T0 + 301))
        // Come back to after a sleep, it is being asked for again, and nothing has failed yet.
        assertEquals(OtherTrains.Choice.View.Loading, OtherTrains.Choice(trains, asOf = T0, awaited = true).view(T0 + 301))
        assertEquals(OtherTrains.Choice.View.Loading, OtherTrains.Choice(failed = true, awaited = true).view(T0))
        // An answer on its way does not take a list that can still be shown off the screen.
        assertEquals(OtherTrains.Choice.View.Trains(trains, live = true), OtherTrains.Choice(trains, asOf = T0, awaited = true).view(T0 + 30))
        assertEquals(OtherTrains.Choice.View.Trains(emptyList(), live = true), OtherTrains.Choice(emptyList(), asOf = T0).view(T0))
    }

    @Test
    fun aRideOnATripWithNoTrainIsNeverCountedAsMovingThoughTheFeedWalksItsList() {
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 30, assigned = false)))
        var train = slot
        // The feed takes a stop off the empty trip every ninety seconds, as the lettered lines do.
        val said = LINE.drop(1).take(9).mapIndexed { index, first ->
            val at = T0 + 60 + index * 90L
            train = train.then(feed(at, trip(first, at + 60, assigned = false)))
            ride(train).state(at)
        }
        said.forEach { state ->
            val waiting = assertIs<RideState.Waiting>(state)
            assertEquals(JEFFERSON, assertIs<Sighting.Scheduled>(waiting.train).origin.platform)
            assertEquals(8, waiting.stops)
        }
        // By now the feed lists nothing before Union Sq, and no train has run.
        assertEquals(LINE, train.stops.map { it.platform })
        assertNull(train.left(UNION_SQ))
        // A ride an older build saved with stops filed as left is not past either.
        val filed = Ride(slot.copy(stops = slot.stops.drop(10), behind = slot.stops.take(10)), MORGAN, UNION_SQ)
        assertFalse(filed.state(T0) is RideState.Past)
    }

    @Test
    fun aTrainBehindItsTimesWhenLastHeardOfIsNotCountedOnAndTheCountStaysWhereItWas() {
        val random = Random(20261116)
        var looked = 0
        repeat(300) {
            val start = random.nextInt(LINE.size - 3)
            val to = LINE[start + 2 + random.nextInt(LINE.size - start - 2)]
            val standing = random.nextBoolean()
            // The stop it is at or coming to was due before the feed was built: by a minute or more when it is standing there.
            val first = T0 - (if (standing) 61 else 1) - random.nextInt(400)
            val train = seen(feed(T0, trip(LINE[start], first, every = 30L + random.nextInt(150)), standing = if (standing) mapOf(ID to LINE[start]) else emptyMap()))
            val ride = ride(train, from = LINE[start], to = to)
            val live = ride.state(T0).count()!!
            (61L..300L step 7).forEach { later ->
                val state = ride.state(T0 + later)
                assertEquals(live, state.count(), "from ${LINE[start]} to $to, standing $standing, at +$later: $state")
                looked++
            }
            assertIs<RideState.Unknown>(ride.state(T0 + 301))
        }
        assertTrue(looked > 10_000)
    }

    @Test
    fun aboardAHeldTrainTheHoldIsCarriedAndATrainStandingAtTheRidersStopIsThereWhileItsTimeIsAhead() {
        // Held at Montrose Av, three minutes past its time there.
        val held = aboard.then(feed(T0 + 400, trip(MONTROSE, T0 + 215), standing = mapOf(ID to MONTROSE)))
        val riding = assertIs<RideState.Riding>(ride(held).state(T0 + 400))
        assertEquals(Train.Where(held.stops.first(), Train.How.HELD, T0 + 215), riding.where)
        assertTrue(riding.sure)
        // One stop short of the rider's.
        val short = aboard.then(feed(T0 + 900, trip(THIRD_AV, T0 + 700), standing = mapOf(ID to THIRD_AV)))
        assertEquals(Train.How.HELD, assertIs<RideState.Next>(ride(short).state(T0 + 900)).where.how)
        // Not held: on its way, or standing under two minutes.
        assertEquals(Train.How.COMING, assertIs<RideState.Riding>(ride(aboard).state(T0 + 150)).where.how)
        val dwell = aboard.then(feed(T0 + 300, trip(MONTROSE, T0 + 215), standing = mapOf(ID to MONTROSE)))
        assertEquals(Train.How.THERE, assertIs<RideState.Riding>(ride(dwell).state(T0 + 300)).where.how)
        // Standing at the rider's own stop with its time there still ahead, it is there when the list grows old, and grey.
        val early = aboard.then(feed(T0 + 700, trip(UNION_SQ, T0 + 900), standing = mapOf(ID to UNION_SQ)))
        assertEquals(RideState.There(T0 + 900, sure = true), ride(early).state(T0 + 760))
        assertEquals(RideState.There(T0 + 900, sure = false), ride(early).state(T0 + 761))
    }

    @Test
    fun aTrainLetGoOfAtTheEndOfItsRunHasLeftAStopShortOfItsLast() {
        // The L leaves the feed as it pulls out of 6 Av, its second-to-last stop. The rider was getting off there.
        val atSixth = seen(feed(T0, trip(SIXTH_AV, T0 - 20), standing = mapOf(ID to SIXTH_AV)))
        val toSixth = ride(atSixth, from = UNION_SQ, to = SIXTH_AV)
        assertEquals(RideState.There(T0 - 20, sure = true), toSixth.state(T0))
        // One download without it: it may only have been missed.
        val missed = toSixth.on(atSixth.then(feed(T0 + 30)))
        assertTrue(missed.train.ended)
        assertEquals(RideState.There(T0 - 20, sure = false), missed.state(T0 + 30))
        // Reckoned into 8 Av, half a minute before its time there, it has left 6 Av.
        assertEquals(RideState.There(T0 - 20, sure = false), missed.state(T0 + 39))
        assertEquals(RideState.Past(T0 - 20, next = null, sure = false), missed.state(T0 + 40))
        // And gone from a second download and a third, it has left whatever its times say.
        val gone = toSixth.on(atSixth.copy(stops = atSixth.stops.map { it.copy(time = it.time + 600) }).then(feed(T0 + 30)).then(feed(T0 + 60)).then(feed(T0 + 90)))
        assertEquals(RideState.Past(T0 + 580, next = null, sure = false), gone.state(T0 + 90))
        assertIs<RideState.Past>(gone.state(T0 + 5000))
        // A ride put away at "next" and looked at again when the run is over is past, not there.
        val before = seen(feed(T0, trip(THIRD_AV, T0 + 30, every = 60)))
        val resumed = Ride(before.then(feed(T0 + 450)), MORGAN, UNION_SQ)
        assertTrue(resumed.train.ended)
        assertEquals(RideState.Past(T0 + 90, next = null, sure = false), resumed.state(T0 + 450))
        // To the very last stop nothing changes: there, for good.
        assertIs<RideState.There>(Ride(before.then(feed(T0 + 450)), MORGAN, EIGHTH_AV).state(T0 + 450))
        // Left with further to go than a missing train can be taken to have finished, it is not there either: it is lost.
        val further = seen(feed(T0, trip(THIRD_AV, T0 + 30))).then(feed(T0 + 450))
        assertFalse(further.ended)
        assertTrue(further.ranOut)
        assertIs<RideState.Lost>(Ride(further, MORGAN, UNION_SQ).state(T0 + 450))
    }

    @Test
    fun aTrainLetGoOfWhileBehindItsTimesStillLeavesAndIsNotLeftWhereItWasLastSeen() {
        // A train behind its times is left where it was when its word grows old. One the feed has let go of at
        // the end of its run is not: there will be no later word, and it has not stood there ever since.
        // Standing at 6 Av, a minute and a half past its time there, when the feed drops it. 8 Av is four minutes on.
        val standing = seen(feed(T0, trip(SIXTH_AV, T0 - 90, every = 240), standing = mapOf(ID to SIXTH_AV)))
        assertEquals(RideState.There(T0 - 90, sure = true), ride(standing, from = UNION_SQ, to = SIXTH_AV).state(T0))
        var train = standing
        val stood = listOf(30L, 60L, 90L).map { later ->
            train = train.then(feed(T0 + later))
            ride(train, from = UNION_SQ, to = SIXTH_AV).state(T0 + later)
        }
        assertEquals(
            listOf(RideState.There(T0 - 90, sure = false), RideState.There(T0 - 90, sure = false), RideState.Past(T0 - 90, next = null, sure = false)),
            stood,
        )
        // On its way and already late for 6 Av: the same.
        train = seen(feed(T0, trip(SIXTH_AV, T0 - 20, every = 240)))
        assertEquals("next", ride(train, from = UNION_SQ, to = SIXTH_AV).state(T0).brief())
        val late = listOf(30L, 60L, 90L).map { later ->
            train = train.then(feed(T0 + later))
            ride(train, from = UNION_SQ, to = SIXTH_AV).state(T0 + later)
        }
        assertEquals(
            listOf(RideState.There(T0 - 20, sure = false), RideState.There(T0 - 20, sure = false), RideState.Past(T0 - 20, next = null, sure = false)),
            late,
        )
        // Standing at its last stop three minutes past its time, as trains at a terminal do: there, for good, and
        // on its page it has finished. This is where the rule works most often.
        val atEnd = seen(feed(T0, trip(EIGHTH_AV, T0 - 180), standing = mapOf(ID to EIGHTH_AV))).then(feed(T0 + 30))
        assertTrue(atEnd.ended)
        assertEquals(RideState.There(T0 - 180, sure = false), ride(atEnd, from = UNION_SQ, to = EIGHTH_AV).state(T0 + 30))
        assertEquals(RideState.There(T0 - 180, sure = false), ride(atEnd, from = UNION_SQ, to = EIGHTH_AV).state(T0 + 400))
        assertEquals(Sighting.Ended, atEnd.seenFrom(EIGHTH_AV, T0 + 30))
        assertEquals(Sighting.Ended, atEnd.seenFrom(MORGAN, T0 + 30))
    }

    @Test
    fun aTrainThatEndedWithStopsStillAheadIsNotCountedPastFiveMinutes() {
        // Let go of with two stops left and a long way to the last of them.
        val slow = seen(feed(T0, trip(SIXTH_AV, T0 + 200, every = 600))).then(feed(T0 + 60))
        assertTrue(slow.ended)
        val toTheEnd = Ride(slow, UNION_SQ, EIGHTH_AV)
        assertEquals("riding 2", toTheEnd.state(T0 + 60).brief())
        assertEquals("next", toTheEnd.state(T0 + 300).brief())
        assertEquals("unknown", toTheEnd.state(T0 + 301).brief())
        // Once its times have it there, it is there, as for any train that ended.
        assertEquals("there", toTheEnd.state(T0 + 770).brief())
    }
}
