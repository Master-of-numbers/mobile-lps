package dev.mobilelps.service

import dev.mobilelps.core.CellTower
import dev.mobilelps.core.Constellation
import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.WifiAccessPoint
import dev.mobilelps.detector.Assessment

enum class MockState { INACTIVE, ACTIVE, NOT_MOCK_APP, FAILED }

data class GnssStats(
    val epochs: Long = 0,
    /** `elapsedRealtimeNanos` of the last measurement event; shows whether raw measurements keep flowing. */
    val lastEpochElapsedNanos: Long? = null,
    val tracked: Int = 0,
    val usable: Int = 0,
    /** Whether the receiver reports its full clock bias; otherwise time is derived from the satellites. */
    val receiverClock: Boolean? = null,
    val fix: PositionFix? = null,
    val satellitesUsed: Int = 0,
    val residualRmsM: Double? = null,
    val timeOffsetS: Double? = null,
    val error: String? = null,
    /** Every signal of the last measurement event, for diagnostics. */
    val signals: List<SignalInfo> = emptyList(),
)

data class SignalInfo(
    val constellation: Constellation,
    val svid: Int,
    val carrierMhz: Double?,
    val cn0DbHz: Double,
    /** Raw `GnssMeasurement.getState()` bits. */
    val state: Int,
    /** Converted into a pseudorange (L1/E1, time of week known, low uncertainty). */
    val usable: Boolean,
    val elevationDeg: Double?,
    /** Post-fit residual when the signal was used in the position, meters. */
    val residualM: Double?,
)

data class LbsStats(
    /** Cells with a complete identity, sent to the lookup. */
    val cells: Int = 0,
    /** All reported cells, including neighbours known only by PCI. */
    val visibleCells: Int = 0,
    val wifiSeen: Int = 0,
    /** Access points left after dropping opted-out and randomized ones. */
    val wifiUsable: Int = 0,
    /** The last scan request was rejected by Android's scan throttling. */
    val wifiThrottled: Boolean = false,
    val fix: PositionFix? = null,
    val lastResult: String? = null,
    /** Identified cells of the last scan, for diagnostics. */
    val cellList: List<CellTower> = emptyList(),
    /** Access points of the last scan with the filter verdict, for diagnostics. */
    val wifiList: List<Pair<WifiAccessPoint, Boolean>> = emptyList(),
    /** Result of a Wi-Fi-only lookup (diagnostics mode): shows whether BeaconDB knows the access points. */
    val wifiOnlyResult: String? = null,
)

data class EphemerisStats(
    val records: Int = 0,
    val updatedAtMillis: Long? = null,
    val error: String? = null,
)

/** Snapshot of the whole pipeline for the UI and the notification. */
data class LpsStatus(
    val running: Boolean = false,
    val mock: MockState = MockState.INACTIVE,
    val mockError: String? = null,
    val assessment: Assessment? = null,
    val output: PositionFix? = null,
    val gnss: GnssStats = GnssStats(),
    val lbs: LbsStats = LbsStats(),
    val ephemeris: EphemerisStats = EphemerisStats(),
)
