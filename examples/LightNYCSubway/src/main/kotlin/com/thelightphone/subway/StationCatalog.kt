package com.thelightphone.subway

import kotlinx.serialization.json.Json

/**
 * Parses assets/stations.json exactly once and caches it process-wide.
 *
 * Parsing ~500 stations is CPU work that must NOT run on the main thread during
 * composition (that was the long black screen at launch). Call [load] from a
 * background dispatcher; every screen then shares the cached list instantly.
 */
object StationCatalog {

    @Volatile
    private var cache: List<Station>? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun isLoaded(): Boolean = cache != null

    fun cachedOrEmpty(): List<Station> = cache ?: emptyList()

    /** Idempotent, thread-safe. Returns the parsed catalog (empty on failure). */
    fun load(readAsset: (String) -> ByteArray): List<Station> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val parsed = runCatching {
                    val text = String(readAsset("stations.json"), Charsets.UTF_8)
                    json.decodeFromString<StationsFile>(text).stations
                }.getOrElse { emptyList() }
                cache = parsed
                parsed
            }
        }
    }
}
