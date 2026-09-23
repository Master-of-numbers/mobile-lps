package dev.mobilelps.lbs

import dev.mobilelps.core.CellTower
import dev.mobilelps.core.WifiAccessPoint

/** Position estimated from cell towers and Wi-Fi access points. */
data class LbsLocation(
    val latDeg: Double,
    val lonDeg: Double,
    /** Accuracy radius reported by the source, meters. */
    val accuracyM: Double,
)

sealed interface LbsResult {
    data class Found(
        val location: LbsLocation,
    ) : LbsResult

    /** The source knows none of the transmitters. */
    data object NotFound : LbsResult

    data class Failed(
        val message: String,
    ) : LbsResult
}

/** Observed transmitters. [wifi] must already be filtered with [AccessPointFilter]. */
data class NetworkQuery(
    val cells: List<CellTower>,
    val wifi: List<WifiAccessPoint>,
) {
    val isEmpty: Boolean get() = cells.isEmpty() && wifi.isEmpty()
}

/** Resolves observed transmitters into a position. Implementations may block and must be called off the main thread. */
fun interface NetworkLocator {
    fun locate(query: NetworkQuery): LbsResult
}
