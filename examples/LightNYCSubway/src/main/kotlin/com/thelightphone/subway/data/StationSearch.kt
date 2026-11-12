package com.thelightphone.subway.data

/**
 * Finds stations from what someone actually types.
 *
 * The first version matched the query as one substring of the name, so "Myrtle Broadway"
 * found nothing while "Myrtle" worked. Here the query is split into words and each word is
 * matched on its own, in any order, against the words of the name, its other names, its
 * borough and its lines. A word may match exactly, as the start of a longer word, or with a
 * small typo. Spelled-out and abbreviated forms are the same word ("Avenue" is "Av",
 * "14th" is "14").
 *
 * If no station has every word, the stations with the most (and rarest) words still come
 * back, so a near miss shows something close instead of an empty page.
 */
class StationSearch(stations: List<Station>) {

    private class Entry(
        val station: Station,
        val nameWords: List<String>,
        val otherWords: Set<String>,
        val routes: Set<String>,
        val plainName: String,
    )

    private val entries = stations.map { st ->
        val nameWords = words(st.name)
        Entry(
            station = st,
            nameWords = nameWords,
            otherWords = (st.aka.flatMap { words(it) } + words(st.boroLabel)).toSet(),
            routes = st.routes.map { it.lowercase() }.toSet(),
            plainName = nameWords.joinToString(" "),
        )
    }

    /** How many stations carry each word. A rare word says more than "st" or "av". */
    private val commonness: Map<String, Int> = HashMap<String, Int>().also { map ->
        entries.forEach { e -> (e.nameWords + e.otherWords).toSet().forEach { map.merge(it, 1, Int::plus) } }
    }

    fun search(query: String, limit: Int = 30): List<Station> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        val plainQuery = q.joinToString(" ")

        data class Hit(val entry: Entry, val matched: Int, val score: Double)

        val hits = entries.mapNotNull { e ->
            var matched = 0
            var score = 0.0
            for (word in q) {
                val s = wordScore(word, e)
                if (s > 0) {
                    matched++
                    score += s
                }
            }
            if (matched == 0) return@mapNotNull null
            if (e.plainName == plainQuery) score += 6
            else if (e.plainName.startsWith(plainQuery)) score += 3
            Hit(e, matched, score)
        }
        if (hits.isEmpty()) return emptyList()

        // Stations with every word win outright. Otherwise the closest ones are shown, but
        // only those that hit a word worth something (not just "st").
        val complete = hits.filter { it.matched == q.size }
        val pool = complete.ifEmpty { hits.filter { it.score >= 2.0 } }
        return pool
            .sortedWith(
                compareByDescending<Hit> { it.score }
                    .thenBy { naturalKey(it.entry.station.name) }
                    .thenBy { it.entry.station.boroLabel },
            )
            .take(limit)
            .map { it.entry.station }
    }

    /** Best match for one query word against one station; 0 means no match. */
    private fun wordScore(word: String, e: Entry): Double {
        var best = 0.0
        // "L", "6", "sir": a line the station serves. A number can also be a street, so a
        // name match below can still beat this.
        if (word in e.routes) best = 2.5

        fun consider(target: String, inName: Boolean) {
            val kind = when {
                target == word -> 3.0
                // "14" must not match "145"; a number only matches whole.
                word[0].isDigit() || target[0].isDigit() -> 0.0
                word.length >= 2 && target.startsWith(word) -> 2.2
                closeEnough(word, target) -> 1.2
                else -> 0.0
            }
            if (kind == 0.0) return
            val rarity = when (commonness[target] ?: 1) {
                in 1..4 -> 1.6
                in 5..25 -> 1.0
                else -> 0.5
            }
            val s = kind * rarity * if (inName) 1.0 else 0.8
            if (s > best) best = s
        }
        e.nameWords.forEach { consider(it, true) }
        e.otherWords.forEach { consider(it, false) }
        return best
    }

    companion object {
        private val SAME_WORD = mapOf(
            "avenue" to "av", "ave" to "av", "avs" to "av", "avenues" to "av",
            "street" to "st", "sts" to "st", "streets" to "st", "str" to "st", "saint" to "st",
            "square" to "sq", "boulevard" to "blvd", "road" to "rd", "parkway" to "pkwy",
            "place" to "pl", "heights" to "hts", "junction" to "jct", "center" to "ctr",
            "centre" to "ctr", "park" to "pk", "plaza" to "plz", "turnpike" to "tpke",
            "highway" to "hwy", "lane" to "ln", "drive" to "dr", "fort" to "ft",
            "mount" to "mt", "point" to "pt", "island" to "is", "university" to "univ",
            "college" to "coll", "east" to "e", "west" to "w", "north" to "n", "south" to "s",
            "first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5",
            "sixth" to "6", "seventh" to "7", "eighth" to "8", "ninth" to "9", "tenth" to "10",
            "bway" to "broadway", "bdwy" to "broadway",
        )

        /** Words that carry nothing: "the L train at Union Square station". */
        private val NOISE = setOf("the", "and", "at", "of", "station", "stop", "train", "line", "subway")

        private val ORDINAL = Regex("^(\\d+)(st|nd|rd|th)$")
        private val SPLIT = Regex("[^a-z0-9]+")

        /** Lowercased, punctuation dropped, abbreviations folded together. */
        fun words(text: String): List<String> =
            text.lowercase().replace("'", "").replace("’", "")
                .split(SPLIT)
                .filter { it.isNotEmpty() && it !in NOISE }
                .map { w -> ORDINAL.matchEntire(w)?.groupValues?.get(1) ?: SAME_WORD[w] ?: w }

        /** One typo in a word of four letters or more, two in a word of eight or more. */
        fun closeEnough(typed: String, target: String): Boolean {
            if (typed.length < 4) return false
            val allowed = if (typed.length >= 8) 2 else 1
            if (kotlin.math.abs(typed.length - target.length) > allowed) return false
            return typoDistance(typed, target) <= allowed
        }

        /** Edit distance where swapping two neighbouring letters counts as one mistake. */
        fun typoDistance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
            return d[a.length][b.length]
        }

        /** Sorts "8 St" before "14 St" before "116 St". */
        fun naturalKey(name: String): String =
            Regex("\\d+").replace(name.lowercase()) { it.value.padStart(4, '0') }
    }
}
