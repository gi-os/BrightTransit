package com.thelightphone.subway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Search measured against what riders type. Each row of rider-queries.tsv is a query and the
 * station that has to come first for it, so a change to the ranking shows up here as a list
 * of the rows it broke.
 */
class RiderQueriesTest {
    private class Row(val typed: String, val name: String, val line: String?)

    private val rows = File("src/test/resources/rider-queries.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("\t") }
        .map { Row(it[0], it[1], it.getOrNull(2)) }

    private fun search(query: String) = Fixtures.catalog.search.search(query)

    @Test
    fun everyRowFindsItsStationFirst() {
        val misses = rows.mapNotNull { row ->
            val first = search(row.typed).firstOrNull()
            val right = first != null && first.name == row.name && (row.line == null || row.line in first.routes)
            val wanted = row.name + row.line?.let { " ($it)" }.orEmpty()
            val found = first?.let { "${it.name} (${it.routes.joinToString(" ")})" } ?: "nothing"
            if (right) null else "\"${row.typed}\" should find $wanted, found $found"
        }
        assertTrue(misses.isEmpty(), "${misses.size} of ${rows.size} queries miss:\n" + misses.joinToString("\n"))
    }

    @Test
    fun theListIsLongEnoughToMeanSomething() {
        assertTrue(rows.size >= 100, "only ${rows.size} rows")
        assertEquals(rows.size, rows.map { it.typed.lowercase() }.toSet().size, "a query is listed twice")
    }

    @Test
    fun everyRowNamesARealStation() {
        val unknown = rows.filter { row ->
            Fixtures.catalog.stations.none { it.name == row.name && (row.line == null || row.line in it.routes) }
        }
        assertTrue(unknown.isEmpty(), unknown.joinToString { "${it.name} ${it.line.orEmpty()}" })
    }

    @Test
    fun everyStationComesFirstForItsOwnName() {
        val sharing = Fixtures.catalog.stations.groupBy { it.name }
        val misses = Fixtures.catalog.stations.filter { station ->
            // Stations that share a name share the top of the list.
            station !in search(station.name).take(sharing.getValue(station.name).size)
        }
        assertTrue(misses.isEmpty(), misses.joinToString { it.name })
    }

    @Test
    fun everyOtherNameBringsItsStationIntoTheTopThree() {
        val misses = Fixtures.catalog.stations.flatMap { station ->
            station.aka.filter { station !in search(it).take(3) }.map { "$it (${station.name})" }
        }
        assertTrue(misses.isEmpty(), misses.joinToString())
    }
}
