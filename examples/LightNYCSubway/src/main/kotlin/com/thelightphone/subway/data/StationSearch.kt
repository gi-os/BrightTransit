package com.thelightphone.subway.data

/**
 * Finds stations from what someone actually types.
 *
 * The query is split into words and each word is matched on its own, in any order, against
 * the words of the name, its other names, its borough and its lines, so "Myrtle Broadway"
 * finds Myrtle Av on Broadway. A word may match exactly, as the start of a longer word, or
 * with a small typo. Spelled-out and abbreviated forms are the same word ("Avenue" is "Av",
 * "14th" is "14").
 *
 * A number can be a street or a line, and how it was typed says which. "7th" and "7 av"
 * are the street; "7 train" is the line and nothing else; a bare "7" may be either. A cross
 * street may also be the avenue a station's lines run under, which no name mentions:
 * "34th and 7th" is the 1 2 3 at Penn Station.
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
        val otherNames: List<String>,
        /** What the avenues its lines run under are called, less the "av": "7", "lexington". */
        val avenues: Set<String>,
    )

    private val entries = stations.map { st ->
        val nameWords = words(st.name)
        Entry(
            station = st,
            nameWords = nameWords,
            otherWords = (st.aka.flatMap { words(it) } + words(st.boroLabel)).toSet(),
            routes = st.routes.map { it.lowercase() }.toSet(),
            plainName = nameWords.joinToString(" "),
            otherNames = st.aka.map { words(it).joinToString(" ") },
            avenues = st.avenues.flatMap { words(it) }.filterTo(HashSet()) { it != AV },
        )
    }

    /** How many stations carry each word. A rare word says more than "st" or "av". */
    private val commonness: Map<String, Int> = HashMap<String, Int>().also { map ->
        entries.forEach { e -> (e.nameWords + e.otherWords).toSet().forEach { map.merge(it, 1, Int::plus) } }
    }

    /** Every line there is, as typed: "7", "l", "sir". */
    private val lines: Set<String> = entries.flatMapTo(HashSet()) { it.routes }

    /** How a word of the query may be read. */
    private enum class Reading {
        /** A word of a name or a line, whichever the station has. */
        EITHER,

        /**
         * A street or an avenue, never a line: "7th", "seventh", the 7 of "7 av", and any
         * number no line goes by.
         */
        STREET,

        /** A line and nothing else: the 7 of "7 train". */
        LINE,
    }

    private class Word(val text: String, val reading: Reading) {
        val isNumber get() = text[0].isDigit()
    }

    /** One station's answer to a query: how many of its words it has, and how well. */
    private class Hit(val entry: Entry, val matched: Int, val score: Double)

    /**
     * The stations [query] finds, best first and [limit] at most. A line asked for by
     * itself ("the 7 train") is the one list that is not cut: part of a line's stations
     * would pass for all of them.
     */
    fun search(query: String, limit: Int = 30): List<Station> {
        // A word no station answers to as typed is read again as a spelled-out word cut
        // short or misspelt. Only then, so "centrl" stays "central" and never becomes "center".
        val q = typed(query).map { word ->
            val answered = word.reading == Reading.LINE || entries.any { wordScore(word, it) > 0 || onAvenue(word, it) }
            if (answered) word else Word(shortForm(word.text) ?: word.text, word.reading)
        }
        if (q.isEmpty()) return emptyList()
        val texts = q.map { it.text }
        val plainQuery = texts.joinToString(" ")
        // "the 7 train" asks for the 7's stations: all of them, in the order of their names.
        val lineOnly = q.all { it.reading == Reading.LINE }

        val hits = entries.mapNotNull { e ->
            val hit = match(q, e) ?: return@mapNotNull null
            if (lineOnly) return@mapNotNull hit
            val bonus = when {
                e.plainName == plainQuery -> WHOLE_NAME
                plainQuery in e.otherNames -> WHOLE_NAME * OTHER_NAME
                // Whole words, not letters: "penn" begins the word "Pennsylvania" but is
                // not the first word of that name, and must not outrank Penn Station.
                e.nameWords.take(q.size) == texts -> NAME_START
                else -> 0.0
            }
            Hit(e, hit.matched, hit.score + bonus)
        }
        if (hits.isEmpty()) return emptyList()

        // Stations with every word win outright. Otherwise the closest ones are shown, but
        // only those that hit a word worth something (not just "st").
        val complete = hits.filter { it.matched == q.size }
        val pool = complete.ifEmpty { hits.filter { it.score >= 2.0 } }
        val behind = if (complete.isEmpty()) onlyNearly(q, pool) else emptySet()
        val ranked = pool
            .sortedWith(
                compareBy<Hit> { it.entry in behind }
                    .thenByDescending { it.score }
                    .thenBy { naturalKey(it.entry.station.name) }
                    // Of stations that share a name, the one with more lines is the one
                    // more riders mean: the Fulton St with eight lines, not the one on the G.
                    .thenByDescending { it.entry.station.routes.size }
                    .thenBy { it.entry.station.boroLabel },
            )
            .map { it.entry.station }
        return if (lineOnly) ranked else ranked.take(limit)
    }

    /**
     * The query as words, each with how it may be read. See [Reading].
     *
     * A number after East or West is left as it is even before "train": "W 4 train" is
     * someone looking for W 4 St, not the 4.
     */
    private fun typed(query: String): List<Word> {
        val pieces = pieces(query)
        val words = pieces.mapIndexedNotNull { index, piece ->
            if (piece.text in NOISE) return@mapIndexedNotNull null
            val text = SAME_WORD[piece.text] ?: piece.text
            val streetName = text[0].isDigit() && pieces.getOrNull(index - 1)?.let { SAME_WORD[it.text] ?: it.text } in COMPASS
            val reading = when {
                piece.ordinal || piece.text in SPELLED_ORDINALS -> Reading.STREET
                pieces.getOrNull(index + 1)?.text in LINE_SAID && piece.text in lines && !streetName -> Reading.LINE
                else -> Reading.EITHER
            }
            Word(text, reading)
        }
        return words.mapIndexed { index, word ->
            val street = word.reading == Reading.EITHER && word.isNumber && (word.text !in lines || words.getOrNull(index + 1)?.text == AV)
            if (street) Word(word.text, Reading.STREET) else word
        }
    }

    /**
     * How much of [q] one station has, or null for none of it.
     *
     * A word that is nothing the station is called can still be the avenue it is on. That
     * counts for little, and only once another word has found the station by itself:
     * the avenue tells two 34 Sts apart, and alone it would bring in every station on the
     * trunk. The "av" typed after an avenue goes with it.
     */
    private fun match(q: List<Word>, e: Entry): Hit? {
        val own = DoubleArray(q.size) { wordScore(q[it], e) }
        val avenue = BooleanArray(q.size) { own[it] == 0.0 && onAvenue(q[it], e) }
        fun afterAvenue(i: Int) = i > 0 && avenue[i - 1] && q[i].text == AV
        val found = q.indices.any { own[it] > 0 && !afterAvenue(it) }
        var matched = 0
        var score = 0.0
        for (i in q.indices) {
            when {
                own[i] > 0 -> {
                    matched++
                    score += own[i]
                }
                found && avenue[i] -> {
                    matched++
                    score += AVENUE
                }
                found && afterAvenue(i) -> matched++
            }
        }
        return if (matched == 0) null else Hit(e, matched, score)
    }

    /** Best match for one query word against what one station is called; 0 means no match. */
    private fun wordScore(word: Word, e: Entry): Double {
        // "L", "6", "sir": a line the station serves. A number can also be a street, so a
        // name match below can still beat this.
        val line = if (word.reading != Reading.STREET && word.text in e.routes) 2.5 else 0.0
        if (word.reading == Reading.LINE) return line
        var best = line

        fun consider(target: String, inName: Boolean) {
            val kind = fit(word.text, target)
            if (kind == 0.0) return
            val rarity = when (commonness[target] ?: 1) {
                in 1..4 -> 1.6
                in 5..25 -> 1.0
                else -> 0.5
            }
            val s = kind * rarity * if (inName) 1.0 else OTHER_NAME
            if (s > best) best = s
        }
        e.nameWords.forEach { consider(it, true) }
        e.otherWords.forEach { consider(it, false) }
        return best
    }

    /**
     * True when [word] is an avenue this station's lines run under. A numbered avenue is
     * only ever a number typed as a street, so "34 st 7" stays the 7 at Hudson Yards.
     */
    private fun onAvenue(word: Word, e: Entry): Boolean = when {
        e.avenues.isEmpty() || word.reading == Reading.LINE -> false
        word.isNumber -> word.reading == Reading.STREET && word.text in e.avenues
        else -> e.avenues.any { fit(word.text, it) > 0 }
    }

    /**
     * Of [pool], the stations that have no word of [q] whole, only the start of one or a
     * near miss, when some other station has a street number of [q] in its own name. They
     * go behind the rest. By score alone the start of a rare word is worth more than a
     * number six stations share, and "86th and cent" would open with Central Av.
     */
    private fun onlyNearly(q: List<Word>, pool: List<Hit>): Set<Entry> {
        val numbers = q.filter { it.reading == Reading.STREET && it.isNumber }.map { it.text }
        if (pool.none { hit -> numbers.any { it in hit.entry.nameWords } }) return emptySet()
        return pool.map { it.entry }.filterTo(HashSet()) { e -> q.none { whole(it, e) } }
    }

    /** True when [word] is a line the station serves or a whole word of one of its names. */
    private fun whole(word: Word, e: Entry): Boolean =
        (word.reading != Reading.STREET && word.text in e.routes) ||
            (word.reading != Reading.LINE && (word.text in e.nameWords || word.text in e.otherWords))

    companion object {
        /** Added when the query is the station's whole name. */
        private const val WHOLE_NAME = 6.0

        /** Added when the query is the first words of the name. */
        private const val NAME_START = 3.0

        /** A match on another name the place goes by counts a little less than one on its own. */
        private const val OTHER_NAME = 0.8

        /** What the avenue a station is on counts for: less than any word of its own names. */
        private const val AVENUE = 1.0

        private const val AV = "av"

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
            "bway" to "broadway", "bdwy" to "broadway", "washington" to "wash",
        )

        /** "First" to "tenth", which are streets and avenues however they are spelled. */
        private val SPELLED_ORDINALS = SAME_WORD.filterValues { it[0].isDigit() }.keys

        /** East and West, as [SAME_WORD] leaves them. */
        private val COMPASS = setOf("e", "w")

        /** Words that carry nothing: "the L train at Union Square station". */
        private val NOISE = setOf("the", "and", "at", "of", "station", "stop", "train", "line", "subway")

        /** After one of these a line's name is a line and not a street: "7 train", "E line". */
        private val LINE_SAID = setOf("train", "line")

        private val ORDINAL = Regex("^(\\d+)(st|nd|rd|th)$")
        private val SPLIT = Regex("[^a-z0-9]+")
        private val RUN = Regex("\\d+|[a-z]+")

        /** One piece of what was typed. [ordinal] is set for the 14 of "14th". */
        private class Piece(val text: String, val ordinal: Boolean)

        /**
         * Lowercased and punctuation dropped. A letter stuck to a number is two pieces
         * ("w4", "8av"), unless it is the ending of "14th".
         */
        private fun pieces(text: String): List<Piece> =
            text.lowercase().replace("'", "").replace("’", "")
                // The MTA writes Queensboro Plaza and Borough Hall. People type either for both.
                .replace("borough", "boro")
                .split(SPLIT)
                .flatMap { token ->
                    ORDINAL.matchEntire(token)?.let { listOf(Piece(it.groupValues[1], ordinal = true)) }
                        ?: RUN.findAll(token).map { Piece(it.value, ordinal = false) }.toList()
                }

        /** The words of a name or a query, with abbreviations folded together. */
        fun words(text: String): List<String> =
            pieces(text).map { it.text }.filter { it !in NOISE }.map { SAME_WORD[it] ?: it }

        /**
         * How well a typed word fits a word of a name: 3 for the same word, 2.2 for its
         * start, 1.2 for a near miss, 0 for neither.
         */
        private fun fit(word: String, target: String): Double = when {
            target == word -> 3.0
            // "14" must not match "145"; a number only matches whole.
            word[0].isDigit() || target[0].isDigit() -> 0.0
            word.length >= 2 && target.startsWith(word) -> 2.2
            closeEnough(word, target) -> 1.2
            else -> 0.0
        }

        /**
         * What the names say for a spelled-out word typed part-way or misspelt: "junc" is
         * "jct", "sqaure" is "sq". Null when [typed] is neither.
         */
        fun shortForm(typed: String): String? {
            if (typed.length < 3) return null
            val long = SAME_WORD.keys.firstOrNull { it.length > typed.length && it.startsWith(typed) }
                ?: SAME_WORD.keys.firstOrNull { closeEnough(typed, it) }
            return long?.let { SAME_WORD[it] }
        }

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
