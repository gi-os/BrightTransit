package com.thelightphone.subway

/**
 * Minimal, dependency-free GTFS-realtime (protobuf) decoder.
 *
 * The Light SDK gently restricts third-party libraries, so instead of pulling in
 * protobuf-java / Wire we hand-decode only the handful of fields we need from the
 * MTA feed:
 *
 *   FeedMessage        entity            = 2  (repeated, len-delimited)
 *   FeedEntity         trip_update       = 3  (len-delimited)
 *   TripUpdate         trip              = 1  (len-delimited)
 *                      stop_time_update  = 2  (repeated, len-delimited)
 *   TripDescriptor     route_id          = 5  (string)
 *   StopTimeUpdate     arrival           = 2  (len-delimited)
 *                      departure         = 3  (len-delimited)
 *                      stop_id           = 4  (string, e.g. "635N")
 *   StopTimeEvent      time              = 2  (varint, unix seconds)
 *
 * Everything else is skipped by wire type.
 */
object GtfsRealtime {

    /** A raw arrival straight from the feed, before station filtering. */
    data class Raw(val route: String, val stopId: String, val time: Long)

    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun hasMore() = p < end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val c = b[p++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        fun tag(): Int = varint().toInt()

        fun length(): Int = varint().toInt()

        /**
         * Read a length prefix and return the absolute end offset of the value.
         * NOTE: reading the length advances [p] past the prefix bytes, so we must
         * read it into a local before adding — `p + length()` would use the stale
         * pre-prefix position and truncate the value.
         */
        fun lenEnd(): Int {
            val n = length()
            return p + n
        }

        fun string(len: Int): String {
            val s = String(b, p, len, Charsets.UTF_8)
            p += len
            return s
        }

        /** Skip a field whose value we don't care about. */
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                5 -> p += 4
                2 -> { val n = length(); p += n }
                else -> throw IllegalStateException("bad wire type $wire")
            }
        }
    }

    fun parse(data: ByteArray): List<Raw> {
        val out = ArrayList<Raw>()
        val r = Reader(data, 0, data.size)
        while (r.hasMore()) {
            val tag = r.tag()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 2 && wire == 2) {
                val end = r.lenEnd()
                parseEntity(r.b, r.p, end, out)
                r.p = end
            } else {
                r.skip(wire)
            }
        }
        return out
    }

    private fun parseEntity(b: ByteArray, start: Int, end: Int, out: MutableList<Raw>) {
        val r = Reader(b, start, end)
        while (r.hasMore()) {
            val tag = r.tag(); val field = tag ushr 3; val wire = tag and 7
            if (field == 3 && wire == 2) { // trip_update
                val e = r.lenEnd()
                parseTripUpdate(b, r.p, e, out)
                r.p = e
            } else r.skip(wire)
        }
    }

    private fun parseTripUpdate(b: ByteArray, start: Int, end: Int, out: MutableList<Raw>) {
        val r = Reader(b, start, end)
        var route = ""
        while (r.hasMore()) {
            val tag = r.tag(); val field = tag ushr 3; val wire = tag and 7
            when {
                field == 1 && wire == 2 -> { // trip descriptor
                    val e = r.lenEnd()
                    route = parseRouteId(b, r.p, e)
                    r.p = e
                }
                field == 2 && wire == 2 -> { // stop_time_update
                    val e = r.lenEnd()
                    val stu = parseStopTimeUpdate(b, r.p, e)
                    if (stu != null && stu.second > 0L) {
                        out.add(Raw(route, stu.first, stu.second))
                    }
                    r.p = e
                }
                else -> r.skip(wire)
            }
        }
    }

    private fun parseRouteId(b: ByteArray, start: Int, end: Int): String {
        val r = Reader(b, start, end)
        var route = ""
        while (r.hasMore()) {
            val tag = r.tag(); val field = tag ushr 3; val wire = tag and 7
            if (field == 5 && wire == 2) route = r.string(r.length())
            else r.skip(wire)
        }
        return route
    }

    /** @return stopId to unix-seconds (arrival, else departure). */
    private fun parseStopTimeUpdate(b: ByteArray, start: Int, end: Int): Pair<String, Long>? {
        val r = Reader(b, start, end)
        var stopId = ""
        var arrival = 0L
        var departure = 0L
        while (r.hasMore()) {
            val tag = r.tag(); val field = tag ushr 3; val wire = tag and 7
            when {
                field == 4 && wire == 2 -> stopId = r.string(r.length())
                field == 2 && wire == 2 -> { val e = r.lenEnd(); arrival = parseStopTimeEvent(b, r.p, e); r.p = e }
                field == 3 && wire == 2 -> { val e = r.lenEnd(); departure = parseStopTimeEvent(b, r.p, e); r.p = e }
                else -> r.skip(wire)
            }
        }
        if (stopId.isEmpty()) return null
        val t = if (arrival > 0L) arrival else departure
        return stopId to t
    }

    private fun parseStopTimeEvent(b: ByteArray, start: Int, end: Int): Long {
        val r = Reader(b, start, end)
        var time = 0L
        while (r.hasMore()) {
            val tag = r.tag(); val field = tag ushr 3; val wire = tag and 7
            if (field == 2 && wire == 0) time = r.varint()
            else r.skip(wire)
        }
        return time
    }
}
