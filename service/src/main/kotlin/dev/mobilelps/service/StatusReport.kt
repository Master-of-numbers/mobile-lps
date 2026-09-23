package dev.mobilelps.service

import dev.mobilelps.core.GnssState
import dev.mobilelps.core.PositionFix
import java.util.Locale

/** Plain-text renderings of [LpsStatus] for `dumpsys` and logcat in debuggable builds. */
object StatusReport {
    private const val NANOS_PER_SECOND = 1e9
    private const val CONSTELLATION_ABBREVIATION = 3

    /** One line for logcat. */
    fun summary(status: LpsStatus): String {
        val a = status.assessment
        return buildString {
            append("state=${a?.state} score=${a?.score?.let { "%.2f".format(Locale.US, it) }}")
            if (!a?.indicators.isNullOrEmpty()) append(" indicators=${a.indicators.keys.joinToString(",")}")
            append(" out=${status.output?.let(::fix)}")
            append(
                " gnss=${status.gnss.fix?.let(
                    ::fix,
                )} used=${status.gnss.satellitesUsed}/${status.gnss.usable}/${status.gnss.tracked}",
            )
            append(" lbs=${status.lbs.fix?.let(::fix)} cells=${status.lbs.cells}/${status.lbs.visibleCells}")
            append(" wifi=${status.lbs.wifiUsable}/${status.lbs.wifiSeen} mock=${status.mock}")
        }
    }

    /** Full multi-line report. */
    fun full(
        status: LpsStatus,
        nowElapsedNanos: Long,
    ): String =
        buildString {
            appendLine("running=${status.running} mock=${status.mock}${status.mockError?.let { " ($it)" } ?: ""}")
            appendLine("output: ${status.output?.let(::fix) ?: "none"}")
            status.assessment?.let { a ->
                appendLine("detector: ${a.state} score=%.2f".format(Locale.US, a.score))
                a.indicators.forEach { (indicator, detail) -> appendLine("  $indicator: $detail") }
            }
            gnss(status.gnss, nowElapsedNanos)
            lbs(status.lbs)
            val e = status.ephemeris
            appendLine("ephemeris: records=${e.records} updatedAtMillis=${e.updatedAtMillis} error=${e.error}")
        }

    private fun StringBuilder.gnss(
        g: GnssStats,
        now: Long,
    ) {
        val age = g.lastEpochElapsedNanos?.let { "%.1fs".format(Locale.US, (now - it) / NANOS_PER_SECOND) }
        appendLine("gnss: events=${g.epochs} lastEvent=$age ago receiverClock=${g.receiverClock} error=${g.error}")
        appendLine("  tracked=${g.tracked} usable=${g.usable} used=${g.satellitesUsed}")
        appendLine("  residualRms=${g.residualRmsM?.fmt()}m timeOffset=${g.timeOffsetS?.fmt()}s")
        appendLine("  fix: ${g.fix?.let(::fix)}")
        appendLine("  sat      MHz     C/N0  state             usable  elev   residual")
        g.signals.sortedWith(compareBy({ it.constellation }, { it.svid }, { it.carrierMhz })).forEach { s ->
            appendLine(
                "  %-8s %-7s %5.1f  %-16s  %-6s  %-5s  %s".format(
                    Locale.US,
                    "${s.constellation.name.take(CONSTELLATION_ABBREVIATION)}${s.svid}",
                    s.carrierMhz?.let { "%.2f".format(Locale.US, it) } ?: "-",
                    s.cn0DbHz,
                    stateFlags(s.state),
                    s.usable,
                    s.elevationDeg?.let { "%.0f".format(Locale.US, it) } ?: "-",
                    s.residualM?.fmt() ?: "-",
                ),
            )
        }
    }

    private fun StringBuilder.lbs(l: LbsStats) {
        appendLine(
            "lbs: cells=${l.cells}/${l.visibleCells} wifi=${l.wifiUsable}/${l.wifiSeen} throttled=${l.wifiThrottled}",
        )
        appendLine("  last lookup: ${l.lastResult}")
        appendLine("  wifi-only lookup: ${l.wifiOnlyResult}")
        appendLine("  fix: ${l.fix?.let(::fix)}")
        l.cellList.forEach { c ->
            appendLine(
                "  cell ${c.radio} ${c.mcc}-${c.mnc} area=${c.areaCode} id=${c.cellId} dbm=${c.signalDbm} " +
                    "ta=${c.timingAdvance} serving=${c.isServing} age=${c.ageMillis}ms",
            )
        }
        l.wifiList.sortedByDescending { it.first.signalDbm }.forEach { (ap, usable) ->
            appendLine("  wifi ${ap.bssid} ${ap.signalDbm}dBm ${ap.frequencyMhz}MHz usable=$usable ssid=${ap.ssid}")
        }
    }

    private fun stateFlags(state: Int): String =
        buildList {
            if (state and GnssState.CODE_LOCK != 0) add("CODE")
            if (state and GnssState.TOW_DECODED != 0) add("TOW")
            if (state and GnssState.TOW_KNOWN != 0) add("TOWK")
            if (state and GnssState.MSEC_AMBIGUOUS != 0) add("AMB")
        }.joinToString("|").ifEmpty { "0x%x".format(state) }

    private fun fix(f: PositionFix): String =
        "%.6f,%.6f ±%.0fm %s".format(Locale.US, f.latDeg, f.lonDeg, f.horizontalAccuracyM, f.source) +
            (f.speedMps?.let { " %.1fm/s".format(Locale.US, it) } ?: "")

    private fun Double.fmt(): String = "%.1f".format(Locale.US, this)
}
