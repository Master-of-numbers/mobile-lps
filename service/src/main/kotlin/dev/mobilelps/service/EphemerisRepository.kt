package dev.mobilelps.service

import dev.mobilelps.gnss.EphemerisDownloader
import dev.mobilelps.gnss.EphemerisStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Keeps broadcast ephemerides fresh: loads the on-disk copy at start, then downloads new data periodically.
 * The cached files let the app compute positions for a few hours without internet.
 */
class EphemerisRepository(
    private val downloader: EphemerisDownloader,
    private val cacheDir: File,
    private val clock: EngineClock,
    private val io: CoroutineDispatcher,
) : LpsEngine.Ephemerides {
    private val _store = MutableStateFlow<EphemerisStore?>(null)
    override val store: StateFlow<EphemerisStore?> = _store.asStateFlow()

    private val _stats = MutableStateFlow(EphemerisStats())
    override val stats: StateFlow<EphemerisStats> = _stats.asStateFlow()

    override suspend fun run() {
        loadCache()
        while (true) {
            val updatedAt = _stats.value.updatedAtMillis
            val due = updatedAt == null || clock.wallMillis() - updatedAt >= REFRESH_MILLIS
            if (due) {
                val ok = download()
                delay(if (ok) REFRESH_MILLIS else RETRY_MILLIS)
            } else {
                delay(REFRESH_MILLIS - (clock.wallMillis() - updatedAt))
            }
        }
    }

    private suspend fun loadCache() {
        val files = withContext(io) { cacheDir.listFiles { f -> f.name.endsWith(CACHE_SUFFIX) }.orEmpty().toList() }
        val fresh = files.filter { clock.wallMillis() - it.lastModified() <= CACHE_MAX_AGE_MILLIS }
        if (fresh.isEmpty()) return
        runCatching { withContext(io) { fresh.map { EphemerisDownloader.parseGzip(it.readBytes()) } } }
            .onSuccess { stores ->
                publish(stores, fresh.maxOf { it.lastModified() }, null)
            }
    }

    private suspend fun download(): Boolean =
        try {
            val stores =
                withContext(io) {
                    val files = downloader.download(Instant.ofEpochMilli(clock.wallMillis()))
                    cacheDir.mkdirs()
                    cacheDir.listFiles { f -> f.name.endsWith(CACHE_SUFFIX) }?.forEach(File::delete)
                    files.mapIndexed { index, bytes ->
                        File(cacheDir, "brdc-$index$CACHE_SUFFIX").writeBytes(bytes)
                        EphemerisDownloader.parseGzip(bytes)
                    }
                }
            publish(stores, clock.wallMillis(), null)
            true
        } catch (e: IOException) {
            _stats.value = _stats.value.copy(error = e.message ?: e.javaClass.simpleName)
            false
        } catch (e: IllegalArgumentException) {
            _stats.value = _stats.value.copy(error = e.message ?: "Malformed ephemeris file")
            false
        }

    private fun publish(
        stores: List<EphemerisStore>,
        updatedAt: Long,
        error: String?,
    ) {
        val merged = stores.reduce(EphemerisStore::merge)
        _store.value = merged
        _stats.value = EphemerisStats(records = merged.size, updatedAtMillis = updatedAt, error = error)
    }

    private companion object {
        const val REFRESH_MILLIS = 30 * 60 * 1000L
        const val RETRY_MILLIS = 2 * 60 * 1000L
        const val CACHE_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
        const val CACHE_SUFFIX = ".rnx.gz"
    }
}
