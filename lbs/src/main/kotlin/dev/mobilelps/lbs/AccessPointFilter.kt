package dev.mobilelps.lbs

import dev.mobilelps.core.WifiAccessPoint

/** Selects access points that are stationary and whose owners allow geolocation. */
object AccessPointFilter {
    /** Geolocation services require at least two access points so that a single one cannot be tracked. */
    const val MIN_ACCESS_POINTS = 2
    private const val MAX_ACCESS_POINTS = 20
    private const val LOCALLY_ADMINISTERED_BIT = 0x02
    private val OPT_OUT_SUFFIXES = listOf("_nomap", "_optout")

    fun usable(accessPoints: List<WifiAccessPoint>): List<WifiAccessPoint> {
        val usable =
            accessPoints
                .filterNot { ap -> OPT_OUT_SUFFIXES.any { ap.ssid?.endsWith(it, ignoreCase = true) == true } }
                .filterNot { isLocallyAdministered(it.bssid) }
                .distinctBy { it.bssid }
                .sortedByDescending { it.signalDbm }
                .take(MAX_ACCESS_POINTS)
        return if (usable.size >= MIN_ACCESS_POINTS) usable else emptyList()
    }

    /** Randomized MACs (phones, mobile hotspots) move with their owners and only spoil the position. */
    private fun isLocallyAdministered(bssid: String): Boolean {
        val firstOctet = bssid.substringBefore(':').toIntOrNull(radix = 16) ?: return true
        return firstOctet and LOCALLY_ADMINISTERED_BIT != 0
    }
}
