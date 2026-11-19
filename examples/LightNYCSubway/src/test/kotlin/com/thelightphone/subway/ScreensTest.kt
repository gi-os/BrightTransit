package com.thelightphone.subway

import com.thelightphone.subway.data.Arrival
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Direction
import com.thelightphone.subway.data.Directions
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.ServiceAlert
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.ui.HEADINGS_ON_HOME
import com.thelightphone.subway.ui.TIMES_SHOWN
import com.thelightphone.subway.ui.byLine
import com.thelightphone.subway.ui.clockLabel
import com.thelightphone.subway.ui.glance
import com.thelightphone.subway.ui.lastStop
import com.thelightphone.subway.ui.named
import com.thelightphone.subway.ui.namesScheduledTrains
import com.thelightphone.subway.ui.rowsWith
import com.thelightphone.subway.ui.timeWords
import com.thelightphone.subway.ui.timesLabel
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How a line of times is worded. */
class TimesWordingTest {
    @Test
    fun minIsSaidOnceAfterTheLastTime() {
        assertEquals("2, 9, 17 min", timesLabel(listOf(2, 9, 17)))
        assertEquals("1 min", timesLabel(listOf(1)))
    }

    @Test
    fun aTrainAtThePlatformIsNowAndTakesNoUnit() {
        assertEquals("Now, 6, 14 min", timesLabel(listOf(0, 6, 14)))
        assertEquals("Now", timesLabel(listOf(0)))
        // Two trains can both be in the station, one behind the other.
        assertEquals("Now, Now", timesLabel(listOf(0, 0)))
    }

    @Test
    fun aTrainStillAtThePlatformAMomentPastItsTimeIsNowAndNotANegativeNumber() {
        val train = Arrival("L", Direction.NORTH, epochSeconds = 1_000, heading = "Manhattan", destination = "8 Av")
        assertFalse(train.departed(1_020))
        assertEquals("Now", timesLabel(listOf(train.minutesFrom(1_020))))
        assertEquals("Now", timesLabel(listOf(-1)))
    }

    @Test
    fun theUnitGoesWithTheLastNumberWhereverThatIs() {
        // A favorite line named first on Home can come after the trains named behind it.
        assertEquals(listOf("8 min", "Now", "Now"), timeWords(listOf(8, 0, 0)))
        assertEquals(listOf("8", "Now", "2 min"), timeWords(listOf(8, 0, 2)))
    }

    @Test
    fun noTrainsIsNoWords() {
        assertEquals(emptyList(), timeWords(emptyList()))
        assertEquals("", timesLabel(emptyList()))
    }

    @Test
    fun aClockTimeReadsAsTheHourAndMinuteItIs() {
        // Every "Due about", "Left about" and "Scheduled for" in the tool is this function.
        // Each moment here has seconds that differ from its minutes, so neither can stand in for the other.
        val newYork = TimeZone.getTimeZone("America/New_York")
        fun said(time: Long) = clockLabel(time, newYork).replace('\u00A0', ' ')
        assertEquals("11:13 AM", said(1_794_500_010))
        assertEquals("2:43 PM", said(1_794_512_625))
        // The hour after noon and the hour after midnight are twelve, not nought.
        assertEquals("12:05 PM", said(1_794_503_145))
        assertEquals("12:05 AM", said(1_794_459_930))
        // In summer the clocks are an hour on.
        assertEquals("9:05 AM", said(1_782_911_140))
        // Asked without a zone it is the phone's own time.
        assertEquals(clockLabel(1_794_500_010, TimeZone.getDefault()), clockLabel(1_794_500_010))
    }

    @Test
    fun aClockTimeIsNeverBrokenBeforeItsAmOrPm() {
        // Whatever the hour, and whichever zone the tests are run in.
        (0L until 24).forEach { hour ->
            assertTrue(Regex("\\d{1,2}:\\d\\d\u00A0[AP]M").matches(clockLabel(Fixtures.ALERTS_BUILT + hour * 3600)))
        }
    }
}

/** Which trains a station's card on Home names, from every train running on a weekday afternoon. */
class HomeGlanceTest {
    private val day = Fixtures.weekdayAfternoon
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val timesSq = Fixtures.station("Times Sq-42 St")

    private fun heading(station: Station, name: String) = day.board(station).groups(day.time).first { it.heading == name }

    private fun List<Arrival>.said() = map { "${it.route} ${it.minutesFrom(day.time)}" }

    @Test
    fun aHeadingNamesItsNextThreeTrainsWhicheverLinesTheyAreOn() {
        val uptown = heading(unionSq, "Uptown")
        assertEquals(listOf("N 1", "6 1", "4 3"), uptown.glance(favorites = emptySet()).said())
        assertEquals(uptown.arrivals.take(TIMES_SHOWN), uptown.glance(favorites = emptySet()))
    }

    @Test
    fun aFavoriteLineIsNamedFirstThenTheSoonestOfTheRest() {
        val uptown = heading(unionSq, "Uptown")
        assertEquals(listOf("Q 3", "N 1", "6 1"), uptown.glance(setOf("Q")).said())
    }

    @Test
    fun aFavoriteLineIsNamedEvenWhenThreeTrainsComeBeforeIt() {
        // Downtown from Times Sq the next Q was six minutes off, with more than three trains due before it.
        val downtown = heading(timesSq, "Downtown")
        assertTrue(downtown.arrivals.indexOfFirst { it.route == "Q" } >= TIMES_SHOWN)
        assertEquals(listOf("Q 6", "W 0", "C 0"), downtown.glance(setOf("Q")).said())
    }

    @Test
    fun twoFavoriteLinesAreNamedInTheOrderTheyCome() {
        val uptown = heading(unionSq, "Uptown")
        assertEquals(listOf("4 3", "Q 3", "N 1"), uptown.glance(setOf("Q", "4")).said())
    }

    @Test
    fun aFavoriteCoversItsExpress() {
        // Toward Queens from Times Sq the express 7 was the next train, and a favorite 7 means both.
        val queens = heading(timesSq, "Queens")
        assertEquals(listOf("7X 0", "7 6", "7X 10"), queens.glance(setOf("7")).said())
    }

    @Test
    fun aFavoriteWithNoTrainThatWayChangesNothing() {
        val westSide = heading(unionSq, "West Side")
        assertEquals(westSide.glance(emptySet()), westSide.glance(setOf("Q")))
    }

    @Test
    fun atEveryStationAGlanceIsThreeDifferentTrainsOrAllThereAre() {
        val favorites = setOf("A", "L", "7", "Q")
        Fixtures.catalog.stations.forEach { station ->
            day.board(station).groups(day.time).forEach { group ->
                val named = group.glance(favorites)
                assertEquals(minOf(TIMES_SHOWN, group.arrivals.size), named.size, station.name)
                assertEquals(named.size, named.distinct().size, station.name)
                assertTrue(group.arrivals.containsAll(named), station.name)
                if (group.arrivals.any { Routes.plain(it.route) in favorites }) {
                    assertTrue(Routes.plain(named.first().route) in favorites, "${station.name} ${group.heading}")
                }
            }
        }
    }

    @Test
    fun timesOnOneLineInARowShareOneBullet() {
        val downtown = heading(unionSq, "Downtown")
        // N, R and 5 are in the station together, then the N again.
        assertEquals(listOf("N", "R", "5", "N"), downtown.arrivals.take(4).map { it.route })
        assertEquals(listOf(listOf("N"), listOf("R"), listOf("5"), listOf("N")), downtown.arrivals.take(4).byLine().map { run -> run.map { it.route } })
        // The L has the West Side to itself.
        val westSide = heading(unionSq, "West Side").glance(emptySet())
        assertEquals(listOf(westSide), westSide.byLine())
        // An express is a line of its own.
        val queens = heading(timesSq, "Queens").glance(emptySet())
        assertEquals(listOf("7X", "7", "7X"), queens.byLine().map { it.single().route })
    }

    @Test
    fun onlyTheTwoBiggestStationsHaveMoreHeadingsThanHomeShows() {
        val more = Fixtures.catalog.stations.filter { day.board(it).groups(day.time).size > HEADINGS_ON_HOME }
        assertEquals(setOf("Times Sq-42 St", "Grand Central-42 St"), more.map { it.name }.toSet())
    }
}

/** The station page's rows: their order with favorites, their last stops, and the lighter times. */
class StationRowsTest {
    private val day = Fixtures.weekdayAfternoon

    private fun headings(name: String) = day.board(Fixtures.station(name)).groups(day.time)

    @Test
    fun rowsOfFavoriteLinesComeFirstAndNothingElseMoves() {
        val uptown = headings("Times Sq-42 St").first()
        val plain = uptown.rows.map { it.route }
        assertEquals(listOf("1", "2", "3", "A", "C", "E", "N", "N", "Q", "R", "W"), plain)
        assertEquals(listOf("Q", "1", "2", "3", "A", "C", "E", "N", "N", "R", "W"), uptown.rowsWith(setOf("Q")).map { it.route })
        // Two favorites keep the station's order between themselves, and both of the N's rows move.
        assertEquals(listOf("A", "N", "N", "1", "2", "3", "C", "E", "Q", "R", "W"), uptown.rowsWith(setOf("N", "A")).map { it.route })
        assertEquals(uptown.rows, uptown.rowsWith(emptySet()))
    }

    @Test
    fun withFavoritesSetEveryLineStillHasItsRow() {
        Fixtures.catalog.stations.forEach { station ->
            day.board(station).groups(day.time).forEach { group ->
                assertEquals(group.rows.toSet(), group.rowsWith(setOf("Q", "4", "L")).toSet(), station.name)
                assertEquals(group.rows.size, group.rowsWith(setOf("Q", "4", "L")).size, station.name)
            }
        }
    }

    @Test
    fun aRowNamesItsLastStopUnlessTheHeadingAlreadyDoes() {
        val morgan = headings("Morgan Av").first()
        assertEquals("Manhattan", morgan.heading)
        assertEquals("8 Av", morgan.rows.single().lastStop(morgan.heading))

        // At Queensboro Plaza the heading is the last stop cut short, and says enough.
        val astoria = headings("Queensboro Plaza").first { it.heading == "Astoria" }
        assertEquals(listOf("Astoria-Ditmars Blvd", "Astoria-Ditmars Blvd"), astoria.rows.map { it.destination })
        assertEquals(listOf("", ""), astoria.rows.map { it.lastStop(astoria.heading) })

        val timesSq = headings("Times Sq-42 St")
        val shuttle = timesSq.first { it.heading == "Grand Central" }
        assertEquals("", shuttle.rows.single().lastStop(shuttle.heading))
        // The sign says "Hudson Yards" and the stop is 34 St-Hudson Yards.
        val hudsonYards = timesSq.first { it.heading == "Hudson Yards" }
        assertEquals("34 St-Hudson Yards", hudsonYards.rows.single().destination)
        assertEquals("", hudsonYards.rows.single().lastStop(hudsonYards.heading))
    }

    @Test
    fun aStopThatOnlyStartsLikeTheHeadingIsStillNamed() {
        fun row(destination: String) = Board.Row("N", destination, emptyList())
        // A train turning back at Astoria Blvd is not going where "Astoria" says.
        assertEquals("Astoria Blvd", row("Astoria Blvd").lastStop("Astoria"))
        assertEquals("Flatbush Av-Brooklyn College", row("Flatbush Av-Brooklyn College").lastStop("Brooklyn"))
        // Two streets are one name, and the heading has to be all of it.
        assertEquals("", row("Myrtle-Wyckoff Avs").lastStop("Myrtle-Wyckoff Avs"))
        assertEquals("Myrtle-Wyckoff Avs", row("Myrtle-Wyckoff Avs").lastStop("Brooklyn"))
    }

    @Test
    fun whereverTheHeadingIsTheLastStopCutShortTheRowDoesNotRepeatIt() {
        Fixtures.catalog.stations.forEach { station ->
            day.board(station).groups(day.time).forEach { group ->
                group.rows.filter { Directions.shortName(it.destination) == group.heading }.forEach { row ->
                    assertEquals("", row.lastStop(group.heading), "${station.name} ${group.heading}")
                }
            }
        }
    }

    @Test
    fun aRowGivesTimesForItsNextThreeTrains() {
        val uptown = headings("Times Sq-42 St").first()
        val r = uptown.rows.first { it.route == "R" }
        assertTrue(r.arrivals.size > TIMES_SHOWN)
        assertEquals("Now, 1, 4 min", timesLabel(r.named.map { it.minutesFrom(day.time) }))
        // A train that turns back early often has only one or two coming.
        val toNereid = headings("14 St-Union Sq").first().rows.first { it.destination == "Nereid Av" }
        assertEquals("43, 54 min", timesLabel(toNereid.named.map { it.minutesFrom(day.time) }))
    }

    @Test
    fun thePageSaysWhyATimeIsLighterOnlyWhenOneIs() {
        // The captured L feed at Morgan Av: one train on its way to Manhattan, the rest on the timetable.
        val morgan = Fixtures.station("Morgan Av")
        val now = Fixtures.lFeed.timestamp
        val board = Board(morgan, Directions.arrivals(morgan, Fixtures.catalog, mapOf("gtfs-l" to Fixtures.lFeed), now), asOf = now)
        val view = assertIs<Board.View.Trains>(board.view(now))
        assertEquals(listOf(true, false, false), view.groups.first().rows.single().named.map { it.assigned })
        assertTrue(view.namesScheduledTrains())

        // Old times are all drawn lighter and say so above them. The footnote would be wrong there.
        val old = assertIs<Board.View.Trains>(board.copy(stale = true).view(now))
        assertFalse(old.namesScheduledTrains())

        // A scheduled train too far down its row to be given a time needs no footnote. The L
        // to Brooklyn from Union Sq had three trains on the move and then the timetable.
        val unionSq = Fixtures.station("14 St-Union Sq")
        val toBrooklyn = Board(unionSq, day.board(unionSq).arrivals.filter { it.heading == "Brooklyn" }, asOf = day.time)
        val moving = assertIs<Board.View.Trains>(toBrooklyn.view(day.time))
        assertTrue(moving.groups.single().arrivals.any { !it.assigned })
        assertFalse(moving.namesScheduledTrains())
    }
}

/** Where a station's alerts go on its page, from the alerts in effect on a weekday afternoon. */
class AlertPlacesTest {
    private fun at(name: String) = Fixtures.alerts.at(Fixtures.station(name), Fixtures.ALERTS_BUILT)

    private fun made(id: String, notice: Boolean) =
        ServiceAlert(id, text = id, notice = notice, priority = 0, lines = emptySet(), stops = emptySet(), periods = emptyList())

    @Test
    fun theFirstTwoAlertsGoAboveTheTimesAndTheRestBelow() {
        val timesSq = at("Times Sq-42 St")
        assertEquals(3, timesSq.size)
        val placed = AlertPlaces.of(timesSq)
        assertEquals(timesSq.take(2), placed.above)
        assertEquals(timesSq.drop(2), placed.below)
    }

    @Test
    fun aStationNoticeGoesBelowTheTimesEvenWithRoomAbove() {
        // 5 Av/53 St had delays on the E and F, and a notice about one of its entrances.
        val fifthAv = at("5 Av/53 St")
        assertEquals(listOf(false, true), fifthAv.map { it.notice })
        val placed = AlertPlaces.of(fifthAv)
        assertEquals(fifthAv.take(1), placed.above)
        assertEquals(fifthAv.drop(1), placed.below)
    }

    @Test
    fun aNoticeNeverTakesAPlaceAboveWhereverItComesInTheList() {
        val alerts = listOf(made("notice", true), made("delay", false), made("second notice", true), made("work", false), made("more work", false))
        val placed = AlertPlaces.of(alerts)
        assertEquals(listOf("delay", "work"), placed.above.map { it.id })
        assertEquals(listOf("notice", "second notice", "more work"), placed.below.map { it.id })
    }

    @Test
    fun withOnlyANoticeNothingStandsAboveTheTimes() {
        val placed = AlertPlaces.of(listOf(made("notice", true)))
        assertEquals(emptyList(), placed.above)
        assertEquals(listOf("notice"), placed.below.map { it.id })
    }

    @Test
    fun atEveryStationEachAlertIsShownOnceAndNoNoticeIsAbove() {
        Fixtures.catalog.stations.forEach { station ->
            val alerts = Fixtures.alerts.at(station, Fixtures.ALERTS_BUILT)
            val placed = AlertPlaces.of(alerts)
            assertEquals(alerts.size, placed.above.size + placed.below.size, station.name)
            assertEquals(alerts.toSet(), (placed.above + placed.below).toSet(), station.name)
            assertTrue(placed.above.size <= AlertPlaces.ABOVE, station.name)
            assertTrue(placed.above.none { it.notice }, station.name)
        }
    }
}
