package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import kotlin.math.abs

/** Immutable set of broadcast ephemerides with selection of the best record for a given time. */
class EphemerisStore(
    ephemerides: List<KeplerEphemeris>,
    val ionosphere: KlobucharCoefficients = KlobucharCoefficients.DEFAULT,
) {
    private val bySatellite: Map<Pair<Constellation, Int>, List<KeplerEphemeris>> =
        ephemerides
            .filter { it.isHealthy }
            .filter { it.constellation != Constellation.GALILEO || it.isGalileoInav }
            .groupBy { it.constellation to it.svid }

    val size: Int get() = bySatellite.values.sumOf { it.size }

    /** Latest ephemeris reference time in the store, GPS seconds, or null when empty. */
    val latestToe: Double? get() = bySatellite.values.flatten().maxOfOrNull { it.toe }

    fun select(
        constellation: Constellation,
        svid: Int,
        gpsSeconds: Double,
    ): KeplerEphemeris? =
        bySatellite[constellation to svid]
            ?.minByOrNull { abs(gpsSeconds - it.toe) }
            ?.takeIf { abs(gpsSeconds - it.toe) <= MAX_AGE_SECONDS }

    fun merge(other: EphemerisStore): EphemerisStore =
        EphemerisStore(bySatellite.values.flatten() + other.bySatellite.values.flatten(), ionosphere)

    companion object {
        /** Broadcast orbits degrade beyond their fit interval; four hours still gives meter-level orbits. */
        const val MAX_AGE_SECONDS = 4 * 3600.0
    }
}
