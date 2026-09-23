package dev.mobilelps.service

import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.Wgs84
import dev.mobilelps.detector.SpoofingState

/**
 * Chooses which position to publish: GNSS while it is trusted; otherwise GNSS only if it lies inside the
 * cell/Wi-Fi position's accuracy circle and is more precise; otherwise the cell/Wi-Fi position itself.
 */
object LocationArbiter {
    const val GNSS_MAX_AGE_NANOS = 2_000_000_000L
    const val LBS_MAX_AGE_NANOS = 120_000_000_000L

    fun choose(
        state: SpoofingState?,
        gnss: PositionFix?,
        lbs: PositionFix?,
        nowElapsedNanos: Long,
        nowWallMillis: Long,
    ): PositionFix? {
        val freshGnss = gnss?.takeIf { nowElapsedNanos - it.elapsedRealtimeNanos <= GNSS_MAX_AGE_NANOS }
        val freshLbs = lbs?.takeIf { nowElapsedNanos - it.elapsedRealtimeNanos <= LBS_MAX_AGE_NANOS }
        val trusted = state == SpoofingState.CLEAN || state == SpoofingState.SUSPECT
        val usable = trusted || (freshLbs != null && freshGnss != null && refines(freshGnss, freshLbs))
        if (freshGnss != null && usable) return freshGnss
        // Navigation apps discard old fixes, so the network position is re-stamped on every publication.
        return freshLbs?.copy(timeMillis = nowWallMillis, elapsedRealtimeNanos = nowElapsedNanos)
    }

    /**
     * A distrusted GNSS fix is still used when it cannot mislead beyond what the network position already
     * allows: it lies within the network accuracy circle and is more precise than it.
     */
    fun refines(
        gnss: PositionFix,
        lbs: PositionFix,
    ): Boolean {
        val distance = Wgs84.distanceM(gnss.latDeg, gnss.lonDeg, lbs.latDeg, lbs.lonDeg)
        return gnss.horizontalAccuracyM < lbs.horizontalAccuracyM &&
            distance <= lbs.horizontalAccuracyM + gnss.horizontalAccuracyM
    }
}
