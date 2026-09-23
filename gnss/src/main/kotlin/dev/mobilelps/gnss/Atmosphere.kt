package dev.mobilelps.gnss

import dev.mobilelps.core.Geodetic
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** GPS broadcast ionosphere model parameters (alpha: s, s/semicircle...; beta: s, s/semicircle...). */
data class KlobucharCoefficients(
    val alpha: List<Double>,
    val beta: List<Double>,
) {
    companion object {
        /**
         * Typical mid-activity broadcast values. The BKG merged navigation files carry no ionosphere header,
         * and single-frequency errors of the model itself are ~50%, so fixed values lose little.
         */
        val DEFAULT =
            KlobucharCoefficients(
                alpha = listOf(1.1176e-08, -7.4506e-09, -5.9605e-08, 1.1921e-07),
                beta = listOf(1.1674e+05, -2.2938e+05, -1.3107e+05, 1.0486e+06),
            )
    }
}

object Atmosphere {
    private const val SECONDS_PER_DAY = 86_400.0
    private const val MIN_PERIOD_S = 72_000.0
    private const val NIGHT_DELAY_S = 5e-9
    private const val PEAK_TIME_S = 50_400.0
    private const val MAX_PHASE = 1.57
    private const val GEOMAGNETIC_POLE_LAT = 0.064
    private const val GEOMAGNETIC_POLE_LON = 1.617
    private const val MAX_IPP_LAT = 0.416
    private const val LOCAL_TIME_PER_SEMICIRCLE = 43_200.0

    /** Klobuchar L1 ionospheric delay in meters. */
    fun ionosphereDelayM(
        coefficients: KlobucharCoefficients,
        receiver: Geodetic,
        azimuthRad: Double,
        elevationRad: Double,
        gpsSeconds: Double,
    ): Double {
        // Semicircle units as in IS-GPS-200 figure 20-4.
        val el = elevationRad / PI
        val psi = 0.0137 / (el + 0.11) - 0.022
        val phiI = (receiver.latDeg / 180 + psi * cos(azimuthRad)).coerceIn(-MAX_IPP_LAT, MAX_IPP_LAT)
        val lambdaI = receiver.lonDeg / 180 + psi * sin(azimuthRad) / cos(phiI * PI)
        val phiM = phiI + GEOMAGNETIC_POLE_LAT * cos((lambdaI - GEOMAGNETIC_POLE_LON) * PI)

        var t = LOCAL_TIME_PER_SEMICIRCLE * lambdaI + GnssTime.secondsOfWeek(gpsSeconds)
        t -= floor(t / SECONDS_PER_DAY) * SECONDS_PER_DAY

        val amplitude = max(0.0, polynomial(coefficients.alpha, phiM))
        val period = max(MIN_PERIOD_S, polynomial(coefficients.beta, phiM))
        val x = 2 * PI * (t - PEAK_TIME_S) / period
        val slant = 1.0 + 16.0 * (0.53 - el) * (0.53 - el) * (0.53 - el)
        val delay =
            if (abs(x) < MAX_PHASE) {
                NIGHT_DELAY_S + amplitude * (1 - x * x / 2 + x * x * x * x / 24)
            } else {
                NIGHT_DELAY_S
            }
        return slant * delay * GnssTime.SPEED_OF_LIGHT_MPS
    }

    /** Simple tropospheric delay model for a standard atmosphere, meters. */
    fun troposphereDelayM(elevationRad: Double): Double = 2.47 / (sin(elevationRad) + 0.0121)

    private fun polynomial(
        c: List<Double>,
        x: Double,
    ): Double = c[0] + x * (c[1] + x * (c[2] + x * c[3]))
}
