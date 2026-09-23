package dev.mobilelps.service

import dev.mobilelps.core.CellScan
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.FixSource
import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.Wgs84
import dev.mobilelps.core.WifiAccessPoint
import dev.mobilelps.core.WifiScan
import dev.mobilelps.lbs.AccessPointFilter
import dev.mobilelps.lbs.CellAccuracy
import dev.mobilelps.lbs.LbsLocation
import dev.mobilelps.lbs.LbsResult
import dev.mobilelps.lbs.NetworkLocator
import dev.mobilelps.lbs.NetworkQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * Position from cell towers and Wi-Fi access points. Scans are collected independently; every
 * [queryIntervalMillis] the latest fresh data of both is sent to the [NetworkLocator] in one query.
 */
class NetworkPositioning(
    private val cellScans: Flow<CellScan>,
    private val wifiScans: Flow<WifiScan>,
    private val locator: NetworkLocator,
    private val clock: EngineClock,
    private val io: CoroutineDispatcher,
    private val queryIntervalMillis: Long = DEFAULT_QUERY_INTERVAL_MILLIS,
    /** Adds a periodic Wi-Fi-only lookup that tells whether the access points are known at all. */
    private val diagnostics: Boolean = false,
) {
    private var lastWifiOnlyNanos: Long? = null

    private val _fix = MutableStateFlow<PositionFix?>(null)
    val fix: StateFlow<PositionFix?> = _fix.asStateFlow()

    private val _stats = MutableStateFlow(LbsStats())
    val stats: StateFlow<LbsStats> = _stats.asStateFlow()

    private val latestCells = MutableStateFlow<CellScan?>(null)
    private val latestWifi = MutableStateFlow<WifiScan?>(null)

    suspend fun run(): Unit =
        coroutineScope {
            launch {
                cellScans.collect { scan ->
                    latestCells.value = scan
                    _stats.update {
                        it.copy(
                            cells = scan.cells.size,
                            visibleCells = scan.visibleCells,
                            cellList = scan.cells,
                        )
                    }
                }
            }
            launch {
                wifiScans.collect { scan ->
                    latestWifi.value = scan
                    val usable = AccessPointFilter.usable(scan.accessPoints).map { it.bssid }.toSet()
                    _stats.update {
                        it.copy(
                            wifiSeen = scan.accessPoints.size,
                            wifiUsable = usable.size,
                            wifiThrottled = !scan.scanRequestAccepted,
                            wifiList = scan.accessPoints.map { ap -> ap to (ap.bssid in usable) },
                        )
                    }
                }
            }
            // The first query waits for the first scan instead of a full interval.
            merge(latestCells.filterNotNull(), latestWifi.filterNotNull()).first()
            while (true) {
                query()
                delay(queryIntervalMillis)
            }
        }

    private suspend fun query() {
        val now = clock.elapsedRealtimeNanos()
        val cells =
            latestCells.value
                ?.takeIf { now - it.elapsedRealtimeNanos <= CELLS_MAX_AGE_NANOS }
                ?.cells
                .orEmpty()
        val wifi =
            latestWifi.value
                ?.takeIf { now - it.elapsedRealtimeNanos <= WIFI_MAX_AGE_NANOS }
                ?.let { AccessPointFilter.usable(it.accessPoints) }
                .orEmpty()
        val query = NetworkQuery(cells, wifi)
        if (query.isEmpty) {
            _stats.update { it.copy(lastResult = "No identified cells or Wi-Fi access points") }
            return
        }
        val description =
            when (val result = withContext(io) { locator.locate(query) }) {
                is LbsResult.Found -> accept(result.location, cells, wifi, now)
                else -> describe(result)
            }
        _stats.update { it.copy(fix = _fix.value, lastResult = description) }
        if (diagnostics) checkWifiAlone(wifi, now)
    }

    /**
     * Publishes a found position. When Wi-Fi did not contribute (none sent, or the answer equals the cells-only
     * answer) the accuracy is raised to a realistic cell radius, because BeaconDB may claim tens of meters for a
     * cell it has seen only a few times.
     */
    private suspend fun accept(
        location: LbsLocation,
        cells: List<CellTower>,
        wifi: List<WifiAccessPoint>,
        now: Long,
    ): String {
        val cellBased = cells.isNotEmpty() && (wifi.isEmpty() || sameAsCellsOnly(location, cells))
        val accuracy = if (cellBased) max(location.accuracyM, CellAccuracy.floorM(cells)) else location.accuracyM
        _fix.value =
            PositionFix(
                latDeg = location.latDeg,
                lonDeg = location.lonDeg,
                altitudeM = null,
                horizontalAccuracyM = accuracy,
                speedMps = null,
                bearingDeg = null,
                timeMillis = clock.wallMillis(),
                elapsedRealtimeNanos = now,
                source = FixSource.LBS,
            )
        val basis =
            if (cellBased) {
                "cell-based, BeaconDB said ±%.0f m".format(
                    Locale.US,
                    location.accuracyM,
                )
            } else {
                "Wi-Fi"
            }
        return "Found ±%.0f m ($basis; ${cells.size} cells, ${wifi.size} Wi-Fi sent)".format(Locale.US, accuracy)
    }

    /** The cells-only answer is cached per cell set, so this costs one request per new serving cell. */
    private suspend fun sameAsCellsOnly(
        location: LbsLocation,
        cells: List<CellTower>,
    ): Boolean {
        val cellsOnly = withContext(io) { locator.locate(NetworkQuery(cells, emptyList())) }
        if (cellsOnly !is LbsResult.Found) return false
        val d = Wgs84.distanceM(location.latDeg, location.lonDeg, cellsOnly.location.latDeg, cellsOnly.location.lonDeg)
        return d < SAME_POSITION_M
    }

    private suspend fun checkWifiAlone(
        wifi: List<WifiAccessPoint>,
        now: Long,
    ) {
        val last = lastWifiOnlyNanos
        if (wifi.isEmpty() || (last != null && now - last < WIFI_ONLY_INTERVAL_NANOS)) return
        lastWifiOnlyNanos = now
        val result = withContext(io) { locator.locate(NetworkQuery(emptyList(), wifi)) }
        _stats.update { it.copy(wifiOnlyResult = describe(result)) }
    }

    private fun describe(result: LbsResult): String =
        when (result) {
            is LbsResult.Found -> {
                val l = result.location
                "Found ±%.0f m at %.5f, %.5f".format(Locale.US, l.accuracyM, l.latDeg, l.lonDeg)
            }

            LbsResult.NotFound -> {
                "Transmitters unknown to BeaconDB"
            }

            is LbsResult.Failed -> {
                "Error: ${result.message}"
            }
        }

    private companion object {
        const val DEFAULT_QUERY_INTERVAL_MILLIS = 10_000L
        const val CELLS_MAX_AGE_NANOS = 30_000_000_000L
        const val WIFI_MAX_AGE_NANOS = 60_000_000_000L
        const val WIFI_ONLY_INTERVAL_NANOS = 60_000_000_000L
        const val SAME_POSITION_M = 10.0
    }
}
