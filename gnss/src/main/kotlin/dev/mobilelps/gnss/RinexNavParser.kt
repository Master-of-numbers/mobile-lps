package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import java.io.BufferedReader
import java.io.Reader

/**
 * Parses GPS and Galileo records of a RINEX 3.x navigation file. Records of other systems are skipped.
 *
 * Records are identified by a non-blank first column; continuation lines start with spaces. Values are
 * fixed-width (19 characters) and may touch each other without separating spaces.
 */
object RinexNavParser {
    private const val HEADER_END = "END OF HEADER"
    private const val HEADER_LABEL_COLUMN = 60
    private const val FIELD_WIDTH = 19
    private const val FIRST_LINE_VALUES_COLUMN = 23
    private const val CONTINUATION_VALUES_COLUMN = 4
    private const val ORBIT_LINES = 7

    data class Result(
        val ephemerides: List<KeplerEphemeris>,
        val ionosphere: KlobucharCoefficients?,
    )

    fun parse(reader: Reader): Result {
        val lines = BufferedReader(reader).readLines()
        val headerEnd = lines.indexOfFirst { it.length > HEADER_LABEL_COLUMN && HEADER_END in it }
        require(headerEnd >= 0) { "Not a RINEX file: no END OF HEADER" }

        val ionosphere = parseKlobuchar(lines.subList(0, headerEnd))
        val ephemerides = mutableListOf<KeplerEphemeris>()
        var i = headerEnd + 1
        while (i < lines.size) {
            val start = i
            i++
            while (i < lines.size && lines[i].startsWith(" ")) i++
            val record = lines.subList(start, i)
            val system = record.first().firstOrNull()
            if ((system == 'G' || system == 'E') && record.size > ORBIT_LINES) {
                runCatching { parseKepler(record) }.getOrNull()?.let(ephemerides::add)
            }
        }
        return Result(ephemerides, ionosphere)
    }

    private fun parseKepler(record: List<String>): KeplerEphemeris {
        val first = record[0]
        val constellation = if (first[0] == 'G') Constellation.GPS else Constellation.GALILEO
        val epoch = first.substring(3, FIRST_LINE_VALUES_COLUMN).trim().split(Regex("\\s+"))
        val toc =
            GnssTime.fromCalendar(
                year = epoch[0].toInt(),
                month = epoch[1].toInt(),
                day = epoch[2].toInt(),
                hour = epoch[3].toInt(),
                minute = epoch[4].toInt(),
                second = epoch[5].toDouble(),
            )
        val clock = fields(first, FIRST_LINE_VALUES_COLUMN, 3)
        // o[n][k]: broadcast orbit line n (1-based as in the RINEX spec), field k.
        val o = listOf(emptyList<Double>()) + (1..ORBIT_LINES).map { fields(record[it], CONTINUATION_VALUES_COLUMN, 4) }
        val week = o[5][2]
        val isGalileo = constellation == Constellation.GALILEO
        return KeplerEphemeris(
            constellation = constellation,
            svid = first.substring(1, 3).trim().toInt(),
            toc = toc,
            af0 = clock[0],
            af1 = clock[1],
            af2 = clock[2],
            crs = o[1][1],
            deltaN = o[1][2],
            m0 = o[1][3],
            cuc = o[2][0],
            eccentricity = o[2][1],
            cus = o[2][2],
            sqrtA = o[2][3],
            toe = week * GnssTime.SECONDS_PER_WEEK + o[3][0],
            cic = o[3][1],
            omega0 = o[3][2],
            cis = o[3][3],
            i0 = o[4][0],
            crc = o[4][1],
            omega = o[4][2],
            omegaDot = o[4][3],
            idot = o[5][0],
            groupDelay = if (isGalileo) o[6][3] else o[6][2],
            health = o[6][1].toInt(),
            dataSources = if (isGalileo) o[5][1].toInt() else 0,
        )
    }

    private fun fields(
        line: String,
        startColumn: Int,
        count: Int,
    ): List<Double> =
        (0 until count).map { k ->
            val from = startColumn + k * FIELD_WIDTH
            val raw = if (from < line.length) line.substring(from, minOf(from + FIELD_WIDTH, line.length)) else ""
            raw
                .trim()
                .replace('D', 'E')
                .replace('d', 'e')
                .ifEmpty { "0" }
                .toDouble()
        }

    private fun parseKlobuchar(header: List<String>): KlobucharCoefficients? {
        fun values(tag: String): List<Double>? =
            header
                .firstOrNull { it.startsWith(tag) && "IONOSPHERIC CORR" in it }
                ?.let { line ->
                    (0 until 4).map { k ->
                        line
                            .substring(5 + k * 12, 17 + k * 12)
                            .trim()
                            .replace('D', 'E')
                            .toDouble()
                    }
                }
        val alpha = values("GPSA") ?: return null
        val beta = values("GPSB") ?: return null
        return KlobucharCoefficients(alpha, beta)
    }
}
