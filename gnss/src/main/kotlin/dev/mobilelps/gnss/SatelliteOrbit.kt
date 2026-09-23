package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import dev.mobilelps.core.Ecef
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SatelliteState(
    val position: Ecef,
    val velocity: Ecef,
    /** Satellite clock offset for L1/E1 single-frequency users, seconds (includes relativity and group delay). */
    val clockBias: Double,
    /** Satellite clock drift, seconds per second. */
    val clockDrift: Double,
)

/** Satellite position and clock from broadcast Keplerian elements (IS-GPS-200, Galileo OS SIS ICD). */
object SatelliteOrbit {
    const val EARTH_ROTATION_RATE = 7.2921151467e-5
    private const val GM_GPS = 3.986005e14
    private const val GM_GALILEO = 3.986004418e14
    private const val RELATIVISTIC_F = -4.442807633e-10
    private const val KEPLER_TOLERANCE = 1e-13
    private const val KEPLER_MAX_ITERATIONS = 30
    private const val VELOCITY_STEP_S = 0.5

    fun state(
        eph: KeplerEphemeris,
        gpsSeconds: Double,
    ): SatelliteState {
        val before = position(eph, gpsSeconds - VELOCITY_STEP_S).first
        val after = position(eph, gpsSeconds + VELOCITY_STEP_S).first
        val (position, eccentricAnomaly) = position(eph, gpsSeconds)
        val dt = gpsSeconds - eph.toc
        val relativistic = RELATIVISTIC_F * eph.eccentricity * eph.sqrtA * sin(eccentricAnomaly)
        return SatelliteState(
            position = position,
            velocity =
                Ecef(
                    (after.x - before.x) / (2 * VELOCITY_STEP_S),
                    (after.y - before.y) / (2 * VELOCITY_STEP_S),
                    (after.z - before.z) / (2 * VELOCITY_STEP_S),
                ),
            clockBias = eph.af0 + eph.af1 * dt + eph.af2 * dt * dt + relativistic - eph.groupDelay,
            clockDrift = eph.af1 + 2 * eph.af2 * dt,
        )
    }

    /** ECEF position at [gpsSeconds] and the eccentric anomaly used for the relativistic clock term. */
    private fun position(
        eph: KeplerEphemeris,
        gpsSeconds: Double,
    ): Pair<Ecef, Double> {
        val gm = if (eph.constellation == Constellation.GALILEO) GM_GALILEO else GM_GPS
        val a = eph.sqrtA * eph.sqrtA
        val tk = gpsSeconds - eph.toe
        val n = sqrt(gm / (a * a * a)) + eph.deltaN
        val m = eph.m0 + n * tk

        var e = m
        var iterations = 0
        do {
            val previous = e
            e = m + eph.eccentricity * sin(e)
            iterations++
        } while (abs(e - previous) >= KEPLER_TOLERANCE && iterations < KEPLER_MAX_ITERATIONS)

        val nu = atan2(sqrt(1 - eph.eccentricity * eph.eccentricity) * sin(e), cos(e) - eph.eccentricity)
        val phi = nu + eph.omega
        val sin2 = sin(2 * phi)
        val cos2 = cos(2 * phi)
        val u = phi + eph.cus * sin2 + eph.cuc * cos2
        val r = a * (1 - eph.eccentricity * cos(e)) + eph.crs * sin2 + eph.crc * cos2
        val inclination = eph.i0 + eph.cis * sin2 + eph.cic * cos2 + eph.idot * tk
        val xOrbit = r * cos(u)
        val yOrbit = r * sin(u)
        val toeOfWeek = GnssTime.secondsOfWeek(eph.toe)
        val node = eph.omega0 + (eph.omegaDot - EARTH_ROTATION_RATE) * tk - EARTH_ROTATION_RATE * toeOfWeek

        val position =
            Ecef(
                x = xOrbit * cos(node) - yOrbit * cos(inclination) * sin(node),
                y = xOrbit * sin(node) + yOrbit * cos(inclination) * cos(node),
                z = yOrbit * sin(inclination),
            )
        return position to e
    }

    /** Rotates a satellite position by the Earth rotation during the signal travel time (Sagnac effect). */
    fun rotateForTravelTime(
        position: Ecef,
        travelTimeS: Double,
    ): Ecef {
        val theta = EARTH_ROTATION_RATE * travelTimeS
        return Ecef(
            x = position.x * cos(theta) + position.y * sin(theta),
            y = -position.x * sin(theta) + position.y * cos(theta),
            z = position.z,
        )
    }
}
