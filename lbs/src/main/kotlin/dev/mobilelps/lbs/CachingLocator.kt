package dev.mobilelps.lbs

/**
 * Remembers results per set of transmitter identities, so a stationary phone does not query the network on
 * every scan. Signal strength and age are ignored in the key.
 */
class CachingLocator(
    private val delegate: NetworkLocator,
    private val nowMillis: () -> Long,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : NetworkLocator {
    private data class Entry(
        val result: LbsResult,
        val storedAt: Long,
    )

    private val cache =
        object : LinkedHashMap<Set<String>, Entry>(maxEntries, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Set<String>, Entry>) = size > maxEntries
        }

    @Synchronized
    override fun locate(query: NetworkQuery): LbsResult {
        val key =
            query.cells.map { "${it.radio}:${it.mcc}:${it.mnc}:${it.areaCode}:${it.cellId}" }.toSet() +
                query.wifi.map { it.bssid }
        val now = nowMillis()
        cache[key]?.takeIf { now - it.storedAt <= ttlMillis }?.let { return it.result }
        val result = delegate.locate(query)
        // Failures are transient (network), so they are not cached.
        if (result !is LbsResult.Failed) cache[key] = Entry(result, now)
        return result
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 256
        const val LOAD_FACTOR = 0.75f
    }
}
