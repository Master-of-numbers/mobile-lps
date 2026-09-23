package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import dev.mobilelps.core.Geodetic
import dev.mobilelps.core.Wgs84
import kotlin.math.asin

/** Elevation of satellites as seen from a reference position, used to catch signals from "impossible" satellites. */
object SkyCheck {
    /** Elevation in degrees for each satellite with a known ephemeris, keyed by constellation and svid. */
    fun elevationsDeg(
        store: EphemerisStore,
        reference: Geodetic,
        gpsSeconds: Double,
        satellites: Collection<Pair<Constellation, Int>>,
    ): Map<Pair<Constellation, Int>, Double> {
        val receiver = Wgs84.toEcef(reference)
        return satellites
            .mapNotNull { key ->
                val eph = store.select(key.first, key.second, gpsSeconds) ?: return@mapNotNull null
                val diff = SatelliteOrbit.state(eph, gpsSeconds).position - receiver
                val enu = Wgs84.toEnu(diff, reference)
                key to Math.toDegrees(asin(enu.up / diff.norm()))
            }.toMap()
    }
}
