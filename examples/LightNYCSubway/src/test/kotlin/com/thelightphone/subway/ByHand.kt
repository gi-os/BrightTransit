package com.thelightphone.subway

import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Train

/**
 * Trains and feeds made by hand, on the L toward Manhattan from Jefferson St to 8 Av: the
 * feed's lies one at a time, which no recording has all of. Stops are named by platform
 * ("L14" is Morgan Av), as the tool names them.
 */
object ByHand {
    /** When the first feed of a test was built. */
    const val T0 = 1_794_500_000L

    const val JEFFERSON = "L15"
    const val MORGAN = "L14"
    const val MONTROSE = "L13"
    const val GRAND = "L12"
    const val GRAHAM = "L11"
    const val LORIMER = "L10"
    const val BEDFORD = "L08"
    const val FIRST_AV = "L06"
    const val THIRD_AV = "L05"
    const val UNION_SQ = "L03"
    const val SIXTH_AV = "L02"
    const val EIGHTH_AV = "L01"

    val LINE = listOf(JEFFERSON, MORGAN, MONTROSE, GRAND, GRAHAM, LORIMER, BEDFORD, FIRST_AV, THIRD_AV, UNION_SQ, SIXTH_AV, EIGHTH_AV)

    const val ID = "090000_L..N"

    /**
     * A trip calling at every stop from [from] to 8 Av, less [skipping], due at [from] at
     * [at] and at each stop after it [every] seconds later.
     */
    fun trip(
        from: String,
        at: Long,
        id: String = ID,
        every: Long = 90,
        assigned: Boolean = true,
        skipping: Set<String> = emptySet(),
        route: String = "L",
    ): GtfsRealtime.Trip {
        val stops = LINE.drop(LINE.indexOf(from)).mapIndexed { index, platform -> platform to at + index * every }
        return GtfsRealtime.Trip(route, stops.filter { it.first !in skipping }.map { GtfsRealtime.StopTime(it.first + "N", it.second) }, assigned, id)
    }

    /** A feed built at [at] holding [trips]. [standing] are the trips the feed has stopped at a platform, seen there as it was built. */
    fun feed(at: Long, vararg trips: GtfsRealtime.Trip, standing: Map<String, String> = emptyMap()): GtfsRealtime.Feed =
        GtfsRealtime.Feed(
            timestamp = at,
            trips = trips.toList(),
            positions = standing.mapValues { GtfsRealtime.Position(it.value + "N", GtfsRealtime.Status.STOPPED, at) },
        )

    /** The train running as [id] in [feed], as the tool first sees it. */
    fun seen(feed: GtfsRealtime.Feed, id: String = ID): Train = Train.find("gtfs-l", feed, id, Fixtures.catalog)!!

    /** The platforms a train still has ahead of it. */
    val Train.platforms: List<String> get() = stops.map { it.platform }
}
