package dev.mobilelps.detector

import dev.mobilelps.core.PositionFix

/** What the GNSS receiver reported in one epoch. */
data class GnssEvidence(
    /** Position computed from raw measurements, or null when no solution was possible. */
    val fix: PositionFix?,
    /** RMS of post-fit pseudorange residuals, meters. */
    val residualRmsM: Double?,
    /** GNSS-derived UTC minus the system wall clock, seconds. */
    val timeOffsetS: Double?,
    /** C/N0 of every tracked L1/E1 signal, dB-Hz. */
    val cn0DbHz: List<Double>,
    /** L1/E1 automatic gain control level, dB. */
    val agcDb: Double?,
    /** Tracked satellites whose ephemeris puts them below the horizon of the LBS position. */
    val satellitesBelowHorizon: Int,
    /** Tracked satellites that could be checked against the LBS position. */
    val satellitesChecked: Int,
)

/** Latest position from cell towers. */
data class LbsEvidence(
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Double,
    val elapsedRealtimeNanos: Long,
)

data class DetectorInput(
    val elapsedRealtimeNanos: Long,
    /** Null when no GNSS measurement event arrived since the previous update. */
    val gnss: GnssEvidence?,
    val lbs: LbsEvidence?,
)
