package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation

/** Broadcast Keplerian ephemeris (GPS LNAV or Galileo I/NAV / F/NAV) as stored in RINEX 3 navigation files. */
data class KeplerEphemeris(
    val constellation: Constellation,
    val svid: Int,
    /** Clock reference time, GPS seconds. */
    val toc: Double,
    val af0: Double,
    val af1: Double,
    val af2: Double,
    val crs: Double,
    val deltaN: Double,
    val m0: Double,
    val cuc: Double,
    val eccentricity: Double,
    val cus: Double,
    val sqrtA: Double,
    /** Ephemeris reference time, GPS seconds (week * 604800 + time of week). */
    val toe: Double,
    val cic: Double,
    val omega0: Double,
    val cis: Double,
    val i0: Double,
    val crc: Double,
    val omega: Double,
    val omegaDot: Double,
    val idot: Double,
    /** Group delay for single-frequency L1/E1 users: GPS TGD or Galileo BGD E5b/E1, seconds. */
    val groupDelay: Double,
    val health: Int,
    /** Galileo "data sources" bit field; 0 for GPS. */
    val dataSources: Int,
) {
    val isHealthy: Boolean
        get() =
            when (constellation) {
                // Galileo health bits 0..2 describe the E1-B signal.
                Constellation.GALILEO -> health and GALILEO_E1B_HEALTH_MASK == 0

                else -> health == 0
            }

    /** Galileo I/NAV records carry the E1 clock; F/NAV records are for E5a users. */
    val isGalileoInav: Boolean
        get() = dataSources and GALILEO_INAV_MASK != 0

    private companion object {
        const val GALILEO_E1B_HEALTH_MASK = 0b111
        const val GALILEO_INAV_MASK = 0b101
    }
}
