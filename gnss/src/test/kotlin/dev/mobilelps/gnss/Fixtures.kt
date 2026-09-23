package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import dev.mobilelps.core.Ecef
import dev.mobilelps.core.Geodetic
import dev.mobilelps.core.Wgs84
import kotlin.math.asin
import kotlin.math.atan2

internal object Fixtures {
    /** 2026-09-23 02:00:00 GPS time: the reference epoch of every record in the fixture. */
    val EPOCH = GnssTime.fromCalendar(2026, 9, 23, 2, 0, 0.0)

    val KYIV = Geodetic(50.4501, 30.5234, 179.0)

    fun rinex(): RinexNavParser.Result =
        checkNotNull(javaClass.getResourceAsStream("/brdc-2026-266-0200.rnx")).reader().use(RinexNavParser::parse)

    fun store(): EphemerisStore = EphemerisStore(rinex().ephemerides)

    /**
     * Builds error-free observations for a receiver at [truth] using the same signal model as [PvtSolver],
     * so the solver must recover the inputs exactly.
     */
    fun observations(
        store: EphemerisStore,
        truth: Geodetic,
        receiveTime: Double,
        clockBiasM: Map<Constellation, Double>,
        velocityEcef: Ecef = Ecef(0.0, 0.0, 0.0),
        clockDriftMps: Double = 0.0,
        minElevationDeg: Double = 15.0,
    ): List<Observation> {
        val receiver = Wgs84.toEcef(truth)
        val satellites = (1..36).map { Constellation.GPS to it } + (1..36).map { Constellation.GALILEO to it }
        return satellites.mapNotNull { (constellation, svid) ->
            val eph = store.select(constellation, svid, receiveTime) ?: return@mapNotNull null
            val bias = clockBiasM[constellation] ?: return@mapNotNull null
            var pseudorange = 2.2e7
            var satellite = SatelliteOrbit.state(eph, receiveTime)
            var diff = satellite.position - receiver
            repeat(6) {
                var transmit = receiveTime - pseudorange / GnssTime.SPEED_OF_LIGHT_MPS
                satellite = SatelliteOrbit.state(eph, transmit)
                transmit -= satellite.clockBias
                satellite = SatelliteOrbit.state(eph, transmit)
                var position = satellite.position
                var range = (position - receiver).norm()
                repeat(2) {
                    position =
                        SatelliteOrbit.rotateForTravelTime(satellite.position, range / GnssTime.SPEED_OF_LIGHT_MPS)
                    range = (position - receiver).norm()
                }
                diff = position - receiver
                val enu = Wgs84.toEnu(diff, truth)
                val elevation = asin(enu.up / range)
                val atmosphere =
                    Atmosphere.ionosphereDelayM(
                        store.ionosphere,
                        truth,
                        atan2(enu.east, enu.north),
                        elevation,
                        receiveTime,
                    ) +
                        Atmosphere.troposphereDelayM(elevation)
                pseudorange = range + bias - satellite.clockBias * GnssTime.SPEED_OF_LIGHT_MPS + atmosphere
            }
            val range = diff.norm()
            val elevationDeg = Math.toDegrees(asin(Wgs84.toEnu(diff, truth).up / range))
            if (elevationDeg < minElevationDeg) return@mapNotNull null
            val los = doubleArrayOf(diff.x / range, diff.y / range, diff.z / range)
            val relative = satellite.velocity - velocityEcef
            val rangeRate = relative.x * los[0] + relative.y * los[1] + relative.z * los[2]
            Observation(
                constellation = constellation,
                svid = svid,
                pseudorangeM = pseudorange,
                sigmaM = 5.0,
                pseudorangeRateMps = rangeRate + clockDriftMps - satellite.clockDrift * GnssTime.SPEED_OF_LIGHT_MPS,
                rateSigmaMps = 0.2,
                cn0DbHz = 40.0,
            )
        }
    }
}
