package com.thelightphone.subway

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Loads the bundled station catalog and persists the user's starred station ids.
 *
 * @param catalogJson raw contents of assets/stations.json (read once by the screen
 *                    via lightContext.readAsset and handed in).
 */
class StationStore(
    private val dataStore: DataStore<Preferences>,
    catalogJson: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val all: List<Station> = json.decodeFromString<StationsFile>(catalogJson).stations
    private val byId: Map<String, Station> = all.associateBy { it.id }

    fun station(id: String): Station? = byId[id]

    /** Case-insensitive substring match on station name, capped for the small screen. */
    fun search(query: String, limit: Int = 40): List<Station> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { it.name.lowercase().contains(q) }
            .sortedWith(compareByDescending<Station> { it.name.lowercase().startsWith(q) }
                .thenBy { it.name })
            .take(limit)
            .toList()
    }

    suspend fun starredIds(): List<String> {
        val raw = dataStore.data.first()[STARRED] ?: return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    suspend fun starredStations(): List<Station> =
        starredIds().mapNotNull { byId[it] }

    suspend fun isStarred(id: String): Boolean = starredIds().contains(id)

    suspend fun toggleStar(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[STARRED]?.split("|")?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
            if (!current.remove(id)) current.add(id)
            prefs[STARRED] = current.joinToString("|")
        }
    }

    private companion object {
        val STARRED = stringPreferencesKey("starred_station_ids")
    }
}
