package com.thelightphone.subway

import com.thelightphone.subway.ByHand.EIGHTH_AV
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
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.clockLabel
import com.thelightphone.subway.ui.notLive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sentences on a train's page and on a ride, state by state, as the rider reads them. */
class RideWordsTest {
    private val catalog = Fixtures.catalog

    private fun Train.then(feed: GtfsRealtime.Feed) = after(feed, catalog)

    /** Leaving Jefferson St at +30, due at Morgan Av at +120 and at Union Sq at +840. */
    private val coming = seen(feed(T0, trip(JEFFERSON, T0 + 30)))

    private val aboard = coming.then(feed(T0 + 150, trip(MONTROSE, T0 + 210)))

    private fun top(train: Train, at: Long, from: String = MORGAN) =
        RideWords.top(train.seenFrom(from, at), catalog.stopName(from), at, catalog).let { it.head to it.detail }

    /** The statement from top to bottom, with ordinary spaces: on the screen a short stop name and a clock time are kept from breaking. */
    private fun said(train: Train, at: Long, from: String = MORGAN, to: String = UNION_SQ): List<String?> =
        RideWords.statement(Ride(train, from, to), at, catalog)
            .let { listOf(it.figure, it.headline) + it.body + it.aside + listOfNotNull(it.note) }
            .map { it?.replace('\u00A0', ' ') }

    /** A clock time as [said] gives it, with an ordinary space. */
    private fun clock(time: Long) = clockLabel(time).replace('\u00A0', ' ')

    @Test
    fun aTrainIsNamedAsItsFrontNamesIt() {
        assertEquals("L to 8 Av", RideWords.name(coming, catalog))
        assertEquals("to 8 Av", RideWords.toward(coming, catalog))
        val day = Fixtures.weekdayAfternoon
        fun first(slug: String, route: String) =
            day.feeds.getValue(slug).let { feed -> Train.of(slug, feed, feed.trips.first { it.route == route }, catalog) }
        // A title has no diamond to draw, so an express is spelled out.
        assertTrue(RideWords.name(first("gtfs", "6X"), catalog).startsWith("6 express to "))
        // The shuttles and the railway go by the names on their bullets.
        assertTrue(RideWords.name(first("gtfs", "GS"), catalog).startsWith("S to "))
        assertTrue(RideWords.name(first("gtfs-si", "SI"), catalog).startsWith("SIR to "))
        // A train cut short is named for where it now ends.
        val cut = coming.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(SIXTH_AV, EIGHTH_AV))))
        assertEquals("L to 14 St-Union Sq", RideWords.name(cut, catalog))
    }

    @Test
    fun theTopOfATrainsPageLeadsWithACountOfStops() {
        assertEquals("2 stops away" to "Next stop Jefferson St. Here in about 2 min.", top(coming, T0))
        assertEquals("3 stops away" to "Next stop Jefferson St. Here in about 3 min.", top(coming, T0 + 10, from = MONTROSE))
        val standing = seen(feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)))
        assertEquals("1 stop away" to "At Jefferson St now. Here in about 1 min.", top(standing, T0))
        // One stop away and moving, the count has said where it is.
        val between = standing.then(feed(T0 + 30, trip(MORGAN, T0 + 80)))
        assertEquals("1 stop away" to "Here in about 1 min.", top(between, T0 + 30))
        assertEquals("1 stop away" to "Here now.", top(between, T0 + 60))
        val here = between.then(feed(T0 + 90, trip(MORGAN, T0 + 85), standing = mapOf(ID to MORGAN)))
        assertEquals("At Morgan Av now" to "", top(here, T0 + 90))
    }

    @Test
    fun aHeldTrainIsSaidToBeStandingAndItsTimesAreNotRepeated() {
        val held = seen(feed(T0, trip(JEFFERSON, T0 - 200), standing = mapOf(ID to JEFFERSON)))
        assertEquals("1 stop away" to "Standing at Jefferson St since ${clockLabel(T0 - 200)}. The times below will slip.", top(held, T0))
        assertEquals(
            listOf("1", "stop away from Morgan Av", "Then 8 stops to 14 St-Union Sq.", "Standing at Jefferson St since ${clock(T0 - 200)}.", "Yours comes after 3 Av."),
            said(held, T0),
        )
    }

    @Test
    fun aTimetableSlotIsCalledScheduledAndNotATrain() {
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 600, assigned = false)))
        assertEquals("Scheduled for ${clockLabel(T0 + 690)}" to "It has not left Jefferson St yet. These are timetable times.", top(slot, T0))
        // Asked about at the stop it sets out from, the stop is not named twice.
        assertEquals("Scheduled for ${clockLabel(T0 + 600)}" to "It has not left yet. These are timetable times.", top(slot, T0, from = JEFFERSON))
        // On a ride each is a sentence of its own, so a long name never pushes the last of them off its two lines.
        assertEquals(
            listOf(null, "Scheduled for ${clock(T0 + 690)}", "It has not left Jefferson St yet.", "These are timetable times.", "Then 8 stops to 14 St-Union Sq.", "Yours comes after 3 Av."),
            said(slot, T0),
        )
        // Once that is no longer fresh it says since when, like anything else on the screen.
        assertEquals("No word since ${clock(T0)}.", said(slot, T0 + 61).last())
        assertEquals("Yours comes after 3 Av.", said(slot, T0 + 60).last())
        // A time that has gone by with no train is not still what it is scheduled for.
        val overdue = slot.then(feed(T0 + 740, trip(JEFFERSON, T0 + 600, assigned = false)))
        assertEquals("Scheduled for ${clockLabel(T0 + 690)}", top(overdue, T0 + 750).first)
        assertEquals("Was scheduled for ${clockLabel(T0 + 690)}", top(overdue, T0 + 751).first)
        // It has not left only until its time to set out. After that nobody knows: on the lettered lines the train
        // has mostly gone by then, and the MTA names it some minutes later. So only that is said.
        val setsOut = T0 + 600
        val due = slot.then(feed(setsOut - 20, trip(JEFFERSON, setsOut, assigned = false)))
        assertEquals("It has not left Jefferson St yet. These are timetable times.", top(due, setsOut - 1).second)
        assertEquals("No train reported yet. These are timetable times.", top(due, setsOut).second)
        assertEquals("It has not left yet. These are timetable times.", top(due, setsOut - 1, from = JEFFERSON).second)
        assertEquals("No train reported yet. These are timetable times.", top(due, setsOut + 1, from = JEFFERSON).second)
        assertEquals("No train reported yet. These are timetable times.", top(overdue, T0 + 751).second)
        assertEquals("It has not left Jefferson St yet.", said(due, setsOut - 1)[2])
        assertEquals(
            listOf(null, "Was scheduled for ${clock(T0 + 690)}", "No train reported yet.", "These are timetable times.", "Then 8 stops to 14 St-Union Sq.", "Yours comes after 3 Av."),
            said(overdue, T0 + 751),
        )
        assertEquals("No train reported yet.", RideWords.NO_TRAIN_YET)
        val noTime = slot.copy(stops = slot.stops.map { it.copy(time = 0) })
        assertEquals("On the timetable", top(noTime, T0).first)
    }

    @Test
    fun aTrainWaitingWhereItsRunStartsLeavesInSoManyMinutesAndIsNotCalledAStopAway() {
        // Due out of Jefferson St at +300: a time to leave and none to arrive, and no position.
        val waiting = trip(JEFFERSON, T0 + 300).let { it.copy(stops = listOf(it.stops.first().copy(departsOnly = true)) + it.stops.drop(1)) }
        val starting = seen(feed(T0, waiting))
        assertEquals("Leaves in about 5 min" to "It has not left yet.", top(starting, T0, from = JEFFERSON))
        assertEquals("Leaves now" to "It has not left yet.", top(starting.then(feed(T0 + 300, waiting)), T0 + 310, from = JEFFERSON))
        // Still there well after it was to go, it is not said to be leaving now.
        assertEquals("Was to leave at ${clockLabel(T0 + 300)}" to "It has not left yet.", top(starting.then(feed(T0 + 400, waiting)), T0 + 400, from = JEFFERSON))
        // From a stop down the line it is counted in, and said not to have set out.
        assertEquals("2 stops away" to "It has not left Jefferson St yet. Here in about 7 min.", top(starting, T0))
        // The rider waiting where it starts reads when it leaves, with the stop named: there is no figure to hang it on.
        assertEquals(
            listOf(null, "Leaves Jefferson St in about 5 min", "It has not left yet.", "Then 9 stops to 14 St-Union Sq.", "Yours comes after 3 Av."),
            said(starting, T0, from = JEFFERSON),
        )
        // Not heard of for a minute, nothing is said of it in the present.
        assertEquals(
            listOf(null, "Leaves Jefferson St in about 2 min", "Then 9 stops to 14 St-Union Sq.", "Yours comes after 3 Av.", "No word since ${clock(T0)}."),
            said(starting, T0 + 200, from = JEFFERSON),
        )
        // The same on its page, from where it starts and from down the line.
        assertEquals("Leaves in about 2 min" to "", top(starting, T0 + 200, from = JEFFERSON))
        assertEquals("2 stops away" to "Here in about 3 min.", top(starting, T0 + 200))
    }

    @Test
    fun aTrainThatHasGoneOrWillNotStopIsSaidSo() {
        assertEquals("Left Morgan Av about ${clockLabel(T0 + 120)}" to "Next stop Montrose Av.", top(aboard, T0 + 150))
        assertEquals("Left Morgan Av" to "Next stop Montrose Av.", top(seen(feed(T0, trip(MONTROSE, T0 + 30))), T0))
        // Reckoned past the platform with no word since, it is not said to have left. It is
        // reckoned past half a minute early, and "was due" is not said of a time still to come.
        assertEquals("Due at Morgan Av at ${clockLabel(T0 + 120)}" to "Next stop Montrose Av.", top(coming, T0 + 100))
        assertEquals("Was due at Morgan Av at ${clockLabel(T0 + 120)}" to "Next stop Montrose Av.", top(coming, T0 + 121))
        val skipping = coming.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(MORGAN))))
        assertEquals("Not stopping at Morgan Av" to "Its stops have changed. Its next stops are Jefferson St, then Montrose Av.", top(skipping, T0 + 60))
        // Its next stops are news for a minute. After that they are behind it.
        assertEquals("Not stopping at Morgan Av" to "Its stops have changed.", top(skipping, T0 + 121))
    }

    @Test
    fun whenThereIsNothingToListThePageSaysWhy() {
        assertNull(RideWords.instead(coming.seenFrom(MORGAN, T0)))
        assertEquals(
            "No word on this train since ${clockLabel(T0)}.\n\nCheck your connection.",
            RideWords.instead(coming.seenFrom(MORGAN, T0 + 301)),
        )
        val other = trip(JEFFERSON, T0 + 900, id = "091000_L..N")
        val lost = coming.then(feed(T0 + 60, other)).then(feed(T0 + 120, other))
        assertEquals(RideWords.GONE, RideWords.instead(lost.seenFrom(MORGAN, T0 + 120)))
        assertEquals(
            "The MTA has stopped reporting this train.\n\nIt may have been taken out of service, or it may now be shown as another train.",
            RideWords.GONE,
        )
        // Gone while nobody was looking, with every time it had gone by: it has most likely finished.
        val unseen = coming.then(feed(T0 + 1800, other)).then(feed(T0 + 1830, other))
        assertEquals("The MTA has stopped reporting this train.\n\nIt has most likely finished its run.", RideWords.instead(unseen.seenFrom(MORGAN, T0 + 1830)))
        // Past its last stop, and listed once more with a stop it has already made.
        val ended = seen(feed(T0, trip(SIXTH_AV, T0 + 20)))
            .then(feed(T0 + 60, trip(EIGHTH_AV, T0 + 80)))
            .then(feed(T0 + 120, trip(SIXTH_AV, T0 + 119, skipping = setOf(EIGHTH_AV))))
        assertEquals("This train has finished its run.", RideWords.instead(ended.seenFrom(MORGAN, T0 + 120)))
    }

    @Test
    fun waitingTheRiderReadsHowFarOffTheTrainIsAndHowFarTheyWillRide() {
        assertEquals(
            listOf("2", "stops away from Morgan Av", "Here in about 2 min.", "Then 8 stops to 14 St-Union Sq.", "Next stop Jefferson St.", "Yours comes after 3 Av."),
            said(coming, T0),
        )
        val here = seen(feed(T0, trip(MORGAN, T0 - 5), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf("Now", "at Morgan Av", "Then 8 stops to 14 St-Union Sq.", "Yours comes after 3 Av."), said(here, T0))
        // One stop is one stop. The stop before the rider's is then the one they board at,
        // and "Yours comes after Morgan Av." would tell them nothing: "Then 1 stop" has said it.
        assertEquals(listOf("Now", "at Morgan Av", "Then 1 stop to Montrose Av."), said(here, T0, to = MONTROSE))
        assertEquals(
            listOf("2", "stops away from Morgan Av", "Here in about 2 min.", "Then 1 stop to Montrose Av.", "Next stop Jefferson St."),
            said(coming, T0, to = MONTROSE),
        )
        // Aboard for that one stop, the next stop is theirs and nothing more needs saying.
        assertEquals(listOf("Next", "stop is Montrose Av", "Due about ${clock(T0 + 210)}."), said(aboard, T0 + 150, to = MONTROSE))
        // And with no word for five minutes, the one thing that cannot have gone stale is that it is the next stop.
        assertEquals(
            listOf(null, "Get off at Montrose Av.", "It is the next stop.", "At ${clock(T0)} the train was due there at ${clock(T0 + 210)}.", "No word since then, which is too long ago to count from."),
            said(coming, T0 + 301, to = MONTROSE),
        )
    }

    @Test
    fun ridingTheRiderReadsACountThenTheStopsToCheckItAgainst() {
        assertEquals(
            listOf("8", "stops to 14 St-Union Sq", "Due about ${clock(T0 + 840)}.", "Next is Montrose Av.", "Yours comes after 3 Av."),
            said(aboard, T0 + 150),
        )
        val inStation = aboard.then(feed(T0 + 200, trip(MONTROSE, T0 + 195), standing = mapOf(ID to MONTROSE)))
        assertEquals("At Montrose Av now.", said(inStation, T0 + 200)[3])
        assertEquals("7", said(inStation, T0 + 200)[0])
        // Two stops from theirs and on its way to the one before it: that stop is named once.
        val nearly = aboard.then(feed(T0 + 650, trip(THIRD_AV, T0 + 700)))
        assertEquals(listOf("2", "stops to 14 St-Union Sq", "Due about ${clock(T0 + 790)}.", "Next is 3 Av."), said(nearly, T0 + 650))
    }

    @Test
    fun aboardAHeldTrainTheRiderIsToldItIsStandingAndIsGivenNoTime() {
        // Held at Montrose Av since +215, with Union Sq still listed for the time it had before.
        val held = aboard.then(feed(T0 + 400, trip(MONTROSE, T0 + 215), standing = mapOf(ID to MONTROSE)))
        assertEquals(
            listOf("7", "stops to 14 St-Union Sq", "Standing at Montrose Av since ${clock(T0 + 215)}.", "Yours comes after 3 Av."),
            said(held, T0 + 400),
        )
        val short = aboard.then(feed(T0 + 900, trip(THIRD_AV, T0 + 700), standing = mapOf(ID to THIRD_AV)))
        assertEquals(listOf("Next", "stop is 14 St-Union Sq", "Standing at 3 Av since ${clock(T0 + 700)}."), said(short, T0 + 900))
        // Not held, only late: a time that has gone by is still not given as when the train is due.
        val onTime = aboard.then(feed(T0 + 900, trip(UNION_SQ, T0 + 890)))
        assertEquals(listOf("Next", "stop is 14 St-Union Sq", "Due about ${clock(T0 + 890)}."), said(onTime, T0 + 900))
        val slow = aboard.then(feed(T0 + 900, trip(UNION_SQ, T0 + 800)))
        assertEquals(listOf("Next", "stop is 14 St-Union Sq"), said(slow, T0 + 900))
        // The same goes for the train's own page: "Here now" is not said of a time long gone.
        val late = seen(feed(T0, trip(JEFFERSON, T0 - 100)))
        assertEquals("1 stop away" to "", top(late, T0, from = JEFFERSON))
        assertEquals("2 stops away" to "Next stop Jefferson St. Here now.", top(late, T0))
    }

    @Test
    fun onTheLetteredLinesATrainIsLastSeenAtAStopOnceItHasBeenThereAMinute() {
        fun atJefferson(at: Long, time: Long) = Train.find("gtfs-jz", feed(at, trip(JEFFERSON, time), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        val arrived = atJefferson(T0, T0 - 10)
        assertEquals("1 stop away" to "At Jefferson St now. Here in about 1 min.", top(arrived, T0))
        val still = arrived.then(feed(T0 + 30, trip(JEFFERSON, T0 + 20), standing = mapOf(ID to JEFFERSON)))
            .then(feed(T0 + 60, trip(JEFFERSON, T0 + 50), standing = mapOf(ID to JEFFERSON)))
        assertEquals("1 stop away" to "Last seen at Jefferson St. Here in about 1 min.", top(still, T0 + 60))
        // From its own platform it is said to have got there, and when. Nobody should run for it on that.
        assertEquals("At Jefferson St now" to "", top(arrived, T0, from = JEFFERSON))
        assertEquals("Reached Jefferson St about ${clockLabel(T0 - 10)}" to "It may have left.", top(still, T0 + 60, from = JEFFERSON))
        // Aboard, the same word, and the count is what it was.
        assertEquals(
            listOf("9", "stops to 14 St-Union Sq", "Due about ${clock(T0 + 50 + 9 * 90)}.", "Last seen at Jefferson St.", "Yours comes after 3 Av."),
            said(still, T0 + 60, from = "L16"),
        )
        // Waiting for it at Jefferson St: "Now" for its first minute, and after that only what is known.
        assertEquals(listOf("Now", "at Jefferson St", "Then 9 stops to 14 St-Union Sq.", "Yours comes after 3 Av."), said(arrived, T0, from = JEFFERSON))
        assertEquals(
            listOf(null, "Reached Jefferson St about ${clock(T0 - 10)}", "It may have left.", "Then 9 stops to 14 St-Union Sq.", "Yours comes after 3 Av."),
            said(still, T0 + 60, from = JEFFERSON),
        )
        // At the rider's own stop it is "Now" for as long as it is listed there: lingering is the safe side.
        assertEquals(listOf("Now", "at Jefferson St", "This is your stop."), said(still, T0 + 60, from = "L16", to = JEFFERSON).take(3))
        // In the list of other trains it is told the same way.
        assertEquals("At Jefferson St now", RideWords.whereabouts(arrived, T0, catalog))
        assertEquals("Last seen at Jefferson St", RideWords.whereabouts(still, T0 + 60, catalog))
    }

    @Test
    fun aTrainLastHeardOfBehindItsTimesIsSaidToBeWhereItWasAndGivenNoTime() {
        // Held at Jefferson St, then no word for two minutes.
        val held = seen(feed(T0, trip(JEFFERSON, T0 - 200), standing = mapOf(ID to JEFFERSON)))
        assertEquals("1 stop away" to "Last seen standing at Jefferson St.", top(held, T0 + 120))
        assertEquals("Last seen standing at Jefferson St" to "", top(held, T0 + 120, from = JEFFERSON))
        assertEquals(
            listOf(
                "1", "stop away from Morgan Av", "Then 8 stops to 14 St-Union Sq.", "Last seen standing at Jefferson St.", "Yours comes after 3 Av.",
                "No word since ${clock(T0)}. This is where it was then.",
            ),
            said(held, T0 + 120),
        )
        // The same held train with its stops six and a half minutes apart. Its time at Morgan Av is still ahead, and is
        // not counted from all the same: there is no "Here in about" for a train left where it was last seen.
        val slow = seen(feed(T0, trip(JEFFERSON, T0 - 200, every = 400), standing = mapOf(ID to JEFFERSON)))
        assertTrue(slow.stop(MORGAN)!!.time > T0 + 120)
        assertEquals("1 stop away" to "Last seen standing at Jefferson St.", top(slow, T0 + 120))
        assertEquals(
            listOf(
                "1", "stop away from Morgan Av", "Then 8 stops to 14 St-Union Sq.", "Last seen standing at Jefferson St.", "Yours comes after 3 Av.",
                "No word since ${clock(T0)}. This is where it was then.",
            ),
            said(slow, T0 + 120),
        )
        // Aboard a train that was late for its next stop: no "Due about", and the stop it was coming to is in the past.
        val late = aboard.then(feed(T0 + 300, trip(MONTROSE, T0 + 290)))
        assertEquals(
            listOf("8", "stops to 14 St-Union Sq", "Its next stop was Montrose Av.", "Yours comes after 3 Av.", "No word since ${clock(T0 + 300)}. This is where it was then."),
            said(late, T0 + 420),
        )
        assertFalse(RideWords.statement(Ride(late, MORGAN, UNION_SQ), T0 + 420, catalog).sure)
    }

    @Test
    fun theLastTwoStatesOfARideAreAWordEach() {
        val before = aboard.then(feed(T0 + 700, trip(THIRD_AV, T0 + 695), standing = mapOf(ID to THIRD_AV)))
        assertEquals(listOf("Next", "stop is 14 St-Union Sq", "Due about ${clock(T0 + 785)}.", "At 3 Av now."), said(before, T0 + 700))
        val between = before.then(feed(T0 + 760, trip(UNION_SQ, T0 + 800)))
        assertEquals(listOf("Next", "stop is 14 St-Union Sq", "Due about ${clock(T0 + 800)}."), said(between, T0 + 760))
        val there = between.then(feed(T0 + 800, trip(UNION_SQ, T0 + 795), standing = mapOf(ID to UNION_SQ)))
        assertEquals(listOf("Now", "at 14 St-Union Sq", "This is your stop."), said(there, T0 + 800))
        val gone = there.then(feed(T0 + 860, trip(SIXTH_AV, T0 + 900)))
        assertEquals(listOf(null, "This train has left 14 St-Union Sq.", "Its next stop is 6 Av."), said(gone, T0 + 860))
        // At the end of the line there is no next stop to name.
        val terminus = seen(feed(T0, trip(SIXTH_AV, T0 + 20))).then(feed(T0 + 60, trip(EIGHTH_AV, T0 + 80)))
        assertEquals(listOf(null, "This train has left 6 Av.", "Its next stop is 8 Av."), said(terminus, T0 + 60, to = SIXTH_AV))
        // A train first named beyond the rider's stop has left it too. It is in every report, and is not said to have gone from them.
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 30, assigned = false)))
        val named = slot.then(feed(T0 + 400, trip(MONTROSE, T0 + 390), standing = mapOf(ID to MONTROSE)))
        assertEquals(listOf(null, "This train has left Morgan Av.", "Its next stop is Montrose Av."), said(named, T0 + 400, from = JEFFERSON, to = MORGAN))
        assertEquals("Left Morgan Av" to "Next stop Montrose Av.", top(named, T0 + 400))
        assertEquals(listOf(null, "This train has left Morgan Av."), said(named, T0 + 500, from = JEFFERSON, to = MORGAN))
    }

    @Test
    fun aReckonedCountSaysSinceWhenAndIsMarkedUnsure() {
        val statement = RideWords.statement(Ride(aboard, MORGAN, UNION_SQ), T0 + 211, catalog)
        assertFalse(statement.sure)
        assertEquals("7", statement.figure)
        assertEquals("No word since ${clockLabel(T0 + 150)}. Counted from its times then.", statement.note)
        assertEquals(listOf("Next is Grand St.", "Yours comes after 3 Av."), statement.aside.map { it.replace('\u00A0', ' ') })
        assertTrue(RideWords.statement(Ride(aboard, MORGAN, UNION_SQ), T0 + 210, catalog).let { it.sure && it.note == null })
        // Reckoned to be at the rider's stop, it says that is a reckoning.
        val close = seen(feed(T0, trip(THIRD_AV, T0 + 60)))
        assertEquals(
            listOf("Now", "at 14 St-Union Sq", "By its last times, this is your stop.", "No word since ${clock(T0)}. Counted from its times then."),
            said(close, T0 + 120),
        )
    }

    @Test
    fun withNoWordForFiveMinutesTheRiderIsLeftWithWhatCannotGoStale() {
        assertEquals(
            listOf(
                null,
                "Get off at 14 St-Union Sq.",
                "It is the stop after 3 Av.",
                "At ${clock(T0 + 150)} the train was due there at ${clock(T0 + 840)}.",
                "No word since then, which is too long ago to count from.",
            ),
            said(aboard, T0 + 451),
        )
        // To a stop the feed gave no time for, the hour of the last word is all there is to give.
        val untimed = aboard.copy(stops = aboard.stops.map { if (it.platform == UNION_SQ) it.copy(time = 0) else it })
        assertEquals(
            listOf(null, "Get off at 14 St-Union Sq.", "It is the stop after 3 Av.", "No word since ${clock(T0 + 150)}, which is too long ago to count from."),
            said(untimed, T0 + 451),
        )
    }

    @Test
    fun aChangeToTheTrainsStopsIsDatedUnderTheCount() {
        val skipping = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf("L12", "L11"))))
        assertEquals(
            listOf("6", "stops to 14 St-Union Sq", "Due about ${clock(T0 + 215 + 7 * 90)}.", "Next is Montrose Av.", "Yours comes after 3 Av.", "Its stops changed at ${clock(T0 + 210)}."),
            said(skipping, T0 + 210),
        )
    }

    @Test
    fun aTrainsPageDatesAChangeToItsStopsUnlessItHasAlreadySaidTheyChanged() {
        assertEquals("Its stops changed at ${clockLabel(T0 + 210)}.", RideWords.changed(T0 + 210))
        val skipping = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf("L12", "L11"))))
        assertEquals(T0 + 210, skipping.changed)
        // From a platform it still stops at, the change is dated under the top two lines.
        assertEquals("Its stops changed at ${clockLabel(T0 + 210)}.", TrainPageWords.changed(skipping, skipping.seenFrom(UNION_SQ, T0 + 210)))
        assertEquals("Its stops changed at ${clockLabel(T0 + 210)}.", TrainPageWords.changed(skipping, skipping.seenFrom(MORGAN, T0 + 210)))
        // From one it now skips, "Its stops have changed." is already the second line.
        assertNull(TrainPageWords.changed(skipping, skipping.seenFrom("L12", T0 + 210)))
        assertEquals("Its stops have changed. Its next stops are Montrose Av, then Lorimer St.", top(skipping, T0 + 210, from = "L12").second)
        // A train whose stops are as it first listed them has nothing to date.
        assertNull(TrainPageWords.changed(aboard, aboard.seenFrom(UNION_SQ, T0 + 150)))
    }

    @Test
    fun aStopTakenAwayOrATrainGoneIsTheWholeStatement() {
        val skipping = aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(THIRD_AV, UNION_SQ))))
        assertEquals(
            listOf(null, "This train no longer stops at 14 St-Union Sq.", "Its next stops are Montrose Av, then Grand St."),
            said(skipping, T0 + 210),
        )
        // A minute on, its next stops are behind it and are not named, and the statement says how old it is.
        assertEquals(
            listOf(null, "This train no longer stops at 14 St-Union Sq.", "No word since ${clock(T0 + 210)}."),
            said(skipping, T0 + 271),
        )
        val notHere = coming.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(MORGAN))))
        assertEquals("This train no longer stops at Morgan Av.", said(notHere, T0 + 60)[1])
        val other = trip(JEFFERSON, T0 + 900, id = "091000_L..N")
        val lost = aboard.then(feed(T0 + 210, other)).then(feed(T0 + 270, other))
        assertEquals(
            listOf(
                null,
                "The MTA has stopped reporting this train.",
                "It was last reported at ${clock(T0 + 150)}.",
                "It was due at 14 St-Union Sq at ${clock(T0 + 840)}.",
                "Yours comes after 3 Av.",
            ),
            said(lost, T0 + 270),
        )
        // Come back to long after its times had all gone by, it has most likely finished.
        val unseen = aboard.then(feed(T0 + 2000, other)).then(feed(T0 + 2030, other))
        assertEquals(
            listOf(null, "The MTA has stopped reporting this train.", "It was last reported at ${clock(T0 + 150)}.", "It was due at 14 St-Union Sq at ${clock(T0 + 840)}.", "It has most likely finished its run."),
            said(unseen, T0 + 2030),
        )
    }

    @Test
    fun aTrainThatFinishedItsRunHasLeftAStopShortOfItsLastAndIsNotSaidToBeThere() {
        // Let go of by the feed as it left 6 Av, where the rider was getting off.
        val atSixth = seen(feed(T0, trip(SIXTH_AV, T0 - 20), standing = mapOf(ID to SIXTH_AV)))
        val gone = atSixth.then(feed(T0 + 30)).then(feed(T0 + 60)).then(feed(T0 + 90))
        assertEquals(
            listOf(null, "This train has left 6 Av.", "It was due there about ${clock(T0 - 20)} and has finished its run."),
            said(gone, T0 + 90, from = UNION_SQ, to = SIXTH_AV),
        )
        // For the half minute it may only have been missed, it is there by its last times.
        assertEquals(
            listOf("Now", "at 6 Av", "By its last times, this is your stop.", "No word since ${clock(T0)}. Counted from its times then."),
            said(atSixth.then(feed(T0 + 30)), T0 + 30, from = UNION_SQ, to = SIXTH_AV),
        )
        assertEquals(listOf(null, "This train has left 6 Av.", "It has finished its run."),
            said(gone.copy(stops = gone.stops.map { it.copy(time = 0) }), T0 + 90, from = UNION_SQ, to = SIXTH_AV))
    }

    @Test
    fun aStopWithNoTimeGetsNoTime() {
        val day = Fixtures.weekdayAfternoon.feeds.getValue("gtfs-si")
        val train = Train.of("gtfs-si", day, day.trips.first { it.stops.size > 5 }, catalog)
        val last = train.stops.last().platform
        val statement = RideWords.statement(Ride(train, from = "S00", to = last), day.timestamp, catalog)
        assertEquals("stops to ${RideWords.whole(catalog.stopName(last))}", statement.headline)
        assertTrue(statement.body.isEmpty())
    }

    @Test
    fun aStopsNameIsNotBrokenAcrossTwoLinesUnlessItIsTooLongForOne() {
        assertEquals("14\u00A0St-Union\u00A0Sq", RideWords.whole("14 St-Union Sq"))
        assertEquals("8\u00A0Av", RideWords.whole("8 Av"))
        assertEquals("Canarsie-Rockaway Pkwy", RideWords.whole("Canarsie-Rockaway Pkwy"))
        val statement = RideWords.statement(Ride(aboard, MORGAN, UNION_SQ), T0 + 150, catalog)
        assertEquals("stops to 14\u00A0St-Union\u00A0Sq", statement.headline)
        assertEquals(listOf("Next is Montrose\u00A0Av.", "Yours comes after 3\u00A0Av."), statement.aside)
    }

    @Test
    fun anotherTrainIsToldByWhatARiderOnItWouldHearOrSee() {
        assertEquals("Next stop Jefferson St", RideWords.whereabouts(coming, T0, catalog))
        val standing = seen(feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)))
        assertEquals("At Jefferson St now", RideWords.whereabouts(standing, T0, catalog))
        val held = seen(feed(T0, trip(JEFFERSON, T0 - 200), standing = mapOf(ID to JEFFERSON)))
        assertEquals("Standing at Jefferson St since ${clockLabel(T0 - 200)}", RideWords.whereabouts(held, T0, catalog))
        // The rest of the list's words.
        assertEquals("Trains on their way to 14 St-Union Sq. Pick the one whose next stop matches what you hear or see.", OtherTrainWords.pick("14 St-Union Sq"))
        assertEquals("the one you are following", OtherTrainWords.FOLLOWED)
        assertEquals("Loading…", OtherTrainWords.instead(OtherTrains.Choice.View.Loading, "8 Av"))
        assertEquals("Times unavailable. Check your connection.", OtherTrainWords.instead(OtherTrains.Choice.View.Unavailable, "8 Av"))
        assertEquals("No trains on their way to 8 Av right now.", OtherTrainWords.instead(OtherTrains.Choice.View.Trains(emptyList(), live = true), "8 Av"))
        assertNull(OtherTrainWords.instead(OtherTrains.Choice.View.Trains(listOf(coming), live = false), "8 Av"))
        assertEquals("Not live. Trains as of ${clockLabel(T0)}.", notLive(T0, "Trains"))
        assertEquals("Not live. Times as of ${clockLabel(T0)}.", notLive(T0))
    }

    @Test
    fun aTrainsPageSaysLoadingWhileItIsAskedAboutAndBlamesTheConnectionOnlyOnceItWasTried() {
        fun instead(following: LiveTrain.Following, at: Long = T0) = TrainPageWords.instead(following, following.train?.seenFrom(MORGAN, at))
        assertEquals("Loading…", instead(LiveTrain.Following(loading = true)))
        assertEquals("Times unavailable. Check your connection.", instead(LiveTrain.Following(failed = true)))
        // Looked for and not there: nearly every train that leaves the MTA's reports has finished.
        assertEquals(RideWords.FINISHED_UNSEEN, instead(LiveTrain.Following()))
        assertNull(instead(LiveTrain.Following(coming)))
        // Come back to after five minutes: asked about first, and only then is the connection named.
        assertEquals("Loading…", instead(LiveTrain.Following(coming, loading = true), at = T0 + 301))
        assertEquals("No word on this train since ${clockLabel(T0)}.\n\nCheck your connection.", instead(LiveTrain.Following(coming), at = T0 + 301))
        // A train that can still be shown is shown while it is asked about.
        assertNull(instead(LiveTrain.Following(coming, loading = true), at = T0 + 200))
        assertEquals("Tap your stop to count down to it.", TrainPageWords.TAP)
    }

    @Test
    fun noStatementAsksForMoreRoomThanThePageThatDoesNotScrollCanGive() {
        // Every train in the system on a Monday midday, ridden from its first stops to a few of its later ones,
        // looked at fresh and two minutes stale. The page has room for a figure, a headline and about five lines
        // of sentences. What cannot be measured here is the typeface, so this only catches a statement that grows.
        val day = Fixtures.weekdayMidday
        var statements = 0
        day.feeds.forEach { (slug, feed) ->
            feed.trips.forEach { trip ->
                val train = Train.of(slug, feed, trip, catalog)
                train.stops.take(2).forEachIndexed { on, from ->
                    listOf(on + 1, on + 4, train.stops.lastIndex).filter { it > on && it < train.stops.size }.distinct().forEach { off ->
                        listOf(day.time, day.time + 120).forEach { at ->
                            val statement = RideWords.statement(Ride(train, from.platform, train.stops[off].platform), at, catalog)
                            assertTrue(statement.body.size + statement.aside.size <= 5, "${trip.id}: ${statement.body + statement.aside}")
                            // A grey figure is never left unexplained, and the screen keeps room for the note that explains it.
                            if (!statement.sure) assertNotNull(statement.note, trip.id)
                            if (at > feed.timestamp + Train.LIVE_FOR) assertNotNull(statement.note, "${trip.id}: ${statement.headline}")
                            statements++
                        }
                    }
                }
            }
        }
        assertTrue(statements > 5_000, "$statements statements")
    }

    @Test
    fun noSentenceARiderReadsNamesWhereTheTimesComeFrom() {
        // The words a rider on a platform would not use for any of this.
        val data = Regex("\\b(feed|feeds|trip|trips|update|updated|download|slot|data)\\b", RegexOption.IGNORE_CASE)
        val other = trip(JEFFERSON, T0 + 900, id = "091000_L..N")
        val trains = listOf(
            coming, aboard,
            seen(feed(T0, trip(JEFFERSON, T0 + 600, assigned = false))),
            seen(feed(T0, trip(JEFFERSON, T0 - 200), standing = mapOf(ID to JEFFERSON))),
            aboard.then(feed(T0 + 210, other)).then(feed(T0 + 270, other)),
            aboard.then(feed(T0 + 2000, other)).then(feed(T0 + 2030, other)),
            aboard.then(feed(T0 + 210, trip(MONTROSE, T0 + 215, skipping = setOf(THIRD_AV, UNION_SQ)))),
            seen(feed(T0, trip(SIXTH_AV, T0 - 20), standing = mapOf(ID to SIXTH_AV))).then(feed(T0 + 30)).then(feed(T0 + 120)),
        )
        val read = ArrayList<String>()
        trains.forEach { train ->
            listOf(MORGAN, SIXTH_AV).forEach { to ->
                (0L..2400L step 37).forEach { later ->
                    val at = train.asOf + later
                    RideWords.statement(Ride(train, JEFFERSON, to), at, catalog).let { read += listOfNotNull(it.figure, it.headline, it.note) + it.body + it.aside }
                    val sighting = train.seenFrom(to, at)
                    RideWords.top(sighting, catalog.stopName(to), at, catalog).let { read += listOf(it.head, it.detail) }
                    RideWords.instead(sighting)?.let { read += it }
                }
            }
        }
        read += listOf(
            RideWords.GONE, RideWords.FINISHED_UNSEEN, RideWords.TIMETABLE, RideWords.NO_TRAIN_YET, RideWords.changed(T0),
            TrainPageWords.TAP, DirectWords.TAP, OtherTrainWords.pick("8 Av"), OtherTrainWords.FOLLOWED, notLive(T0),
        )
        assertTrue(read.distinct().size > 60, "${read.distinct().size} sentences")
        read.distinct().forEach { sentence -> assertNull(data.find(sentence), sentence) }
    }

    @Test
    fun theStationPagesHintNamesOnlyWhatHasNotBeenTapped() {
        assertEquals("Brooklyn  ·  tap a line to favorite, a train to follow", StationHint.of("Brooklyn", hasFavorites = false, openedATrain = false))
        assertEquals("Brooklyn  ·  tap a line to favorite", StationHint.of("Brooklyn", hasFavorites = false, openedATrain = true))
        assertEquals("Brooklyn  ·  tap a train to follow it", StationHint.of("Brooklyn", hasFavorites = true, openedATrain = false))
        assertEquals("Staten Island", StationHint.of("Staten Island", hasFavorites = true, openedATrain = true))
    }
}
