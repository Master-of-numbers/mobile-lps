package dev.mobilelps.gnss

import dev.mobilelps.core.HttpClient
import java.io.IOException
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * Downloads merged multi-GNSS broadcast ephemerides published by BKG (IGS data center, updated every 15 minutes).
 * Right after midnight UTC the current daily file is nearly empty, so the previous day is fetched as well.
 */
class EphemerisDownloader(
    private val http: HttpClient,
    private val baseUrl: String = "https://igs.bkg.bund.de/root_ftp/IGS/BRDC",
) {
    /** Daily file URLs needed at [now]. */
    fun urls(now: Instant): List<String> {
        val today = now.atZone(ZoneOffset.UTC)
        val days = if (today.hour < EARLY_HOURS) listOf(today.minusDays(1), today) else listOf(today)
        return days.map { day ->
            val year = day.year
            val doy = "%03d".format(day.dayOfYear)
            "$baseUrl/$year/$doy/BRDC00WRD_S_$year${doy}0000_01D_MN.rnx.gz"
        }
    }

    /** Downloads the gzip-compressed RINEX files; returns their raw bytes. */
    @Throws(IOException::class)
    fun download(now: Instant): List<ByteArray> =
        urls(now).map { url ->
            val response = http.get(url)
            if (response.code != HTTP_OK) throw IOException("HTTP ${response.code} for $url")
            response.body
        }

    companion object {
        private const val EARLY_HOURS = 4
        private const val HTTP_OK = 200

        fun parseGzip(bytes: ByteArray): EphemerisStore {
            val result = InputStreamReader(GZIPInputStream(bytes.inputStream())).use(RinexNavParser::parse)
            return EphemerisStore(result.ephemerides, result.ionosphere ?: KlobucharCoefficients.DEFAULT)
        }
    }
}
