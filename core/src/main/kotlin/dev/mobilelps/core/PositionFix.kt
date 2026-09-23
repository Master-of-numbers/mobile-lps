package dev.mobilelps.core

enum class FixSource { GNSS, LBS }

/** A position estimate ready to be published to the Android location API. */
data class PositionFix(
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeM: Double?,
    /** Horizontal accuracy, 68% confidence radius, as Android `Location.getAccuracy` expects. */
    val horizontalAccuracyM: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    /** Wall-clock UTC time of the fix. */
    val timeMillis: Long,
    /** `SystemClock.elapsedRealtimeNanos` at the moment the fix was valid. */
    val elapsedRealtimeNanos: Long,
    val source: FixSource,
)
