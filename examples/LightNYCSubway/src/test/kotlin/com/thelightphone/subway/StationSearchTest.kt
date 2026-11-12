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
    fun aLineNarrowsTheSearch() {
        assertEquals("14 St (1 2 3 F M L)", first("14 st L 1"))
        assertTrue(search("L train").all { "L" in it.routes })
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
