package dev.mobilelps.core

/** Mirrors `android.location.GnssStatus.CONSTELLATION_*` so pure modules stay Android-free. */
enum class Constellation { UNKNOWN, GPS, SBAS, GLONASS, QZSS, BEIDOU, GALILEO, IRNSS }

/** Bits of `android.location.GnssMeasurement.getState()` used by this project. */
object GnssState {
    const val CODE_LOCK = 1
    const val TOW_DECODED = 1 shl 3
    const val MSEC_AMBIGUOUS = 1 shl 4
    const val GAL_E1C_2ND_CODE_LOCK = 1 shl 11
    const val TOW_KNOWN = 1 shl 14
}

/** Subset of `android.location.GnssClock`. */
data class GnssClockData(
    val timeNanos: Long,
    val fullBiasNanos: Long?,
    val biasNanos: Double,
    val hardwareClockDiscontinuityCount: Int,
    /** `SystemClock.elapsedRealtimeNanos` matching [timeNanos], if the chipset reports it. */
    val elapsedRealtimeNanos: Long?,
)

/** Subset of `android.location.GnssMeasurement`. */
data class GnssMeasurementData(
    val constellation: Constellation,
    val svid: Int,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val timeOffsetNanos: Double,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncertaintyMps: Double,
    val carrierFrequencyHz: Double?,
)

/** One `GnssMeasurementsEvent`. */
data class GnssEpoch(
    val clock: GnssClockData,
    val measurements: List<GnssMeasurementData>,
    /** Automatic gain control level of the L1/E1 band, dB, if reported. */
    val agcDb: Double?,
    /** Wall clock (`System.currentTimeMillis`) when the event was received. */
    val receivedWallMillis: Long,
    /** `SystemClock.elapsedRealtimeNanos` when the event was received. */
    val receivedElapsedNanos: Long,
)
