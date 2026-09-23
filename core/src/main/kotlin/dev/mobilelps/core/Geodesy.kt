package dev.mobilelps.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Earth-centered, Earth-fixed coordinates in meters. */
data class Ecef(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun minus(other: Ecef) = Ecef(x - other.x, y - other.y, z - other.z)

    fun norm(): Double = sqrt(x * x + y * y + z * z)
}

/** Geodetic WGS84 coordinates: degrees and meters above the ellipsoid. */
data class Geodetic(
    val latDeg: Double,
    val lonDeg: Double,
    val heightM: Double = 0.0,
)

/** Local east/north/up vector in meters. */
data class Enu(
    val east: Double,
    val north: Double,
    val up: Double,
)

object Wgs84 {
    const val SEMI_MAJOR_AXIS_M = 6_378_137.0
    const val FLATTENING = 1 / 298.257223563
    private const val E2 = FLATTENING * (2 - FLATTENING)
    private const val MEAN_RADIUS_M = 6_371_008.8
    private const val GEODETIC_ITERATIONS = 5

    fun toEcef(p: Geodetic): Ecef {
        val lat = Math.toRadians(p.latDeg)
        val lon = Math.toRadians(p.lonDeg)
        val n = SEMI_MAJOR_AXIS_M / sqrt(1 - E2 * sin(lat) * sin(lat))
        return Ecef(
            x = (n + p.heightM) * cos(lat) * cos(lon),
            y = (n + p.heightM) * cos(lat) * sin(lon),
            z = (n * (1 - E2) + p.heightM) * sin(lat),
        )
    }

    fun toGeodetic(p: Ecef): Geodetic {
        val lon = atan2(p.y, p.x)
        val horizontal = hypot(p.x, p.y)
        var lat = atan2(p.z, horizontal * (1 - E2))
        var height = 0.0
        repeat(GEODETIC_ITERATIONS) {
            val n = SEMI_MAJOR_AXIS_M / sqrt(1 - E2 * sin(lat) * sin(lat))
            height = horizontal / cos(lat) - n
            lat = atan2(p.z, horizontal * (1 - E2 * n / (n + height)))
        }
        return Geodetic(Math.toDegrees(lat), Math.toDegrees(lon), height)
    }

    /** Rotates an ECEF vector into the local ENU frame at [origin]. */
    fun toEnu(
        vector: Ecef,
        origin: Geodetic,
    ): Enu {
        val lat = Math.toRadians(origin.latDeg)
        val lon = Math.toRadians(origin.lonDeg)
        return Enu(
            east = -sin(lon) * vector.x + cos(lon) * vector.y,
            north = -sin(lat) * cos(lon) * vector.x - sin(lat) * sin(lon) * vector.y + cos(lat) * vector.z,
            up = cos(lat) * cos(lon) * vector.x + cos(lat) * sin(lon) * vector.y + sin(lat) * vector.z,
        )
    }

    /** Great-circle distance in meters. */
    fun distanceM(
        aLatDeg: Double,
        aLonDeg: Double,
        bLatDeg: Double,
        bLonDeg: Double,
    ): Double {
        val dLat = Math.toRadians(bLatDeg - aLatDeg)
        val dLon = Math.toRadians(bLonDeg - aLonDeg)
        val h =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(aLatDeg)) * cos(Math.toRadians(bLatDeg)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * MEAN_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
