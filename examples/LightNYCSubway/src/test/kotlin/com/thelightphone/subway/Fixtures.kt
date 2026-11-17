package com.thelightphone.subway

import com.thelightphone.subway.data.Alerts
import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.GtfsRealtime
import java.io.File

/**
 * The real station file, a real L feed captured when the L was turning back at
 * Myrtle-Wyckoff Avs, and a cut of the real alerts feed from the same afternoon.
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

    fun station(name: String, line: String? = null) =
        catalog.stations.first { it.name == name && (line == null || line in it.routes) }
}
