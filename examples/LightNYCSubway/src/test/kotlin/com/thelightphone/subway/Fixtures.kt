package com.thelightphone.subway

import com.thelightphone.subway.data.Alerts
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Directions
import com.thelightphone.subway.data.Feeds
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Station
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The real station file, a real L feed captured when the L was turning back at
 * Myrtle-Wyckoff Avs, a cut of the real alerts feed from the same afternoon, every train
 * in the system a few hours later and again on a Monday midday, and stretches of one
 * feed, or of one train in it, recorded download by download.
 */
object Fixtures {
    val catalog: Catalog by lazy { Catalog.parse(File("src/main/assets/stations.json").readText()) }

    val lFeedBytes: ByteArray by lazy { File("src/test/resources/gtfs-l.pb").readBytes() }

    val lFeed: GtfsRealtime.Feed by lazy { GtfsRealtime.parse(lFeedBytes) }

    /** 30 alerts, byte for byte as the MTA sent them. 14 were in effect when the feed was built. */
    val alertBytes: ByteArray by lazy { File("src/test/resources/subway-alerts.pb").readBytes() }

    /** When the MTA built the alerts feed. */
    const val ALERTS_BUILT = 1794506045L

    /** The alerts as the tool would hold them, had it downloaded the feed the second it was built. */
    val alerts: Alerts by lazy {
        Alerts.from(GtfsRealtime.alerts(alertBytes, ALERTS_BUILT, ALERTS_BUILT + Alerts.KEEP_FOR), checkedAt = ALERTS_BUILT)
    }

    /** All 30, whenever they apply. */
    val everyAlert: GtfsRealtime.AlertFeed by lazy { GtfsRealtime.alerts(alertBytes, 0, Long.MAX_VALUE) }

    /**
     * Thursday 12 November 2026, half a minute past four in the afternoon: 820 trips, 456
     * of them given to a train and the rest still only on the timetable, the evening rush
     * building, the eight feeds built within 13 seconds of each other. The A was not
     * running to Lefferts Blvd, and its trains that would have gone there ended at Howard
     * Beach; the 7 to Flushing was skipping 52 St and 69 St.
     */
    val weekdayAfternoon: Snapshot by lazy { Snapshot("weekday-afternoon") }

    /**
     * Monday 16 November 2026 at 11:20:57 AM: 766 trips, and with them where the feeds
     * last had each train, which the afternoon's snapshot does not hold. Everything that
     * goes by a train's position is asked of this one: a train seen beyond a station, a
     * train held at a stop, and on the railway whether a trip is a train yet.
     */
    val weekdayMidday: Snapshot by lazy { Snapshot("weekday-midday") }

    /**
     * The whole L feed, seventeen downloads a minute apart from 2:43 PM on Thursday 12
     * November 2026. At 2:49 the train 086350_L..N, then at Myrtle-Wyckoff Avs, was made to
     * skip its next six stops.
     */
    val lSkipStop: Replay by lazy { Replay("l-skip-stop") }

    /**
     * One uptown 1 train out of the numbered lines' feed, twenty-two downloads a minute
     * apart from 2:53 PM the same day. Its id lost its ending, two of its stops were taken
     * away, and twice it was missing from a download.
     */
    val oneCutShort: Replay by lazy { Replay("1-cut-short") }

    /**
     * One Franklin Av shuttle trip, 067150_FS.S01R, out of the B D F M feed: eleven
     * downloads half a minute apart from 11:10:58 AM on Monday 16 November 2026. It was
     * due out at 11:11:30. The feed gave it no train until the last of these downloads,
     * when it was all but at Prospect Park, and took its stops off the list one by one
     * as their times passed all the same.
     */
    val shuttleNoTrain: Replay by lazy { Replay("shuttle-no-train") }

    /*
     * The rest are one train each, cut from the same recording of Monday 16 November 2026,
     * half a minute a download. Each is a thing a feed did to a train that morning.
     */

    /**
     * The Q trip 068050_Q..N36R, the 11:20 from Coney Island, twenty-eight downloads from
     * 11:19:28 AM. It had no train until 11:26:51. The feed walked the empty trip down its
     * timetable all the same, four stops off its list, then gave it a train listed from
     * Coney Island again.
     */
    val qSlotThenTrain: Replay by lazy { Replay("q-slot-then-train") }

    /**
     * The B train 069500_B..N65R, eighteen downloads from 11:43:58 AM. The feed took Kings
     * Hwy off its list at 11:46:22, a few minutes before the train got there, listed it
     * again from 11:49:22 with the train standing at it, and took it off for good at 11:51:22.
     */
    val bStopGivenBack: Replay by lazy { Replay("b-stop-given-back") }

    /**
     * The J train 065700_J..S13R, twenty-seven downloads from 11:14:28 AM, stop by stop
     * from Crescent St to Chauncey St. Like every train on the lettered lines it is
     * "stopped" in every download: the feed moves it to a stop when it gets there and
     * never says it has left.
     */
    val jEveryStop: Replay by lazy { Replay("j-every-stop") }

    /**
     * The B train 062750_B..S65R, twenty-two downloads from 11:19:58 AM, from
     * Broadway-Lafayette St over the Manhattan Bridge to DeKalb Av. From 11:23 to 11:28 it
     * was on the bridge, named as stopped at Grand St, which was no longer in its list.
     */
    val bOverTheBridge: Replay by lazy { Replay("b-over-the-bridge") }

    /**
     * The N train 068800_N..N33R, twenty downloads from 11:43:28 AM. Short of Fort Hamilton
     * Pkwy, it was named as stopped at Atlantic Av-Barclays Ctr, four stops on, with the
     * stops between listed a second apart. At 11:48:52 the feed dropped those stops for
     * one download and listed them again in the next.
     */
    val nNamedFarAhead: Replay by lazy { Replay("n-named-far-ahead") }

    /**
     * The L train 064450_L..N, forty-six downloads from 11:10:58 AM. Late for
     * Myrtle-Wyckoff Avs, it was in the first two and in none after, with fourteen stops
     * and twenty minutes still to go.
     */
    val lGoneMidRun: Replay by lazy { Replay("l-gone-mid-run") }

    /**
     * The 5 train 064050_5..N46R, forty-two downloads from 11:32:28 AM. It stood at 149
     * St-Hostos from 11:33:39 until about 11:47, every download in between giving the same
     * times for the stops after it: 3 Av-149 St at 11:36:09, Jackson Av at 11:38:09.
     */
    val fiveHeld: Replay by lazy { Replay("5-held") }

    /**
     * The 7 train 066600_7..S, nine downloads from 11:10:58 AM. Held at Mets-Willets Point
     * seven minutes past its time there, with every stop as far as 82 St-Jackson Hts
     * listed for a time already gone. It left at about 11:12.
     */
    val sevenHeldThenOff: Replay by lazy { Replay("7-held-then-off") }

    /**
     * Two 4 trains that set out in the same minute the same way, twelve downloads from
     * 11:10:58 AM: 064700_4..S01R, two stops from the end of its run at New Lots Av, and
     * 064700_4..S06R, at Fulton St in Manhattan. The first left the feed at 11:14:54.
     */
    val fourNamesakes: Replay by lazy { Replay("4-namesakes") }

    /**
     * The 42 St shuttle trip 067700_GS.N04R, seventeen downloads from 11:10:58 AM. A train
     * had it from 11:11:54, and until it left Grand Central at 11:17 the feed gave it no
     * position at all: only a time to leave.
     */
    val shuttleStarting: Replay by lazy { Replay("shuttle-starting") }

    /**
     * The J train 070000_J..N41R, fourteen downloads from 11:45:58 AM. It stood at Delancey
     * St-Essex St from 11:46:09 for more than five minutes, the feed moving its time
     * there on with the clock the whole while.
     */
    val jHeld: Replay by lazy { Replay("j-held") }

    /**
     * The 4 train 071400_4..N06R in two downloads from 11:34:28 AM: no train in the first,
     * and in the second a train standing at Crown Hts-Utica Av, where its run starts, not
     * due out until 11:54.
     */
    val fourAtItsTerminal: Replay by lazy { Replay("4-at-its-terminal") }

    /**
     * The G train 067100_G..N14R, nine downloads from 11:40:28 AM. The feed took
     * Bedford-Nostrand Avs off its list at 11:41:51 and listed it first again in the next
     * two downloads, the second time with Myrtle-Willoughby Avs, Flushing Av, Broadway and
     * Nassau Av gone for good: the train ran from Bedford-Nostrand Avs to Metropolitan Av.
     */
    val gGivenBackSkipping: Replay by lazy { Replay("g-given-back-skipping") }

    /**
     * The M trip 069750_M..S24R, the 11:37:30 from Forest Hills-71 Av, twenty downloads
     * from 11:36:58 AM. It had no train until 11:44:36, when the feed first named one:
     * standing at Elmhurst Av, five stops down, a minute ahead of its timetable.
     */
    val mTrainNamedLate: Replay by lazy { Replay("m-train-named-late") }

    /**
     * The Rockaway Park shuttle trip 070200_H..N21R, the 11:42 from Beach 116 St,
     * twenty-one downloads from 11:41:28 AM. It had no train until 11:48:43, and the first
     * list it was given with one started at Beach 90 St. Half a minute later it was listed
     * from Beach 105 St, two stops back, with the train standing there.
     */
    val shuttleNamedTooFarDown: Replay by lazy { Replay("h-named-too-far-down") }

    /**
     * The E train 064450_E..N66R, twenty-eight downloads from 11:26:58 AM. It stood at
     * Sutphin Blvd-Archer Av-JFK Airport from 11:27:43 until 11:39:50, one stop short of
     * the end of its run, the feed moving its time there on with the clock the whole
     * while, and its time at Jamaica Center from 11:29:53 to 11:40:37.
     */
    val eHeld: Replay by lazy { Replay("e-held") }

    fun station(name: String, line: String? = null) =
        catalog.stations.first { it.name == name && (line == null || line in it.routes) }
}

/**
 * One feed over a run of downloads, as scripts/cut_replay.py cut them from a recording:
 * the MTA's own bytes, positions included, in the order they were fetched.
 */
class Replay(name: String) {
    val bytes: List<ByteArray> = DataInputStream(GZIPInputStream(File("src/test/resources/replays/$name.gz").inputStream())).use { packed ->
        buildList {
            while (true) {
                val length = try {
                    packed.readInt()
                } catch (e: EOFException) {
                    break
                }
                add(ByteArray(length).also(packed::readFully))
            }
        }
    }

    val feeds: List<GtfsRealtime.Feed> = bytes.map(GtfsRealtime::parse)
}

/**
 * The eight train feeds at one moment, as scripts/capture_feeds.py recorded them. Each is
 * the MTA's own bytes, less the entities that hold no train. The first snapshot taken,
 * of a weekday afternoon, was cut down further, to trips with no positions.
 */
class Snapshot(name: String) {
    val bytes: Map<String, ByteArray> = SLUGS.associateWith { slug ->
        GZIPInputStream(File("src/test/resources/snapshots/$name/$slug.pb.gz").inputStream()).use { it.readBytes() }
    }

    val feeds: Map<String, GtfsRealtime.Feed> = bytes.mapValues { GtfsRealtime.parse(it.value) }

    /** The snapshot's own time: when the last of its feeds was built. */
    val time: Long = feeds.values.maxOf { it.timestamp }

    /** The board the tool would have shown for [station] at that moment, from the feeds it fetches for it. */
    fun board(station: Station): Board {
        val fetched = Feeds.slugsFor(station).associateWith { feeds.getValue(it) }
        return Board(station, Directions.arrivals(station, Fixtures.catalog, fetched, time), asOf = fetched.values.minOf { it.timestamp })
    }

    /** The headings at [station], each with its rows written "line last stop". */
    fun says(station: Station): Map<String, List<String>> =
        board(station).groups(time).associate { group -> group.heading to group.rows.map { "${it.route} ${it.destination}" } }

    companion object {
        val SLUGS = listOf("gtfs", "gtfs-ace", "gtfs-bdfm", "gtfs-g", "gtfs-jz", "gtfs-nqrw", "gtfs-l", "gtfs-si")
    }
}
