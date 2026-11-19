package com.thelightphone.subway

import com.thelightphone.subway.data.StationSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationSearchTest {
    private fun search(query: String) = Fixtures.catalog.search.search(query)

    private fun first(query: String) = search(query).first().let { "${it.name} (${it.routes.joinToString(" ")})" }

    @Test
    fun myrtleBroadwayFindsTheStationJoeLookedFor() {
        assertEquals("Myrtle Av (M J Z)", first("Myrtle Broadway"))
    }

    @Test
    fun oneWordStillFindsEveryMyrtle() {
        assertEquals(
            setOf("Myrtle Av", "Myrtle-Willoughby Avs", "Myrtle-Wyckoff Avs"),
            search("Myrtle").map { it.name }.toSet(),
        )
    }

    @Test
    fun wordsMatchInAnyOrderAndSpelledOut() {
        assertEquals("14 St-Union Sq (4 5 6 L N Q R W)", first("union square"))
        assertEquals("14 St-Union Sq (4 5 6 L N Q R W)", first("14th Street Union Sq"))
        assertEquals("14 St-Union Sq (4 5 6 L N Q R W)", first("Square Union"))
        assertEquals("Bedford Av (L)", first("bedford avenue"))
    }

    @Test
    fun unionListsAllThreeLikeTheMock() {
        val names = search("Union").map { it.name }
        assertTrue(names.containsAll(listOf("Union St", "14 St-Union Sq", "Kew Gardens-Union Tpke")), names.toString())
    }

    @Test
    fun aTypoStillLands() {
        assertEquals("Grand Central-42 St (4 5 6 7 S)", first("grand centrl"))
        assertEquals("Morgan Av (L)", first("Mogran"))
    }

    @Test
    fun otherNamesForTheSamePlaceWork() {
        assertEquals("Times Sq-42 St (1 2 3 7 A C E N Q R W S)", first("port authority"))
        assertEquals("Mets-Willets Point (7)", first("citi field"))
        assertEquals("Atlantic Av-Barclays Ctr (2 3 4 5 B D N Q R)", first("barclays center"))
    }

    @Test
    fun pennStationComesBeforePennsylvaniaAv() {
        for (typed in listOf("penn", "penn station", "Penn Station")) {
            val names = search(typed).map { it.name }
            assertEquals(listOf("34 St-Penn Station", "34 St-Penn Station", "Pennsylvania Av"), names, typed)
        }
        assertEquals("Pennsylvania Av (3)", first("pennsylvania"))
    }

    @Test
    fun aPlatformsOwnNameFindsTheStationItIsIn() {
        assertEquals("Borough Hall (2 3 4 5 R)", first("court st"))
        assertEquals("Chambers St (2 3 A C E R W)", first("cortlandt st"))
        // A station really called that still comes before one that only contains it.
        assertEquals(listOf("Lorimer St", "Metropolitan Av"), search("lorimer st").map { it.name })
    }

    @Test
    fun stationsThatShareANameListTheOneWithMoreLinesFirst() {
        assertEquals(listOf(7, 3, 1), search("canal st").map { it.routes.size })
        assertEquals("Fulton St (2 3 4 5 A C J Z)", first("fulton"))
    }

    @Test
    fun aLetterStuckToANumberIsTwoWords() {
        assertEquals("W 4 St-Wash Sq (A C E B D F M)", first("w4"))
        assertEquals(listOf("14", "st"), StationSearch.words("14st") + StationSearch.words("St"))
        assertEquals(listOf("8", "av"), StationSearch.words("8av"))
    }

    @Test
    fun aSpelledOutWordCanBeCutShortOrMisspelt() {
        assertEquals("Broadway Junction (A C J Z L)", first("broadway junc"))
        assertEquals("14 St-Union Sq (4 5 6 L N Q R W)", first("union sqaure"))
        assertEquals("Park Pl (S)", first("park plce"))
        assertEquals("sq", StationSearch.shortForm("squar"))
        assertEquals(null, StationSearch.shortForm("sq"))
        assertEquals(null, StationSearch.shortForm("morgan"))
    }

    @Test
    fun eitherSpellingOfBoroughWorks() {
        assertEquals("Queensboro Plaza (7 N W)", first("queensborough plaza"))
        assertEquals("Borough Hall (2 3 4 5 R)", first("boro hall"))
        assertEquals("W 4 St-Wash Sq (A C E B D F M)", first("washington square"))
    }

    @Test
    fun aLineNarrowsTheSearch() {
        assertEquals("14 St (1 2 3 F M L)", first("14 st L 1"))
        assertEquals("23 St (C E)", first("E train 23 st"))
        assertEquals(listOf("Flushing-Main St"), search("main st 7 train").map { it.name })
    }

    @Test
    fun aLineAskedForAsATrainListsThatLinesStationsAndNoOthers() {
        val stations = Fixtures.catalog.stations
        val lines = stations.flatMap { it.routes }.distinct()
        assertEquals(24, lines.size)
        for (line in lines) {
            val served = stations.filter { line in it.routes }.map { it.id }.sorted()
            for (typed in listOf("$line train", "the $line train", "$line line", "${line.lowercase()} train")) {
                assertEquals(served, search(typed).map { it.id }.sorted(), typed)
            }
        }
        // East and West are words of names, and Avenue J is a station. None of them is on these lines.
        assertEquals(22, search("E train").size)
        assertTrue(search("7 train").none { it.name == "7 Av" })
        assertTrue(search("J train").none { it.name == "Avenue J" })
        // The longest line is not cut off at thirty.
        assertEquals(49, search("2 train").size)
        assertTrue("Times Sq-42 St" in search("1 train").map { it.name })
    }

    @Test
    fun aNumberOrLetterAloneMayBeALineOrAName() {
        // A bare 7 can be the avenue, and the stations named for it come first.
        assertEquals("7 Av", search("7").first().name)
        assertEquals(30, search("a").size)
        // After East or West a number is a street, whatever follows it.
        assertEquals("W 4 St-Wash Sq (A C E B D F M)", first("W 4 train"))
        assertEquals(listOf("W 4 St-Wash Sq"), search("west 4th train station").map { it.name })
    }

    @Test
    fun aNumberTypedAsAStreetIsNeverALine() {
        // Read as the 7 line, each of these would find 34 St-Hudson Yards and nothing else.
        for (typed in listOf("34th and 7th", "7th and 34th", "34th & 7th", "34th st and 7th ave", "34 st 7 av", "34th and seventh")) {
            assertEquals(listOf("34 St-Penn Station (1 2 3)"), search(typed).map { "${it.name} (${it.routes.joinToString(" ")})" }, typed)
        }
        // With no word to say otherwise, a number may still be the line.
        assertEquals("34 St-Hudson Yards (7)", first("34 and 7"))
        assertEquals(listOf("86 St (4 5 6)"), search("86 st 4").map { "${it.name} (${it.routes.joinToString(" ")})" })
        // An avenue asked for by name is the stations called that, not every station on the 2 or the 6.
        assertEquals(listOf("2 Av"), search("2nd ave").map { it.name })
        assertEquals(listOf("14 St"), search("6th ave").map { it.name })
        assertEquals(listOf("7 Av", "7 Av", "7 Av", "57 St-7 Av"), search("7 av").map { it.name })
    }

    @Test
    fun aCrossStreetFindsTheStationOnThatAvenue() {
        assertEquals(listOf("7 Av"), Fixtures.station("34 St-Penn Station", "1").avenues)
        assertEquals("34 St-Penn Station (A C E)", first("34th and 8th"))
        assertEquals("72 St (Q)", first("72nd and 2nd"))
        assertEquals("72 St (C B)", first("72nd and 8th"))
        assertEquals("86 St (R)", first("86th and 4th"))
        assertEquals("86 St (4 5 6)", first("86th and lex"))
        assertEquals("23 St (F M)", first("23rd and 6th"))
        assertEquals("23 St (1)", first("23rd and 7th"))
        assertEquals("42 St-Bryant Pk (7 B D F M)", first("42nd and 6th"))
        assertEquals("167 St (4)", first("167th and jerome"))
        assertEquals("104 St (A)", first("104th and liberty"))
        // The avenue comes after the street's own stations where one is named for it.
        assertEquals("1 Av (L)", first("14th and 1st"))
        assertEquals("57 St-7 Av (N Q R W)", first("57th and 7th"))
    }

    @Test
    fun anAvenueAloneBringsInNoStationThatIsOnlyOnIt() {
        // Seven stations of the M are on Myrtle Av, and only Myrtle-Wyckoff Avs is called that.
        assertEquals(3, search("Myrtle").size)
        assertEquals(3, search("myrtle av").size)
        assertEquals(listOf("4 Av-9 St"), search("4th ave").map { it.name })
        assertEquals(search("lexington"), search("lexington ave"))
        assertTrue(search("lexington").all { "Lexington" in it.name || it.aka.any { other -> "Lexington" in other } })
        // The 1 runs under Broadway north of 59 St, and 7 Av up there is another street.
        assertTrue(Fixtures.station("125 St", "1").avenues.isEmpty())
        assertTrue(search("125th and 7th").size > 1)
        // In Brooklyn the F is nowhere near 6 Av.
        assertTrue(Fixtures.station("18 Av", "F").avenues.isEmpty())
    }

    @Test
    fun withNoStationHavingEveryWordAStreetNumberOutranksTheStartOfARareWord() {
        // Leaving the avenues out is what leaves no station with every word.
        val bare = StationSearch(Fixtures.catalog.stations.map { it.copy(avenues = emptyList()) })
        fun names(typed: String) = bare.search(typed).map { it.name }
        // Six stations are 86 St. The few words that begin "cent" or "lex" are rarer, and score higher.
        assertEquals("86 St", names("86th and cent").first())
        assertEquals(List(6) { "86 St" }, names("86th and lex").take(6))
        assertTrue("Lexington Av/53 St" in names("86th and lex"))
        // A station found by a whole word of its name keeps its place.
        assertEquals("Grand Central-42 St", names("grand central 45th").first())
        assertEquals("14 St-Union Sq", names("union sq 15th").first())
        assertEquals("1 Av", names("14th and 1st").first())
    }

    @Test
    fun namesTheMtaHasReplacedStillFindTheirStations() {
        assertEquals("110 St-Malcolm X Plaza (2 3)", first("central park north"))
        assertEquals("149 St-Hostos (2 4 5)", first("149 grand concourse"))
        assertEquals("Christopher St-Stonewall (1)", first("sheridan sq"))
        assertEquals("Christopher St-Stonewall (1)", first("sheridan square"))
    }

    @Test
    fun aNumberOnlyMatchesTheWholeNumber() {
        assertTrue(search("14 st").none { it.name.startsWith("145") })
    }

    @Test
    fun aNearMissShowsTheClosestStationsInsteadOfNothing() {
        val names = search("Morgan Avenue Williamsburg").map { it.name }
        assertEquals("Morgan Av", names.first())
    }

    @Test
    fun nonsenseFindsNothing() {
        assertTrue(search("zzzzqqq").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun streetNumbersSortLikeNumbers() {
        val keys = listOf("116 St", "8 St-NYU", "14 St").sortedBy { StationSearch.naturalKey(it) }
        assertEquals(listOf("8 St-NYU", "14 St", "116 St"), keys)
    }
}
