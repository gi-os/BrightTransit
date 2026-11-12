package com.thelightphone.subway

import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.GtfsRealtime
import java.io.File

/** The real station file and a real L feed, captured when the L was turning back at Myrtle-Wyckoff Avs. */
object Fixtures {
    val catalog: Catalog by lazy { Catalog.parse(File("src/main/assets/stations.json").readText()) }

    val lFeedBytes: ByteArray by lazy { File("src/test/resources/gtfs-l.pb").readBytes() }

    val lFeed: GtfsRealtime.Feed by lazy { GtfsRealtime.parse(lFeedBytes) }

    fun station(name: String, line: String? = null) =
        catalog.stations.first { it.name == name && (line == null || line in it.routes) }
}
