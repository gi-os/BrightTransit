package com.thelightphone.subway.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The two things the tool remembers: which stations are saved (in the order they were
 * saved) and which lines are favorites. Both stay on the phone.
 *
 * The keys are the ones version 1 wrote under, so what was starred there is saved here.
 */
class SavedStore(private val dataStore: DataStore<Preferences>) {

    val stationIds: Flow<List<String>> = dataStore.data.map { it[STATIONS].parts() }.distinctUntilChanged()

    val favoriteRoutes: Flow<Set<String>> = dataStore.data.map { it[ROUTES].parts().toSet() }.distinctUntilChanged()

    /** Rewrites the saved list in today's ids if an older version wrote it. See [Catalog.canonicalIds]. */
    suspend fun bringUpToDate(catalog: Catalog) {
        dataStore.edit { prefs ->
            val current = prefs[STATIONS].parts()
            val canonical = catalog.canonicalIds(current)
            if (canonical != current) prefs[STATIONS] = canonical.joinToString("|")
        }
    }

    suspend fun toggleStation(id: String) = toggle(STATIONS, id)

    suspend fun toggleRoute(route: String) = toggle(ROUTES, route)

    private suspend fun toggle(key: Preferences.Key<String>, value: String) {
        dataStore.edit { prefs ->
            val current = prefs[key].parts().toMutableList()
            if (!current.remove(value)) current.add(value)
            prefs[key] = current.joinToString("|")
        }
    }

    private fun String?.parts(): List<String> = this?.split("|")?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        val STATIONS = stringPreferencesKey("starred_station_ids")
        val ROUTES = stringPreferencesKey("favorite_routes")
    }
}
