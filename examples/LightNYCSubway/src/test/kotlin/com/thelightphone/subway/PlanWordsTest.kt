package com.thelightphone.subway

import com.thelightphone.subway.data.Direct
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Planner
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.clockLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The way from one station to another as the rider reads it, from every train running on a weekday afternoon. */
class PlanWordsTest {
    private val day = Fixtures.weekdayAfternoon
    private val catalog = Fixtures.catalog
    private val now = day.time

    private fun plan(from: Station, to: Station): Plan =
        Planner.plan(from, to, catalog, Planner.slugs(from, to).associateWith { day.feeds.getValue(it) }, now)

    private fun Plan.train(id: String): Direct = trains.first { it.train.id == id }

    /** A row from top to bottom, as the list of trains words it, with ordinary spaces: on the screen some are kept from breaking. */
    private fun Plan.said(id: String, at: Long = now, here: Boolean = false): List<String?> =
        PlanWords.row(train(id), this, at, catalog, here)
            .let { listOf(it.leaves, it.arrives, it.detail) + listOfNotNull(it.warning) }
            .map { it?.replace('\u00A0', ' ') }

    /** A clock time as [said] gives it, with an ordinary space. */
    private fun clock(time: Long) = clockLabel(time).replace('\u00A0', ' ')

    private val morgan = Fixtures.station("Morgan Av")
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val grandCentral = Fixtures.station("Grand Central-42 St")

    @Test
    fun aMovingTrainLeavesInAboutSoManyMinutesAndArrivesAboutATime() {
        val plan = plan(morgan, unionSq)
        val train = plan.train("094300_L..N")
        assertEquals(
            listOf("Leaves in about 2 min", "Arrives about ${clock(train.arrives)}", "Manhattan  ·  to 8 Av  ·  8 stops"),
            plan.said("094300_L..N"),
        )
        assertTrue(PlanWords.row(train, plan, now, catalog).sure)
        // The minutes count down on the clock alone, and the last half minute is "now".
        assertEquals("Leaves in about 1 min", plan.said("094300_L..N", at = train.leaves - 89)[0])
        assertEquals("Leaves now", plan.said("094300_L..N", at = train.leaves - 29)[0])
        assertEquals("Leaves now", plan.said("094300_L..N", at = train.leaves + 30)[0])
    }

    @Test
    fun onThePageOfTheStationItIsGoingToATrainLeavesThereAndArrivesHere() {
        val plan = plan(morgan, unionSq)
        val train = plan.train("094300_L..N")
        assertEquals(
            listOf("Leaves there in about 2 min", "Arrives here about ${clock(train.arrives)}", "Manhattan  ·  to 8 Av  ·  8 stops"),
            plan.said("094300_L..N", here = true),
        )
        assertEquals("Leaves there now", plan.said("094300_L..N", at = train.leaves, here = true)[0])
    }

    @Test
    fun aTrainOnlyOnTheTimetableLeavesAndArrivesAtATimeAndIsNeverAbout() {
        val plan = plan(unionSq, Fixtures.station("23 St-Baruch College"))
        val slot = plan.train("096200_6..N03R")
        assertFalse(slot.running)
        // The same two lines as a moving train's, with "at" for "about", and a line under them that says whose times they are.
        assertEquals(
            listOf("Leaves at ${clock(slot.leaves)}", "Arrives at ${clock(slot.arrives)}", "Uptown  ·  to Parkchester  ·  1 stop", "These are timetable times."),
            plan.said("096200_6..N03R"),
        )
        assertEquals(
            listOf("Leaves there at ${clock(slot.leaves)}", "Arrives here at ${clock(slot.arrives)}", "Uptown  ·  to Parkchester  ·  1 stop", "These are timetable times."),
            plan.said("096200_6..N03R", here = true),
        )
        assertFalse(PlanWords.row(slot, plan, now, catalog).sure)
        // An express is told from the local by its diamond and by where it ends.
        assertEquals("Uptown  ·  to Pelham Bay Park  ·  1 stop", plan.said("096250_6..N02R")[2])
        assertEquals("6X", plan.train("096250_6..N02R").route)
    }

    @Test
    fun aPlatformThatGoesByAnotherNameThanItsStationIsNamed() {
        // The L's platforms inside the two stations called 14 St are 6 Av and 8 Av.
        val plan = plan(Fixtures.station("14 St", "F"), Fixtures.station("14 St", "A"))
        val train = plan.train("092850_L..N")
        assertEquals(
            listOf("Leaves 6 Av in about 7 min", "Arrives at 8 Av about ${clock(train.arrives)}", "West Side  ·  to 8 Av  ·  1 stop"),
            plan.said("092850_L..N"),
        )
        // On the station's own page the platforms are still named, not "there" and "here".
        assertEquals("Leaves 6 Av in about 7 min", plan.said("092850_L..N", here = true)[0])
        assertEquals("Arrives at 8 Av about ${clock(train.arrives)}", plan.said("092850_L..N", here = true)[1])
    }

    @Test
    fun whereTheFeedGivesNoArrivalTimeNoneIsSaid() {
        // This snapshot holds no positions, so a railway trip nineteen minutes from setting out reads
        // here as a train. With them it is a trip on the timetable, and worded as one: see PlannerPositionsTest.
        val plan = plan(Fixtures.station("Tottenville"), Fixtures.station("St George"))
        val said = PlanWords.row(plan.train("097900_SI.N03R"), plan, now, catalog)
        assertEquals("Leaves in about 19 min", said.leaves)
        assertNull(said.arrives)
        assertEquals("Ferry  ·  to St George  ·  20 stops", said.detail.replace('\u00A0', ' '))
        assertNull(PlanWords.row(plan.train("097900_SI.N03R"), plan, now, catalog, here = true).arrives)
        // A stretch of stops with no times takes the arrival with it, and the platform's name is still worth saying.
        val canal = plan(Fixtures.station("Atlantic Av-Barclays Ctr"), Fixtures.station("Canal St", "N"))
        assertNull(PlanWords.row(canal.train("094000_N..N35R"), canal, now, catalog).arrives)
    }

    @Test
    fun aHeadingThatIsTheLastStopIsNotSaidTwice() {
        // The shuttle's platform at Grand Central is signed for where the shuttle ends.
        val plan = plan(grandCentral, Fixtures.station("Times Sq-42 St"))
        assertEquals("Times Sq", plan.train("096100_GS.N04R").heading)
        assertEquals("to Times Sq-42 St  ·  1 stop", plan.said("096100_GS.N04R")[2])
        assertEquals("Hudson Yards", plan.train("092950_7..S").heading)
        assertEquals("to 34 St-Hudson Yards  ·  2 stops", plan.said("092950_7..S")[2])
    }

    @Test
    fun aTrainBeingHeldIsSaidToBeStandingAndItsTimesAreDrawnLighter() {
        val t0 = 1_794_500_000L
        val trip = GtfsRealtime.Trip(
            route = "4",
            stops = listOf("640N" to t0 - 200, "635N" to t0 + 100, "631N" to t0 + 340).map { GtfsRealtime.StopTime(it.first, it.second) },
            id = "held",
        )
        val feed = GtfsRealtime.Feed(t0, listOf(trip), mapOf("held" to GtfsRealtime.Position("640N", GtfsRealtime.Status.STOPPED, t0 - 5)))
        val plan = Planner.plan(unionSq, grandCentral, catalog, mapOf("gtfs" to feed), t0)
        val said = PlanWords.row(plan.trains.single(), plan, t0, catalog)
        assertEquals("Standing at Brooklyn Bridge-City Hall since ${clockLabel(t0 - 200)}. Its times will slip.", said.warning)
        assertFalse(said.sure)
        assertEquals("Leaves in about 2 min", said.leaves)
        // With no word of where the train is, nothing is said of it.
        val moving = Planner.plan(unionSq, grandCentral, catalog, mapOf("gtfs" to feed.copy(positions = emptyMap())), t0)
        assertNull(PlanWords.row(moving.trains.single(), moving, t0, catalog).warning)
    }

    @Test
    fun aTrainsSmallLineBreaksOnlyBetweenItsPartsAndNeverBeforeADot() {
        val plan = plan(morgan, unionSq)
        assertEquals("Manhattan\u00A0\u00A0·  to\u00A08\u00A0Av\u00A0\u00A0·  8\u00A0stops", PlanWords.row(plan.train("094300_L..N"), plan, now, catalog).detail)
        // The only places a line can end are after a dot, or inside a name at its own hyphen.
        listOf(unionSq to grandCentral, Fixtures.station("14 St", "A") to Fixtures.station("14 St", "F"), Fixtures.station("Tottenville") to Fixtures.station("St George")).forEach { (from, to) ->
            val trains = plan(from, to)
            trains.trains.forEach { train ->
                val detail = PlanWords.row(train, trains, now, catalog).detail
                assertTrue(Regex(" +").findAll(detail).all { detail.substring(0, it.range.first).endsWith("\u00A0\u00A0·") }, detail)
            }
        }
    }

    @Test
    fun whatCouldNotBeCheckedIsNamedByLine() {
        assertEquals("Could not check the L just now.", PlanWords.unchecked(listOf("L")))
        assertEquals("Could not check the A C E just now.", PlanWords.unchecked(listOf("A", "C", "E")))
        // With no line to name, something could not be looked at all the same.
        assertEquals("Could not check just now.", PlanWords.unchecked(emptyList()))
    }

    @Test
    fun searchOpenedFromAStationSaysWhatOpeningAStationWillShow() {
        assertEquals("Open a station to see trains to it from Morgan Av.", PlanWords.searching(morgan))
        // It holds up with the longest name there is: one sentence, and the name whole.
        val longest = catalog.stations.maxBy { it.name.length }
        assertEquals("Sutphin Blvd-Archer Av-JFK Airport", longest.name)
        assertEquals("Open a station to see trains to it from Sutphin Blvd-Archer Av-JFK Airport.", PlanWords.searching(longest))
        assertTrue(catalog.stations.all { PlanWords.searching(it).length <= 76 })
    }

    @Test
    fun theListOfTrainsSaysLoadingUntilItHasAnAnswerAndNoDirectTrainOnlyWhenThatIsKnown() {
        val whole = plan(morgan, unionSq)
        fun instead(following: LivePlan.Following, at: Long = now, later: Boolean = false) = DirectWords.instead(following, following.plan?.view(at), later)
        assertEquals("Loading…", instead(LivePlan.Following()))
        assertNull(instead(LivePlan.Following(whole, loading = false)))
        // Trains in hand stay up while they are fetched again.
        assertNull(instead(LivePlan.Following(whole, loading = true)))
        // Too old to offer anything, with an answer on its way: loading, not a failure.
        val old = now + Planner.FRESH_FOR + 30
        assertEquals("Loading…", instead(LivePlan.Following(whole, loading = true), at = old))
        assertEquals("Could not check the L just now.\n\nCheck your connection.", instead(LivePlan.Following(whole, loading = false), at = old))
        // The L's feed could not be had: the list that had trains does not say there are none.
        val without = Planner.plan(morgan, unionSq, catalog, mapOf("gtfs" to day.feeds.getValue("gtfs"), "gtfs-nqrw" to day.feeds.getValue("gtfs-nqrw")), now)
        assertEquals("Could not check the L just now.\n\nCheck your connection.", instead(LivePlan.Following(without, loading = false)))
        // Nothing runs from Morgan Av to Times Sq, and with every feed in hand that is said.
        val timesSq = Fixtures.station("Times Sq-42 St")
        val none = plan(morgan, timesSq)
        assertEquals("No direct train from Morgan Av to Times Sq-42 St right now.", instead(LivePlan.Following(none, loading = false)))
        assertEquals("No later direct train from Morgan Av to Times Sq-42 St right now.", instead(LivePlan.Following(none, loading = false), later = true))
        // With one of them missing it is not: a train can run between two stations that share no line.
        val partly = Planner.plan(morgan, timesSq, catalog, mapOf("gtfs-l" to day.feeds.getValue("gtfs-l")), now)
        assertEquals("Could not check just now.\n\nCheck your connection.", instead(LivePlan.Following(partly, loading = false)))
        assertEquals("Tap a train to count down its stops.", DirectWords.TAP)
    }

    @Test
    fun theRestOfThePageSaysWhereAndHowMany() {
        val plan = plan(morgan, unionSq)
        assertEquals("From Morgan Av", PlanWords.from(morgan))
        assertEquals("To 14 St-Union Sq", PlanWords.to(unionSq))
        assertEquals("Tap to follow a train.", PlanWords.TAP)
        assertEquals("1 other train to choose from.", PlanWords.others(1))
        assertEquals("3 other trains to choose from.", PlanWords.others(3))
        assertEquals("No direct train from Morgan Av to 14 St-Union Sq right now.", PlanWords.none(plan, later = false))
        assertEquals("No later direct train from Morgan Av to 14 St-Union Sq right now.", PlanWords.none(plan, later = true))
        assertEquals("No direct train right now.", PlanWords.NONE_HERE)
        assertEquals("Trains that leave after about ${clockLabel(now)}.", PlanWords.after(now))
    }

    @Test
    fun everyTimeInEveryRowIsAboutOrScheduled() {
        listOf(morgan to unionSq, unionSq to grandCentral, Fixtures.station("Broad Channel") to Fixtures.station("Rockaway Park-Beach 116 St")).forEach { (from, to) ->
            val plan = plan(from, to)
            plan.trains.forEach { train ->
                val row = PlanWords.row(train, plan, now, catalog, here = true)
                val lines = listOfNotNull(row.leaves, row.arrives)
                if (train.running) {
                    assertTrue(lines.all { "about" in it || it.endsWith("now") }, lines.toString())
                    assertTrue(row.warning != "These are timetable times.")
                } else {
                    assertTrue(lines.all { " at " in it } && lines.none { "about" in it }, lines.toString())
                    assertEquals("These are timetable times.", row.warning)
                    assertFalse(row.sure)
                }
            }
        }
    }

    @Test
    fun aTrainsTwoMainLinesAreShortEnoughForOneLineEach() {
        // Beside the bullet a line of the kit's Copy type has room for about 32 characters of
        // this length on the emulator, and the phone's type may be a little larger. Where
        // only the station's own name has to be said, no line is longer than the longest a
        // moving train can have, and the timetable's are shorter than that.
        val widest = "Leaves there in about 59 min".length
        assertEquals(28, widest)
        var rows = 0
        listOf(
            morgan to unionSq, unionSq to grandCentral, Fixtures.station("Franklin Av", "S") to Fixtures.station("Prospect Park"),
            Fixtures.station("Tottenville") to Fixtures.station("St George"), Fixtures.station("Broad Channel") to Fixtures.station("Rockaway Park-Beach 116 St"),
        ).forEach { (from, to) ->
            val plan = plan(from, to)
            // A platform that goes by another name than its station is the one thing that can run a line over.
            plan.trains.filter { catalog.stopName(it.from) == from.name && catalog.stopName(it.to) == to.name }.forEach { train ->
                listOf(true, false).forEach { here ->
                    val row = PlanWords.row(train, plan, now, catalog, here)
                    listOfNotNull(row.leaves, row.arrives).forEach { line ->
                        assertTrue(line.length <= widest || " min" in line, line)
                        if (!train.running) assertTrue(line.length <= "Arrives here at 12:55 PM".length, line)
                    }
                    rows++
                }
            }
        }
        assertTrue(rows > 100)
        // The widest time there is, on the page of the station found and in the list.
        val slot = plan(Fixtures.station("Franklin Av", "S"), Fixtures.station("Prospect Park")).trains.first()
        assertFalse(slot.running)
        val widestTime = slot.copy(leaves = at1255, arrives = at1255 + 180)
        val planned = plan(Fixtures.station("Franklin Av", "S"), Fixtures.station("Prospect Park"))
        val onThePage = PlanWords.row(widestTime, planned, now, catalog, here = true)
        assertEquals("Leaves there at ${clockLabel(at1255)}" to "Arrives here at ${clockLabel(at1255 + 180)}", onThePage.leaves to onThePage.arrives)
        assertEquals(24, onThePage.leaves.length)
    }

    /** Some moment that reads 12:55 on the phone, which is as wide as a time gets. */
    private val at1255: Long = (0L until 86_400 step 60).map { now - now % 60 + it }.first { clockLabel(it).startsWith("12:55") }
}
