package com.thelightphone.subway

import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Direction
import com.thelightphone.subway.data.Directions
import com.thelightphone.subway.data.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectionsTest {
    private val now = Fixtures.lFeed.timestamp

    private fun arrivals(name: String) =
        Directions.arrivals(Fixtures.station(name, "L"), Fixtures.catalog, listOf(Fixtures.lFeed), now)

    @Test
    fun theLAtMorganAvSaysManhattanNotUptown() {
        val north = arrivals("Morgan Av").filter { it.direction == Direction.NORTH }
        assertTrue(north.isNotEmpty())
        assertTrue(north.all { it.heading == "Manhattan" })
        assertTrue(north.all { it.destination == "8 Av" })
    }

    @Test
    fun aDirectionTheMtaOnlyCallsOutboundIsNamedAfterWhereTheTrainEnds() {
        val south = arrivals("Morgan Av").filter { it.direction == Direction.SOUTH }
        assertTrue(south.isNotEmpty())
        // This feed was captured with the L turning back early.
        assertTrue(south.all { it.destination == "Myrtle-Wyckoff Avs" })
        assertTrue(south.all { it.heading == "Myrtle-Wyckoff Avs" })
    }

    @Test
    fun aComplexUsesEachPlatformsOwnSigns() {
        val unionSq = arrivals("14 St-Union Sq")
        assertEquals(setOf("West Side", "Brooklyn"), unionSq.map { it.heading }.toSet())
    }

    @Test
    fun trainsEndingHereAreNotListed() {
        val eighthAv = Fixtures.catalog.stop("L01")!!.first
        val here = Directions.arrivals(eighthAv, Fixtures.catalog, listOf(Fixtures.lFeed), now)
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
    private fun train(route: String, direction: Direction, heading: String, at: Long) =
        com.thelightphone.subway.data.Arrival(route, direction, at, heading, "")

    @Test
    fun trainsToTheSamePlaceAreOneGroupWhateverTheFeedCallsTheirDirection() {
        // Delancey St-Essex St: Brooklyn is "south" for the F and "north" for the J and M.
        val board = Board(
            Fixtures.station("Delancey St-Essex St"),
            listOf(
                train("F", Direction.NORTH, "Uptown", 100),
                train("F", Direction.SOUTH, "Brooklyn", 120),
                train("J", Direction.NORTH, "Brooklyn", 60),
                train("M", Direction.NORTH, "Brooklyn", 200),
                train("J", Direction.SOUTH, "Downtown", 90),
            ),
        )
        val groups = board.groups(nowSeconds = 0)
        assertEquals(listOf("Brooklyn", "Uptown", "Downtown"), groups.map { it.heading })
        assertEquals(listOf("J", "F", "M"), groups.first().arrivals.map { it.route })
    }

    @Test
    fun trainsThatHaveLeftAreNotListed() {
        val board = Board(Fixtures.station("Morgan Av"), listOf(train("L", Direction.NORTH, "Manhattan", 100)))
        assertTrue(board.groups(nowSeconds = 100 + 31).isEmpty())
        assertEquals(1, board.groups(nowSeconds = 100 + 29).size)
    }
}
