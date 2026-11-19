package com.thelightphone.subway.data

/**
 * One service alert, ready to show.
 *
 * [text] is the MTA's headline with its "[L]" tokens written out; it can run to two lines.
 * [notice] marks a station notice (a closed entrance, an elevator), which can stay up for
 * months. [priority] is the MTA's own ranking, higher for worse.
 *
 * [stops] are the stops the alert names. [lines] are the lines it covers from end to end,
 * as feed ids: a delay names lines and no stops, so it is about everyone on them.
 */
class ServiceAlert(
    val id: String,
    val text: String,
    val notice: Boolean,
    val priority: Int,
    val lines: Set<String>,
    val stops: Set<String>,
    val periods: List<GtfsRealtime.Period>,
) {
    /** The first line, which is all a station on the home screen has room for. */
    val headline: String get() = text.substringBefore('\n')

    /**
     * Whether the alert is running at [now]. A period counts from its first second up to,
     * but not including, its end. Work that has not started is not in effect, however soon
     * it starts.
     */
    fun inEffect(now: Long): Boolean = periods.isEmpty() || periods.any { it.covers(now) }

    /**
     * Whether the alert is about [station]: it names one of the station's stops, or covers
     * one of its lines. The line an alert gives next to a stop is not looked at, so a
     * reroute shows at stops the train does not normally serve.
     */
    fun appliesTo(station: Station): Boolean =
        station.platforms.any { it.stop in stops } ||
            (lines.isNotEmpty() && station.feedRoutes().any { it in lines })

    /** When the period running at [now] began. This is what puts a new delay above an older one. */
    internal fun since(now: Long): Long = periods.filter { it.covers(now) }.maxOfOrNull { it.start } ?: 0L

    private fun GtfsRealtime.Period.covers(time: Long) = start <= time && (end == 0L || time < end)
}

/**
 * Every alert the tool is holding. [checkedAt] is when they were downloaded, by the phone's
 * clock, and is what the age of this copy is judged by.
 */
class Alerts(val checkedAt: Long, val all: List<ServiceAlert>) {

    /**
     * The alerts to show at [station] at [now]: those in effect there, worst first, and
     * among equals the one that began most recently.
     */
    fun at(station: Station, now: Long): List<ServiceAlert> {
        if (now - checkedAt > KEEP_FOR) return emptyList()
        return all
            .filter { it.inEffect(now) && it.appliesTo(station) }
            // The MTA files the same words again for each weekend of the same work.
            .distinctBy { it.text }
            .sortedWith(compareByDescending<ServiceAlert> { it.priority }.thenByDescending { it.since(now) })
    }

    /** Old enough that the station page says when these were fetched. */
    fun stale(now: Long): Boolean = now - checkedAt > STALE_AFTER

    companion object {
        /** What there is before the first download. */
        val NONE = Alerts(0, emptyList())

        const val STALE_AFTER = 10 * 60L

        /**
         * Past this nothing is shown: an alert that may have been lifted an hour ago is worse
         * than none. It is also how far ahead the copy looks, so work that starts while the
         * copy is held appears on time without a download.
         */
        const val KEEP_FOR = 60 * 60L

        private val ICON = Regex("""\[[^\[\]]*icon\] ?""", RegexOption.IGNORE_CASE)
        private val LINE_TOKEN = Regex("""\[([A-Za-z0-9]{1,3}(?:/[A-Za-z0-9]{1,3})*)\]""")
        private val SPACES = Regex("""[ \t\u00A0]+""")

        fun from(feed: GtfsRealtime.AlertFeed, checkedAt: Long): Alerts =
            Alerts(checkedAt, feed.alerts.mapNotNull { serviceAlert(it) })

        private fun serviceAlert(alert: GtfsRealtime.Alert): ServiceAlert? {
            val text = plain(alert.header)
            if (text.isEmpty()) return null
            // A gap in a line matters to everyone riding toward it, not only at the stops
            // inside the gap, so a suspension counts for the whole of every line it names.
            val suspended = alert.type.contains("Suspended", ignoreCase = true)
            return ServiceAlert(
                id = alert.id,
                text = text,
                notice = alert.type.equals("Station Notice", ignoreCase = true),
                priority = alert.selectors.maxOfOrNull { it.sort } ?: 0,
                lines = alert.selectors
                    .filter { it.route.isNotEmpty() && (suspended || it.stop.isEmpty()) }
                    .mapTo(HashSet()) { Routes.alertId(it.route) },
                stops = alert.selectors.filter { it.stop.isNotEmpty() }.mapTo(HashSet()) { it.stop },
                periods = alert.periods,
            )
        }

        /**
         * A headline as plain words. The feed marks lines and pictures with tokens in square
         * brackets, meant to be swapped for bullets and icons: "Uptown [B][Q] trains" becomes
         * "Uptown B/Q trains", "[H]" becomes the "S" on the train, and "[shuttle bus icon]"
         * goes, along with the space after it.
         */
        fun plain(header: String): String =
            header
                .replace(ICON, "")
                .replace("][", "/")
                .replace(LINE_TOKEN) { token ->
                    token.groupValues[1].split('/').joinToString("/") { Routes.display(it) }
                }
                .replace("[", "")
                .replace("]", "")
                .lineSequence()
                .map { it.replace(SPACES, " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
    }
}
