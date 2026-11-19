package com.thelightphone.subway

import com.thelightphone.subway.data.Alerts
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.GtfsRealtime.Period
import com.thelightphone.subway.data.GtfsRealtime.Selector
import com.thelightphone.subway.data.Routes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BQ_DELAYS =
    "Uptown B/Q trains are running with delays after we moved a train with a mechanical problem near Prospect Park."
private const val EF_DELAYS =
    "Downtown E/F trains are running with delays while we address a signal malfunction near 5 Av/53 St."
private const val SIR_DELAYS = "SIR trains are running with delays in both directions."
private const val NO_L = "In Brooklyn, no L between Myrtle-Wyckoff Avs and Atlantic Av"
private const val NO_A = "In Queens, no A between Rockaway Blvd and Ozone Park-Lefferts Blvd"
private const val NO_ROCKAWAY_SHUTTLE = "In Southeast Queens, S Rockaway Park Shuttle is replaced by free shuttle buses"

class AlertTextTest {
    @Test
    fun lineTokensAreWrittenAsRidersSeeThem() {
        assertEquals("Uptown B/Q trains", Alerts.plain("Uptown [B][Q] trains"))
        assertEquals("Downtown 1 skips 125 St", Alerts.plain("Downtown [1] skips 125 St"))
        assertEquals("7/7X local/express trains", Alerts.plain("[7][7X] local/express trains"))
    }

    @Test
    fun shuttlesAndTheRailwayGetTheNamesOnTheirSigns() {
        assertEquals("S Rockaway Park Shuttle", Alerts.plain("[H] Rockaway Park Shuttle"))
        assertEquals("S 42 Street Shuttle", Alerts.plain("[GS] 42 Street Shuttle"))
        assertEquals("S Franklin Av Shuttle", Alerts.plain("[FS] Franklin Av Shuttle"))
        assertEquals("SIR trains", Alerts.plain("[SIR] trains"))
        assertEquals("SIR trains", Alerts.plain("[SI] trains"))
    }

    @Test
    fun iconTokensAreDroppedWithTheSpaceAfterThem() {
        assertEquals("Free B111 shuttle buses", Alerts.plain("[shuttle bus icon] Free B111 shuttle buses"))
        assertEquals("Church Av or Prospect Park", Alerts.plain("Church Av [accessibility icon] or Prospect Park"))
        assertEquals("JFK AirTrain", Alerts.plain("[Airplane Icon] JFK AirTrain"))
    }

    @Test
    fun otherBracketsAreJustRemoved() {
        assertEquals("Take the M15 SBS instead", Alerts.plain("Take the [M15 SBS] instead"))
    }

    @Test
    fun linesAreTidiedAndBlankOnesDropped() {
        assertEquals("No L trains\nUse the M", Alerts.plain("  No [L]   trains \n\n \n Use the [M]\n"))
        assertEquals("", Alerts.plain(" [shuttle bus icon] \n"))
    }

    @Test
    fun aSecondLineIsKeptAndTheHeadlineIsTheFirst() {
        val four = Fixtures.alerts.all.first { it.id == "lmm:planned_work:35397" }
        assertEquals(
            "In the Bronx, Woodlawn-bound 4 skips 170 St, Mt Eden Av and 176 St\n" +
                "All trains board from the Manhattan-bound platform at Burnside Av",
            four.text,
        )
        assertEquals("In the Bronx, Woodlawn-bound 4 skips 170 St, Mt Eden Av and 176 St", four.headline)
    }

    @Test
    fun noBracketIsLeftAnywhereInTheFixture() {
        val all = Alerts.from(Fixtures.everyAlert, Fixtures.ALERTS_BUILT).all
        assertEquals(30, all.size)
        all.forEach { assertTrue('[' !in it.text && ']' !in it.text, it.text) }
    }

    @Test
    fun anAlertWithNothingToSayIsDropped() {
        val feed = GtfsRealtime.AlertFeed(1, listOf(GtfsRealtime.Alert("empty", emptyList(), listOf(Selector("L", "", 26)), " \n", "Delays")))
        assertTrue(Alerts.from(feed, 1).all.isEmpty())
    }
}

class AlertsAtAStationTest {
    private val built = Fixtures.ALERTS_BUILT

    private fun at(name: String, line: String? = null) = Fixtures.alerts.at(Fixtures.station(name, line), built)

    private fun texts(name: String, line: String? = null) = at(name, line).map { it.text }

    @Test
    fun aGapInTheLineShowsAtStopsOnEitherSideOfIt() {
        // Neither stop is among the six the alert names.
        assertEquals(listOf(NO_L), texts("Morgan Av"))
        assertEquals(listOf(NO_L), texts("Bedford Av"))
    }

    @Test
    fun aBigStationGetsDelaysNewestFirstThenPlannedWork() {
        val timesSq = texts("Times Sq-42 St")
        assertEquals(listOf(EF_DELAYS, BQ_DELAYS, NO_A), timesSq)
        // Skipped stops on the 1, the 7 and the Q are all somewhere else.
        assertTrue(timesSq.none { "skips" in it })
    }

    @Test
    fun aStationNoticeStaysAtItsOwnStationAndComesLast() {
        val notice = "Enter 5 Av/53 St E/F Station at 5 Av only\nMadison Av entrance will be exit only"
        val fifthAv = at("5 Av/53 St")
        assertEquals(listOf(EF_DELAYS, notice), fifthAv.map { it.text })
        assertEquals(listOf(false, true), fifthAv.map { it.notice })
        // One stop along the same two lines.
        assertEquals(listOf(EF_DELAYS), texts("Lexington Av/53 St"))
    }

    @Test
    fun skippedStopsShowOnlyWhereTheAlertNamesAStop() {
        assertEquals(listOf("Downtown 1 skips 125 St"), texts("125 St", "1"))
        assertEquals(emptyList(), texts("Canal St", "1"))
    }

    @Test
    fun theShuttlesDoNotLeakOntoGrandCentral() {
        assertEquals(emptyList(), texts("Grand Central-42 St"))
    }

    @Test
    fun aDelayOnTheRailwayShowsAtEveryRailwayStationAndNowhereElse() {
        val withDelay = Fixtures.catalog.stations.filter { SIR_DELAYS in Fixtures.alerts.at(it, built).map { a -> a.text } }
        val railway = Fixtures.catalog.stations.filter { "SIR" in it.routes }
        assertEquals(21, railway.size)
        assertEquals(railway.map { it.name }, withDelay.map { it.name })
    }

    @Test
    fun theSameWordsFiledTwiceShowOnce() {
        val twice = GtfsRealtime.AlertFeed(
            built,
            listOf("first", "second").map {
                GtfsRealtime.Alert(it, emptyList(), listOf(Selector("L", "", 26)), "[L] trains are delayed", "Delays")
            },
        )
        assertEquals(listOf("first"), Alerts.from(twice, built).at(Fixtures.station("Morgan Av"), built).map { it.id })
    }

    @Test
    fun worseComesFirstWhateverTheFeedOrder() {
        fun alert(id: String, sort: Int, start: Long) =
            GtfsRealtime.Alert(id, listOf(Period(start, 0)), listOf(Selector("L", "", sort)), id, "")
        val feed = GtfsRealtime.AlertFeed(
            built,
            listOf(alert("notice", 3, 50), alert("old delay", 26, 10), alert("unranked", 0, 90), alert("new delay", 26, 20)),
        )
        val shown = Alerts.from(feed, built).at(Fixtures.station("Morgan Av"), built).map { it.id }
        assertEquals(listOf("new delay", "old delay", "notice", "unranked"), shown)
    }
}

class ShuttleAndExpressAlertsTest {
    @Test
    fun aPlatformsStopTellsTheThreeShuttlesApart() {
        assertEquals("GS", Routes.feedId("S", "902"))
        assertEquals("H", Routes.feedId("S", "H04"))
        assertEquals("H", Routes.feedId("S", "H12"))
        assertEquals("FS", Routes.feedId("S", "S03"))
        assertEquals("FS", Routes.feedId("S", "D26"))
        // The railway's stops start with S too, but its line is not the shuttle.
        assertEquals("SI", Routes.feedId("SIR", "S22"))
        assertEquals("Q", Routes.feedId("Q", "D26"))
    }

    @Test
    fun everyShuttlePlatformInTheCatalogGoesToTheRightShuttle() {
        val byShuttle = Fixtures.catalog.stations
            .flatMap { it.platforms }
            .filter { "S" in it.routes }
            .groupBy({ Routes.feedId("S", it.stop) }, { it.stop })
            .mapValues { it.value.toSet() }
        assertEquals(
            mapOf(
                "GS" to setOf("901", "902"),
                "FS" to setOf("D26", "S01", "S03", "S04"),
                "H" to setOf("H04", "H12", "H13", "H14", "H15"),
            ),
            byShuttle,
        )
    }

    @Test
    fun aStationsFeedRoutesNameItsOwnShuttleOnly() {
        assertEquals(setOf("1", "2", "3", "7", "A", "C", "E", "N", "Q", "R", "W", "GS"), Fixtures.station("Times Sq-42 St").feedRoutes())
        assertEquals(setOf("A", "H"), Fixtures.station("Broad Channel").feedRoutes())
        assertEquals(setOf("B", "Q", "FS"), Fixtures.station("Prospect Park").feedRoutes())
        assertEquals(setOf("SI"), Fixtures.station("New Dorp").feedRoutes())
    }

    @Test
    fun theRockawayParkShuttleBeingOutShowsOnlyAlongThatShuttle() {
        // The first weekend it is replaced by buses. Nothing has to be in effect when the
        // feed is built for the alert to be in it.
        val weekend = 1795236300L
        val then = Alerts.from(GtfsRealtime.alerts(Fixtures.alertBytes, weekend, weekend + Alerts.KEEP_FOR), checkedAt = weekend)
        fun shows(name: String, line: String? = null) =
            NO_ROCKAWAY_SHUTTLE in then.at(Fixtures.station(name, line), weekend).map { it.text }

        // Broad Channel is where the shuttle starts, and is not one of the four stops named.
        assertTrue(shows("Broad Channel"))
        assertTrue(shows("Rockaway Park-Beach 116 St"))
        assertFalse(shows("Times Sq-42 St"))
        assertFalse(shows("Grand Central-42 St"))
        assertFalse(shows("Park Pl"))
        assertFalse(shows("Franklin Av", "S"))
        assertFalse(shows("Prospect Park"))
    }

    @Test
    fun anExpressAlertIsAnAlertForItsLine() {
        assertEquals("6", Routes.alertId("6X"))
        assertEquals("7", Routes.alertId("7x"))
        assertEquals("F", Routes.alertId("FX"))
        assertEquals("SI", Routes.alertId("SIR"))
        assertEquals("GS", Routes.alertId("GS"))

        val feed = GtfsRealtime.AlertFeed(1, listOf(GtfsRealtime.Alert("express", emptyList(), listOf(Selector("6X", "", 26)), "[6X] trains are delayed", "Delays")))
        val alerts = Alerts.from(feed, checkedAt = 1)
        assertEquals(listOf("6X trains are delayed"), alerts.at(Fixtures.station("Parkchester"), 1).map { it.text })
        assertEquals(emptyList(), alerts.at(Fixtures.station("Morgan Av"), 1))
    }
}

class AlertTimesTest {
    private val built = Fixtures.ALERTS_BUILT
    private val morgan = Fixtures.station("Morgan Av")

    private fun alert(id: String) = Fixtures.alerts.all.first { it.id == id }

    @Test
    fun aPeriodRunsFromItsStartUpToButNotIncludingItsEnd() {
        val gap = alert("lmm:planned_work:35536")
        assertFalse(gap.inEffect(1794495599))
        assertTrue(gap.inEffect(1794495600))
        assertTrue(gap.inEffect(1794511799))
        assertFalse(gap.inEffect(1794511800))
    }

    @Test
    fun anOpenEndedAlertNeverEnds() {
        val delay = alert("lmm:alert:275985:26")
        assertFalse(delay.inEffect(1794500745))
        assertTrue(delay.inEffect(1794500746))
        assertTrue(delay.inEffect(built + 10 * 365 * 86400L))
    }

    @Test
    fun workThatStartsWhileTheCopyIsHeldAppearsOnTime() {
        // Half an hour before the Q starts skipping Parkside Av for the night.
        val starts = 1794494700L
        val before = starts - 1800
        val held = Alerts.from(GtfsRealtime.alerts(Fixtures.alertBytes, before, before + Alerts.KEEP_FOR), checkedAt = before)
        val parkside = Fixtures.station("Parkside Av")
        fun skipping(now: Long) = held.at(parkside, now).any { "skips" in it.text }

        assertFalse(skipping(before))
        assertFalse(skipping(starts - 1))
        assertTrue(skipping(starts))
    }

    @Test
    fun workThatEndsWhileTheCopyIsHeldGoesOnTime() {
        val ends = 1794511800L
        val before = ends - 600
        val held = Alerts.from(GtfsRealtime.alerts(Fixtures.alertBytes, before, before + Alerts.KEEP_FOR), checkedAt = before)

        assertEquals(listOf(NO_L), held.at(morgan, ends - 1).map { it.text })
        assertEquals(emptyList(), held.at(morgan, ends))
    }

    @Test
    fun aCopyIsMarkedOldAfterTenMinutesAndDroppedAfterAnHour() {
        assertFalse(Fixtures.alerts.stale(built + Alerts.STALE_AFTER))
        assertTrue(Fixtures.alerts.stale(built + Alerts.STALE_AFTER + 1))

        assertEquals(1, Fixtures.alerts.at(morgan, built + Alerts.KEEP_FOR).size)
        assertEquals(emptyList(), Fixtures.alerts.at(morgan, built + Alerts.KEEP_FOR + 1))
    }

    @Test
    fun beforeAnyDownloadThereIsNothing() {
        assertEquals(emptyList(), Alerts.NONE.at(morgan, built))
        assertEquals(emptyList(), Alerts.NONE.at(morgan, 0))
    }
}
