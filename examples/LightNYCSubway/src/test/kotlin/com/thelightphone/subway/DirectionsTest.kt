package com.thelightphone.subway

import com.thelightphone.subway.data.Arrival
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Direction
import com.thelightphone.subway.data.Directions
import com.thelightphone.subway.data.Feeds
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Platform
import com.thelightphone.subway.data.Routes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectionsTest {
    private val now = Fixtures.lFeed.timestamp
    private val day = Fixtures.weekdayAfternoon

    private fun says(name: String, line: String? = null) = day.says(Fixtures.station(name, line))

    private fun arrivals(name: String) =
        Directions.arrivals(Fixtures.station(name, "L"), Fixtures.catalog, mapOf("gtfs-l" to Fixtures.lFeed), now)

    /** A train the MTA has not sent, calling at [stops] two minutes apart. */
    private fun trip(route: String, vararg stops: String) =
        GtfsRealtime.Trip(route, stops.mapIndexed { index, stop -> GtfsRealtime.StopTime(stop, now + 120 * (index + 1)) })

    @Test
    fun theLAtMorganAvSaysManhattanNotUptown() {
        val north = arrivals("Morgan Av").filter { it.direction == Direction.NORTH }
        assertTrue(north.isNotEmpty())
        assertTrue(north.all { it.heading == "Manhattan" })
        assertTrue(north.all { it.destination == "8 Av" })
    }

    @Test
    fun theOtherWayOnTheLIsBrooklynWhereverTheTrainTurnsBack() {
        val south = arrivals("Morgan Av").filter { it.direction == Direction.SOUTH }
        assertTrue(south.isNotEmpty())
        // This feed was captured with the L turning back early. The heading stays put; the
        // last stop is what tells a rider the train is not going all the way.
        assertTrue(south.all { it.destination == "Myrtle-Wyckoff Avs" })
        assertTrue(south.all { it.heading == "Brooklyn" })
    }

    @Test
    fun everyLStopOutsideManhattanReadsManhattanAndBrooklyn() {
        val outside = Fixtures.catalog.stations
            .flatMap { it.platforms }
            .filter { it.routes == listOf("L") && it.n == "Manhattan" && it.stop != "L29" }
        assertEquals(18, outside.size)
        assertTrue(outside.all { it.s == "Brooklyn" })
        // Nothing leaves Canarsie going that way.
        assertEquals("", Fixtures.catalog.stop("L29")!!.second.s)
    }

    @Test
    fun withNoSignADirectionIsNamedByRule() {
        val unsigned = Platform(stop = "X00")
        val morgan = Fixtures.station("Morgan Av")
        val eighthAv = Fixtures.catalog.stop("L01")!!
        val canarsie = Fixtures.catalog.stop("L29")!!
        val courtSq = Fixtures.catalog.stop("G22")!!
        // Outside Manhattan a train that still stops there is going to Manhattan, wherever it ends.
        assertEquals("Manhattan", Directions.heading(morgan, unsigned, Direction.NORTH, eighthAv, viaManhattan = true))
        assertEquals("Manhattan", Directions.heading(morgan, unsigned, Direction.NORTH, courtSq, viaManhattan = true))
        // One that does not is named for the borough it ends in.
        assertEquals("Queens", Directions.heading(morgan, unsigned, Direction.NORTH, courtSq, viaManhattan = false))
        // When that is the borough it is in, the borough still: not "Canarsie", which would
        // change with every train that turns back early.
        assertEquals("Brooklyn", Directions.heading(morgan, unsigned, Direction.SOUTH, canarsie, viaManhattan = false))

        // From Manhattan the borough it ends in, and for a train that ends in Manhattan its last stop.
        val firstAv = Fixtures.station("1 Av")
        assertEquals("Brooklyn", Directions.heading(firstAv, unsigned, Direction.SOUTH, canarsie, viaManhattan = false))
        assertEquals("8 Av", Directions.heading(firstAv, unsigned, Direction.NORTH, eighthAv, viaManhattan = true))
    }

    @Test
    fun aSignComesBeforeTheRule() {
        val unionSq = Fixtures.station("14 St-Union Sq")
        val lPlatform = Fixtures.catalog.stop("L03")!!.second
        val eighthAv = Fixtures.catalog.stop("L01")!!
        assertEquals("West Side", Directions.heading(unionSq, lPlatform, Direction.NORTH, eighthAv, viaManhattan = true))
    }

    @Test
    fun theFFromBergenStIsManhattanAndNotTheQueensItEndsIn() {
        // Bergen St has no sign either way. The F ends in Jamaica by way of Manhattan; the G
        // ends at Court Sq and never sees Manhattan. Named for where they end, both are "Queens".
        val bergen = says("Bergen St", "F")
        assertEquals(listOf("Manhattan", "Queens", "Brooklyn"), bergen.keys.toList())
        assertEquals(listOf("F Jamaica-179 St"), bergen["Manhattan"])
        assertEquals(listOf("G Court Sq"), bergen["Queens"])
        assertEquals(listOf("F Coney Island-Stillwell Av", "F Kings Hwy", "G Church Av", "E Kings Hwy"), bergen["Brooklyn"])
    }

    @Test
    fun theWayOutIsOneHeadingHoweverManyPlacesTheTrainsEnd() {
        val atlantic = says("Atlantic Av-Barclays Ctr")
        assertEquals(listOf("Manhattan", "Brooklyn"), atlantic.keys.toList())
        assertEquals(
            setOf("Flatbush Av-Brooklyn College", "New Lots Av", "Crown Hts-Utica Av", "Brighton Beach", "Coney Island-Stillwell Av", "Bay Ridge-95 St"),
            atlantic.getValue("Brooklyn").map { it.substringAfter(' ') }.toSet(),
        )
    }

    @Test
    fun theBronxAndQueensReadTheSameWayAsBrooklyn() {
        assertEquals(listOf("The Bronx", "Manhattan"), says("149 St-Hostos").keys.toList())
        assertEquals(listOf("Queens", "Manhattan"), says("61 St-Woodside").keys.toList())
        assertEquals(listOf("Queens", "Manhattan"), says("Jackson Hts-Roosevelt Av").keys.toList())
    }

    @Test
    fun fromManhattanATrainWithNoSignGoesByWhereItEnds() {
        // 7 Av on 53 St has a sign for downtown only. Going the other way the E ends in Queens
        // and the B and D in the Bronx.
        val seventhAv = says("7 Av", "E")
        assertEquals(listOf("E Jamaica Center-Parsons/Archer"), seventhAv["Queens"])
        assertEquals(listOf("B Bedford Park Blvd", "D Norwood-205 St"), seventhAv["The Bronx"])
        // Middays the B turns back at 145 St, which is no other borough, and goes by that.
        val station = Fixtures.station("7 Av", "E")
        val turnBack = Fixtures.catalog.stop("D13")!!
        assertEquals("145 St", Directions.heading(station, station.platforms.single(), Direction.NORTH, turnBack, viaManhattan = true))
        // Essex St: the J ends four stops on, still in Manhattan.
        assertEquals(listOf("J Broad St"), says("Delancey St-Essex St")["Broad St"])
    }

    @Test
    fun theMIsListedWithTheJItSharesATrackWith() {
        // Over the Williamsburg Bridge the feed calls the M to Manhattan "north" and the J
        // beside it "south". Read by letter, the M to Middle Village comes out under "Manhattan".
        val marcy = says("Marcy Av")
        assertEquals(listOf("Queens", "Manhattan"), marcy.keys.toList())
        assertEquals(listOf("M Forest Hills-71 Av", "J Broad St"), marcy["Manhattan"])
        assertEquals(
            listOf("M Middle Village-Metropolitan Av", "J Jamaica Center-Parsons/Archer", "Z Jamaica Center-Parsons/Archer"),
            marcy["Queens"],
        )
        assertEquals(Direction.SOUTH, Directions.side("M", "M16", 'N'))
        assertEquals(Direction.NORTH, Directions.side("J", "M16", 'N'))
        // On its own line and on 6 Av the M's letter is the platform's.
        assertEquals(Direction.NORTH, Directions.side("M", "M08", 'N'))
        assertEquals(Direction.NORTH, Directions.side("M", "D19", 'N'))
        assertNull(Directions.side("M", "M16", 'X'))
    }

    @Test
    fun oneNameNeverStandsForBothWaysOutOfAPlatform() {
        // Hoyt-Schermerhorn Sts: the G to Court Sq and the A to the Rockaways both end in
        // Queens, on opposite sides. The A is the way out and keeps the name.
        val hoyt = says("Hoyt-Schermerhorn Sts")
        assertEquals(listOf("Manhattan", "Court Sq", "Queens", "Brooklyn"), hoyt.keys.toList())
        assertEquals(listOf("G Court Sq"), hoyt["Court Sq"])
        assertTrue(hoyt.getValue("Queens").all { it.startsWith("A ") })
        // The table's one sign for this platform, "Queens", would cover these two as well.
        assertTrue("C Euclid Av" in hoyt.getValue("Brooklyn") && "G Church Av" in hoyt.getValue("Brooklyn"))

        // Prospect Park: the shuttle to Franklin Av and the Q to Coney Island both stay in Brooklyn.
        val prospectPark = says("Prospect Park")
        assertEquals(listOf("Manhattan", "Franklin Av", "Brooklyn"), prospectPark.keys.toList())
        assertEquals(listOf("S Franklin Av"), prospectPark["Franklin Av"])
        assertEquals(listOf("B Brighton Beach", "Q Coney Island-Stillwell Av"), prospectPark["Brooklyn"])
    }

    @Test
    fun aTrainTurningBackShortOfManhattanDoesNotTakeTheNameOfTheWayOut() {
        // Bergen St on a day the G turns back at Bedford-Nostrand Avs, without leaving
        // Brooklyn. By the rule alone it would be "Brooklyn", beside the trains going the
        // other way.
        val bergen = Fixtures.station("Bergen St", "F")
        fun headings(vararg trips: GtfsRealtime.Trip) =
            Directions.arrivals(bergen, Fixtures.catalog, mapOf("gtfs-bdfm" to GtfsRealtime.Feed(now, trips.toList())), now)
                .associate { "${it.route} ${it.destination}" to it.heading }

        val fToManhattan = trip("F", "F20N", "A41N", "F18N", "F16N")
        val gTurningBack = trip("G", "F20N", "A42N", "G36N", "G35N", "G34N", "G33N")
        val gToChurchAv = trip("G", "F20S", "F21S", "F27S")
        assertEquals(
            mapOf("F East Broadway" to "Manhattan", "G Bedford-Nostrand Avs" to "Bedford-Nostrand Avs", "G Church Av" to "Brooklyn"),
            headings(fToManhattan, gTurningBack, gToChurchAv),
        )
        // With no train for Manhattan there is no saying which side is the way out, and
        // neither side gets the borough.
        assertEquals(
            mapOf("G Bedford-Nostrand Avs" to "Bedford-Nostrand Avs", "G Church Av" to "Church Av"),
            headings(gTurningBack, gToChurchAv),
        )
        assertEquals(mapOf("G Church Av" to "Brooklyn"), headings(gToChurchAv))
    }

    @Test
    fun theSideTheTableCallsInboundIsManhattan() {
        // The Rockaway Park shuttle never leaves Queens. It runs on the track the A takes to
        // Manhattan in the morning rush; named by rule it would be "Queens", beside that A.
        val beach105 = Fixtures.station("Beach 105 St")
        assertEquals(listOf("Manhattan", "Rockaway Park"), day.says(beach105).keys.toList())
        assertEquals(listOf("S Broad Channel"), says("Rockaway Park-Beach 116 St")["Manhattan"])
        val morning = listOf(trip("H", "H14N", "H13N", "H12N", "H04N"), trip("A", "H14N", "H13N", "H12N", "H04N", "A02N"))
        val trains = Directions.arrivals(beach105, Fixtures.catalog, mapOf("gtfs-ace" to GtfsRealtime.Feed(now, morning)), now)
        assertEquals(listOf("S Broad Channel", "A Inwood-207 St"), trains.map { "${it.route} ${it.destination}" })
        assertEquals(setOf("Manhattan"), trains.map { it.heading }.toSet())

        // The same word on the M's own stretch of line, where every train does go on to Manhattan.
        val inbound = listOf("H04", "H12", "H13", "H14", "H15", "M01", "M04", "M05", "M06", "M08", "M09", "M10")
        assertEquals(inbound.map { "Manhattan" }, inbound.map { Fixtures.catalog.stop(it)!!.second.n })
        // Essex St is "Inbound" in the table as well, and in Manhattan it is no direction at all.
        assertEquals("", Fixtures.catalog.stop("M18")!!.second.s)
    }

    @Test
    fun oneTrainListedUnderTwoTripsIsOneTrain() {
        // In the snapshot two trips on the F have the same times at fifteen stops. Listed
        // twice, a train would show as two due in the same minute.
        val stops = listOf(GtfsRealtime.StopTime("L14N", now + 300), GtfsRealtime.StopTime("L01N", now + 1500))
        val twice = listOf(
            GtfsRealtime.Trip("L", stops, assigned = false, id = "090000_L..N07R"),
            GtfsRealtime.Trip("L", stops, assigned = true, id = "090000_L..N"),
        )
        val trains = Directions.arrivals(Fixtures.station("Morgan Av"), Fixtures.catalog, mapOf("gtfs-l" to GtfsRealtime.Feed(now, twice)), now)
        // The copy kept is the one a train has been given, and it is that trip a tap on the row follows.
        assertTrue(trains.single().assigned)
        assertEquals("090000_L..N", trains.single().trip)
    }

    @Test
    fun theRailwaysExpressIsListedAsTheRailway() {
        // The feed calls it "SS", which is no shuttle: no station on the island lists an S.
        assertTrue(day.feeds.getValue("gtfs-si").trips.any { it.route == "SS" })
        assertEquals("SIR", Routes.display("SS"))
        assertEquals(listOf("SIR Great Kills", "SIR Tottenville"), says("St George")["South Shore"])
    }

    @Test
    fun aTrainsLastStopIsAStationAndNotTheSidingPastIt() {
        // The Rockaway Park shuttle's trips run on past Broad Channel to a point the station
        // file does not know, where it turns round.
        assertNull(Fixtures.catalog.stop("H19"))
        assertTrue(day.feeds.getValue("gtfs-ace").trips.any { it.route == "H" && it.stops.last().stopId == "H19N" })

        assertEquals(listOf("S Broad Channel"), says("Beach 90 St")["Manhattan"])
        // At Broad Channel that shuttle is ending, so it is not a train to wait for.
        val broadChannel = day.board(Fixtures.station("Broad Channel")).arrivals
        assertTrue(broadChannel.none { it.route == "S" && it.direction == Direction.NORTH })
        assertTrue(broadChannel.any { it.route == "S" && it.direction == Direction.SOUTH })
    }

    @Test
    fun aTrainStillOnlyOnTheTimetableIsMarked() {
        // Toward Manhattan from Morgan Av, one train was moving; the four behind it had not left Canarsie.
        val north = arrivals("Morgan Av").filter { it.direction == Direction.NORTH }
        assertEquals(listOf(true, false, false, false, false), north.map { it.assigned })
    }

    @Test
    fun aComplexUsesEachPlatformsOwnSigns() {
        val unionSq = arrivals("14 St-Union Sq")
        assertEquals(setOf("West Side", "Brooklyn"), unionSq.map { it.heading }.toSet())
    }

    @Test
    fun trainsEndingHereAreNotListed() {
        val eighthAv = Fixtures.catalog.stop("L01")!!.first
        val here = Directions.arrivals(eighthAv, Fixtures.catalog, mapOf("gtfs-l" to Fixtures.lFeed), now)
        assertTrue(here.isNotEmpty())
        assertTrue(here.none { it.direction == Direction.NORTH }, "8 Av is the end of the line going north")
    }

    @Test
    fun arrivalsComeSoonestFirstAndGroupNorthSideFirst() {
        val list = arrivals("Bedford Av")
        assertEquals(list.map { it.epochSeconds }.sorted(), list.map { it.epochSeconds })
        val groups = Board(Fixtures.station("Bedford Av", "L"), list).groups(now)
        assertEquals(listOf(Direction.NORTH, Direction.SOUTH), groups.map { it.direction })
    }

    @Test
    fun longTerminalNamesAreShortenedToThePlace() {
        assertEquals("Canarsie", Directions.shortName("Canarsie-Rockaway Pkwy"))
        assertEquals("Coney Island", Directions.shortName("Coney Island-Stillwell Av"))
        assertEquals("8 Av", Directions.shortName("8 Av"))
        assertEquals("241 St", Directions.shortName("241 St"))
        assertEquals("Myrtle-Wyckoff Avs", Directions.shortName("Myrtle-Wyckoff Avs"))
    }

    @Test
    fun everyStationHasAFeed() {
        Fixtures.catalog.stations.forEach { assertTrue(Feeds.slugsFor(it).isNotEmpty(), it.name) }
    }

    @Test
    fun eachShuttleRidesInAFeedItsStationsFetch() {
        fun feedsWith(route: String) = day.feeds.filterValues { feed -> feed.trips.any { it.route == route } }.keys
        assertEquals(setOf("gtfs"), feedsWith("GS"))
        assertEquals(setOf("gtfs-bdfm"), feedsWith("FS"))
        assertEquals(setOf("gtfs-ace"), feedsWith("H"))
        assertTrue("gtfs" in Feeds.slugsFor(Fixtures.station("Grand Central-42 St")))
        assertTrue("gtfs-bdfm" in Feeds.slugsFor(Fixtures.station("Park Pl")))
        assertTrue("gtfs-ace" in Feeds.slugsFor(Fixtures.station("Beach 90 St")))
    }

    @Test
    fun lettersThatShareTrackAreCheckedTogether() {
        val w4 = Fixtures.station("W 4 St-Wash Sq")
        assertEquals(listOf("gtfs-ace", "gtfs-bdfm"), Feeds.slugsFor(w4))
        assertEquals(listOf("gtfs-l"), Feeds.slugsFor(Fixtures.station("Morgan Av")))
    }
}

class SavedIdsTest {
    @Test
    fun aListWrittenByOlderVersionsBecomesOneEntryPerStation() {
        // What was on a phone that had been through two id schemes: Delancey St-Essex St
        // three times, Grand St (B D) twice, and Grand St on the L under an id that had
        // stopped being read.
        val saved = listOf("F15", "M18", "D22", "L12", "625", "231")
        val names = Fixtures.catalog.canonicalIds(saved).map { Fixtures.catalog.station(it)!! }
            .map { "${it.name} (${it.routes.joinToString(" ")})" }
        assertEquals(listOf("Delancey St-Essex St (F M J Z)", "Grand St (B D)", "Grand St (L)"), names)
    }

    @Test
    fun aCurrentListIsLeftAlone() {
        assertEquals(listOf("625", "231"), Fixtures.catalog.canonicalIds(listOf("625", "231")))
        assertEquals(emptyList(), Fixtures.catalog.canonicalIds(listOf("nonsense")))
    }
}

class GroupsTest {
    private val day = Fixtures.weekdayAfternoon
    private val delancey = Fixtures.station("Delancey St-Essex St")

    private fun train(route: String, direction: Direction, heading: String, at: Long, destination: String = "") =
        Arrival(route, direction, at, heading, destination)

    // Delancey St-Essex St: Brooklyn is "south" for the F and "north" for the J and M.
    private val trains = listOf(
        train("F", Direction.NORTH, "Uptown", 100),
        train("F", Direction.SOUTH, "Brooklyn", 120),
        train("J", Direction.NORTH, "Brooklyn", 60),
        train("M", Direction.NORTH, "Brooklyn", 200),
        train("J", Direction.SOUTH, "Downtown", 90),
    )

    @Test
    fun trainsToTheSamePlaceAreOneGroupWhateverTheFeedCallsTheirDirection() {
        val groups = Board(delancey, trains).groups(nowSeconds = 0)
        // The station's lines are F M J Z. "Uptown" and "Brooklyn" both go by the F: its
        // north side first, then its south. "Downtown" goes by the J.
        assertEquals(listOf("Uptown", "Brooklyn", "Downtown"), groups.map { it.heading })
        assertEquals(listOf("J", "F", "M"), groups[1].arrivals.map { it.route })
        assertEquals(listOf("F", "M", "J"), groups[1].rows.map { it.route })
    }

    @Test
    fun headingsKeepTheirPlacesWhicheverTrainComesFirst() {
        // The same trains with the J to Brooklyn last instead of first, and the downtown J before everything.
        val later = listOf(
            train("F", Direction.NORTH, "Uptown", 500),
            train("F", Direction.SOUTH, "Brooklyn", 300),
            train("J", Direction.NORTH, "Brooklyn", 900),
            train("M", Direction.NORTH, "Brooklyn", 200),
            train("J", Direction.SOUTH, "Downtown", 10),
        )
        val groups = Board(delancey, later).groups(nowSeconds = 0)
        assertEquals(listOf("Uptown", "Brooklyn", "Downtown"), groups.map { it.heading })
        assertEquals(listOf("F", "M", "J"), groups[1].rows.map { it.route })
    }

    @Test
    fun headingsOnTheSameSideAndLineGoByName() {
        val board = Board(
            delancey,
            listOf(train("F", Direction.SOUTH, "Coney Island", 100), train("F", Direction.SOUTH, "Brooklyn", 200)),
        )
        assertEquals(listOf("Brooklyn", "Coney Island"), board.groups(nowSeconds = 0).map { it.heading })
    }

    @Test
    fun trainsThatHaveLeftAreNotListed() {
        val board = Board(Fixtures.station("Morgan Av"), listOf(train("L", Direction.NORTH, "Manhattan", 100)))
        assertTrue(board.groups(nowSeconds = 100 + 31).isEmpty())
        assertEquals(1, board.groups(nowSeconds = 100 + 29).size)
    }

    @Test
    fun atTimesSqEveryLineGoingUptownHasARow() {
        val uptown = day.board(Fixtures.station("Times Sq-42 St")).groups(day.time).first()
        assertEquals("Uptown", uptown.heading)
        assertEquals(listOf("1", "2", "3", "A", "C", "E", "N", "Q", "R", "W"), uptown.rows.map { it.route }.distinct())
        // The next five trains, whoever they are, leave most of those lines out.
        assertTrue(uptown.arrivals.take(5).map { it.route }.distinct().size <= 5)
    }

    @Test
    fun aTrainThatTurnsBackEarlyHasARowOfItsOwn() {
        // Downtown from Grand Central the 5 was going to three different places.
        val downtown = day.says(Fixtures.station("Grand Central-42 St")).getValue("Downtown")
        assertEquals(
            setOf("5 Flatbush Av-Brooklyn College", "5 Crown Hts-Utica Av", "5 Bowling Green"),
            downtown.filter { it.startsWith("5 ") }.toSet(),
        )
    }

    @Test
    fun aRowHoldsAllItsTrainsSoonestFirstAndNoTrainIsLeftOut() {
        Fixtures.catalog.stations.forEach { station ->
            day.board(station).groups(day.time).forEach { group ->
                group.rows.forEach { row ->
                    assertTrue(row.arrivals.isNotEmpty())
                    assertTrue(row.arrivals.all { it.route == row.route && it.destination == row.destination })
                    assertEquals(row.arrivals.sortedBy { it.epochSeconds }, row.arrivals, station.name)
                }
                assertEquals(group.arrivals.toSet(), group.rows.flatMap { it.arrivals }.toSet(), station.name)
                assertEquals(group.arrivals.size, group.rows.sumOf { it.arrivals.size }, station.name)
            }
        }
    }

    @Test
    fun rowsFollowTheStationsOrderOfLinesWithAStrangerLast() {
        // Bergen St lists the F and the G. An E was running down the F line that afternoon.
        val brooklyn = day.board(Fixtures.station("Bergen St", "F")).groups(day.time).last()
        // A line's rows go by the name of the last stop.
        assertEquals(
            listOf("F Coney Island-Stillwell Av", "F Kings Hwy", "G Church Av", "E Kings Hwy"),
            brooklyn.rows.map { "${it.route} ${it.destination}" },
        )
    }

    @Test
    fun aLinesRowsGoByLastStopAndNotByWhichTrainIsNext() {
        // Uptown from 125 St the next B was for Bedford Park Blvd, and the one for 145 St
        // was behind it. The rows are the other way round, and stay that way.
        val uptown = day.board(Fixtures.station("125 St", "A")).groups(day.time).first { it.heading == "Uptown" }
        val b = uptown.rows.filter { it.route == "B" }
        assertEquals(listOf("145 St", "Bedford Park Blvd"), b.map { it.destination })
        assertTrue(b[1].arrivals.first().epochSeconds < b[0].arrivals.first().epochSeconds)

        // The same at 116 St with two trains of our own, whichever of them comes first.
        val station = Fixtures.station("116 St", "B")
        for ((first, second) in listOf(100L to 200L, 200L to 100L)) {
            val board = Board(
                station,
                listOf(
                    train("B", Direction.NORTH, "Uptown", first, "Bedford Park Blvd"),
                    train("B", Direction.NORTH, "Uptown", second, "145 St"),
                ),
            )
            assertEquals(listOf("145 St", "Bedford Park Blvd"), board.groups(nowSeconds = 0).single().rows.map { it.destination })
        }
    }
}
