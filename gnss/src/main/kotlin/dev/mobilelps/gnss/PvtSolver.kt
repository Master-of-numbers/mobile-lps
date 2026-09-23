package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import dev.mobilelps.core.Ecef
import dev.mobilelps.core.Geodetic
import dev.mobilelps.core.Wgs84
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class PvtSolution(
    val position: Geodetic,
    val ecef: Ecef,
    /** Horizontal 1-sigma accuracy estimate, meters. */
    val horizontalAccuracyM: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    /** GPS time of signal reception corrected by the solved receiver clock bias, GPS seconds. */
    val gpsTime: Double,
    val satellitesUsed: Int,
    /** RMS of post-fit pseudorange residuals, meters. */
    val residualRmsM: Double,
    val satellites: List<UsedSatellite> = emptyList(),
)

/** A satellite that contributed to a [PvtSolution]. */
data class UsedSatellite(
    val constellation: Constellation,
    val svid: Int,
    val elevationDeg: Double,
    val residualM: Double,
)

/**
 * Single-point weighted least-squares position, velocity and time from L1/E1 pseudoranges.
 * Unknowns: ECEF position plus one receiver clock bias per constellation.
 */
class PvtSolver(
    private val store: EphemerisStore,
) {
    private class Row(
        val obs: Observation,
        val satellite: SatelliteState,
        val los: DoubleArray,
        val elevationRad: Double,
        val predictedM: Double,
        val sigmaM: Double,
    )

    fun solve(
        epoch: ObservationEpoch,
        initialGuess: Ecef? = null,
    ): PvtSolution? {
        var observations =
            epoch.observations.filter {
                store.select(it.constellation, it.svid, epoch.receiveTime) !=
                    null
            }
        repeat(MAX_EXCLUSIONS + 1) {
            val constellations = observations.map { it.constellation }.distinct().sorted()
            if (observations.size < 3 + constellations.size) return null
            val fit = fit(epoch.receiveTime, observations, constellations, initialGuess) ?: return null
            val outlier = fit.worstOutlier(observations.size - (3 + constellations.size))
            if (outlier == null) return fit.toSolution(epoch.receiveTime)
            observations = observations - outlier
        }
        return null
    }

    private inner class Fit(
        val ecef: Ecef,
        val clockBiasesM: Map<Constellation, Double>,
        val rows: List<Row>,
        val covariance: Array<DoubleArray>,
        val residualsM: DoubleArray,
    ) {
        fun worstOutlier(redundancy: Int): Observation? {
            if (redundancy < 1) return null
            val worst = residualsM.indices.maxBy { abs(residualsM[it]) / rows[it].sigmaM }
            val normalized = abs(residualsM[worst]) / rows[worst].sigmaM
            return rows[worst].obs.takeIf { normalized > OUTLIER_SIGMAS && abs(residualsM[worst]) > OUTLIER_MIN_M }
        }

        fun toSolution(receiveTime: Double): PvtSolution {
            val geodetic = Wgs84.toGeodetic(ecef)
            val rms = sqrt(residualsM.sumOf { it * it } / residualsM.size)
            val redundancy = rows.size - covariance.size
            val varianceFactor =
                if (redundancy > 0) {
                    max(1.0, rows.indices.sumOf { (residualsM[it] / rows[it].sigmaM).let { r -> r * r } } / redundancy)
                } else {
                    1.0
                }
            val velocity = velocity(receiveTime, geodetic)
            val gpsClockBias = clockBiasesM[Constellation.GPS] ?: clockBiasesM.values.first()
            return PvtSolution(
                position = geodetic,
                ecef = ecef,
                horizontalAccuracyM = horizontalSigma(geodetic) * sqrt(varianceFactor),
                speedMps = velocity?.first,
                bearingDeg = velocity?.second,
                gpsTime = receiveTime - gpsClockBias / GnssTime.SPEED_OF_LIGHT_MPS,
                satellitesUsed = rows.size,
                residualRmsM = rms,
                satellites =
                    rows.mapIndexed { i, r ->
                        UsedSatellite(r.obs.constellation, r.obs.svid, Math.toDegrees(r.elevationRad), residualsM[i])
                    },
            )
        }

        private fun horizontalSigma(origin: Geodetic): Double {
            val lat = Math.toRadians(origin.latDeg)
            val lon = Math.toRadians(origin.lonDeg)
            val east = doubleArrayOf(-sin(lon), kotlin.math.cos(lon), 0.0)
            val north = doubleArrayOf(-sin(lat) * kotlin.math.cos(lon), -sin(lat) * sin(lon), kotlin.math.cos(lat))

            fun variance(axis: DoubleArray) =
                (0..2).sumOf { i ->
                    (0..2).sumOf { j ->
                        axis[i] * covariance[i][j] *
                            axis[j]
                    }
                }
            return sqrt(max(0.0, variance(east) + variance(north)))
        }

        /** Receiver velocity from pseudorange rates: returns horizontal speed and bearing. */
        private fun velocity(
            receiveTime: Double,
            origin: Geodetic,
        ): Pair<Double, Double?>? {
            if (rows.size < 4) return null
            val h = rows.map { doubleArrayOf(-it.los[0], -it.los[1], -it.los[2], 1.0) }
            val y =
                DoubleArray(rows.size) { i ->
                    val r = rows[i]
                    val satRate =
                        r.satellite.velocity.x * r.los[0] + r.satellite.velocity.y * r.los[1] +
                            r.satellite.velocity.z * r.los[2]
                    r.obs.pseudorangeRateMps - satRate + r.satellite.clockDrift * GnssTime.SPEED_OF_LIGHT_MPS
                }
            val w = DoubleArray(rows.size) { 1 / (rows[it].obs.rateSigmaMps * rows[it].obs.rateSigmaMps) }
            val (v, _) = LinearAlgebra.weightedLeastSquares(h, y, w) ?: return null
            if (v.any { it.isNaN() } || receiveTime.isNaN()) return null
            val enu = Wgs84.toEnu(Ecef(v[0], v[1], v[2]), origin)
            val speed = hypot(enu.east, enu.north)
            val bearing =
                if (speed >=
                    MIN_SPEED_FOR_BEARING_MPS
                ) {
                    Math.toDegrees(atan2(enu.east, enu.north)).mod(FULL_CIRCLE_DEG)
                } else {
                    null
                }
            return speed to bearing
        }
    }

    private fun fit(
        receiveTime: Double,
        observations: List<Observation>,
        constellations: List<Constellation>,
        initialGuess: Ecef?,
    ): Fit? {
        var position = initialGuess ?: Ecef(0.0, 0.0, 0.0)
        val clocks = DoubleArray(constellations.size)
        repeat(MAX_ITERATIONS) {
            val positionKnown = position.norm() > NEAR_EARTH_SURFACE_M
            val receiver = if (positionKnown) Wgs84.toGeodetic(position) else null
            val rows =
                observations.mapNotNull { obs ->
                    row(obs, receiveTime, position, receiver, clocks[constellations.indexOf(obs.constellation)])
                }
            if (rows.size < 3 + constellations.size) return null
            val h =
                rows.map { r ->
                    DoubleArray(3 + constellations.size).also { a ->
                        a[0] = -r.los[0]
                        a[1] = -r.los[1]
                        a[2] = -r.los[2]
                        a[3 + constellations.indexOf(r.obs.constellation)] = 1.0
                    }
                }
            val residuals = DoubleArray(rows.size) { rows[it].obs.pseudorangeM - rows[it].predictedM }
            val weights = DoubleArray(rows.size) { 1 / (rows[it].sigmaM * rows[it].sigmaM) }
            val (dx, covariance) = LinearAlgebra.weightedLeastSquares(h, residuals, weights) ?: return null
            position = Ecef(position.x + dx[0], position.y + dx[1], position.z + dx[2])
            for (k in clocks.indices) clocks[k] += dx[3 + k]
            if (sqrt(dx[0] * dx[0] + dx[1] * dx[1] + dx[2] * dx[2]) < CONVERGENCE_M) {
                val clockBiases = constellations.zip(clocks.toList()).toMap()
                return converged(receiveTime, observations, position, clockBiases, covariance)
                    .takeIf { it.rows.size == rows.size }
            }
        }
        return null
    }

    /** Recomputes rows and residuals at the converged position. */
    private fun converged(
        receiveTime: Double,
        observations: List<Observation>,
        position: Ecef,
        clockBiases: Map<Constellation, Double>,
        covariance: Array<DoubleArray>,
    ): Fit {
        val receiver = Wgs84.toGeodetic(position)
        val rows =
            observations.mapNotNull { obs ->
                row(obs, receiveTime, position, receiver, clockBiases.getValue(obs.constellation))
            }
        val residuals = DoubleArray(rows.size) { rows[it].obs.pseudorangeM - rows[it].predictedM }
        return Fit(position, clockBiases, rows, covariance, residuals)
    }

    private fun row(
        obs: Observation,
        receiveTime: Double,
        receiverEcef: Ecef,
        receiver: Geodetic?,
        clockBiasM: Double,
    ): Row? {
        val eph = store.select(obs.constellation, obs.svid, receiveTime) ?: return null
        var transmitTime = receiveTime - obs.pseudorangeM / GnssTime.SPEED_OF_LIGHT_MPS
        var satellite = SatelliteOrbit.state(eph, transmitTime)
        transmitTime -= satellite.clockBias
        satellite = SatelliteOrbit.state(eph, transmitTime)

        var satPosition = satellite.position
        var range = (satPosition - receiverEcef).norm()
        repeat(2) {
            satPosition = SatelliteOrbit.rotateForTravelTime(satellite.position, range / GnssTime.SPEED_OF_LIGHT_MPS)
            range = (satPosition - receiverEcef).norm()
        }
        val diff = satPosition - receiverEcef
        val los = doubleArrayOf(diff.x / range, diff.y / range, diff.z / range)

        var elevation = Math.PI / 2
        var atmosphere = 0.0
        if (receiver != null) {
            val enu = Wgs84.toEnu(diff, receiver)
            elevation = asin(enu.up / range)
            if (elevation < ELEVATION_MASK_RAD) return null
            val azimuth = atan2(enu.east, enu.north)
            atmosphere =
                Atmosphere.ionosphereDelayM(store.ionosphere, receiver, azimuth, elevation, receiveTime) +
                Atmosphere.troposphereDelayM(elevation)
        }
        val predicted = range + clockBiasM - satellite.clockBias * GnssTime.SPEED_OF_LIGHT_MPS + atmosphere
        return Row(obs, satellite, los, elevation, predicted, obs.sigmaM / max(sin(elevation), MIN_SIN_ELEVATION))
    }

    private companion object {
        const val MAX_ITERATIONS = 20
        const val MAX_EXCLUSIONS = 3
        const val CONVERGENCE_M = 1e-3
        const val NEAR_EARTH_SURFACE_M = 6.0e6
        val ELEVATION_MASK_RAD = Math.toRadians(10.0)
        const val MIN_SIN_ELEVATION = 0.2
        const val OUTLIER_SIGMAS = 5.0
        const val OUTLIER_MIN_M = 30.0
        const val MIN_SPEED_FOR_BEARING_MPS = 0.5
        const val FULL_CIRCLE_DEG = 360.0
    }
}
