package dev.mobilelps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import dev.mobilelps.core.GnssState
import dev.mobilelps.service.LpsStatus
import java.text.DateFormat
import java.util.Date

/** Everything the pipeline knows, for diagnosing problems in the field. */
@Composable
internal fun ExtendedTab(
    status: LpsStatus,
    now: Long,
) {
    DetectorSection(status)
    Section("Published") {
        Line("Mock providers", status.mock.name + (status.mockError?.let { " ($it)" } ?: ""))
        status.output?.let { Line("Source", it.source.name) }
        FixLines(status.output)
    }
    GnssSection(status, now)
    SignalsSection(status)
    LbsSection(status)
    Section("Ephemeris") {
        Line("Records", status.ephemeris.records.toString())
        Line("Updated", status.ephemeris.updatedAtMillis?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "—")
        status.ephemeris.error?.let { Line("Problem", it) }
    }
}

@Composable
private fun DetectorSection(status: LpsStatus) {
    val assessment = status.assessment
    Section("Detector", color = stateColor(assessment?.state)) {
        Line("State", assessment?.state?.name ?: "—")
        if (assessment != null) Line("Score", "%.2f".format(assessment.score))
        assessment?.indicators?.forEach { (indicator, detail) -> Line(indicator.name, detail) }
    }
}

@Composable
private fun GnssSection(
    status: LpsStatus,
    now: Long,
) {
    Section("GNSS") {
        val gnss = status.gnss
        Line("Measurement events", gnss.epochs.toString())
        Line("Last event", age(gnss.lastEpochElapsedNanos, now))
        Line("Tracked / usable / used", "${gnss.tracked} / ${gnss.usable} / ${gnss.satellitesUsed}")
        gnss.receiverClock?.let { Line("Time source", if (it) "receiver clock" else "satellites") }
        gnss.residualRmsM?.let { Line("Residual RMS", "%.1f m".format(it)) }
        gnss.timeOffsetS?.let { Line("Time offset", "%.3f s".format(it)) }
        gnss.error?.let { Line("Problem", it) }
        FixLines(gnss.fix)
    }
}

@Composable
private fun SignalsSection(status: LpsStatus) {
    val signals = status.gnss.signals
    if (signals.isEmpty()) return
    Section("Signals") {
        Mono("sat     MHz      C/N0 state      use elev  res")
        signals.sortedWith(compareBy({ it.constellation }, { it.svid }, { it.carrierMhz })).forEach { s ->
            Mono(
                "%-7s %-8s %4.0f %-10s %-3s %4s %5s".format(
                    "${s.constellation.name.take(CONSTELLATION_ABBREVIATION)}${s.svid}",
                    s.carrierMhz?.let { "%.2f".format(it) } ?: "-",
                    s.cn0DbHz,
                    stateFlags(s.state),
                    if (s.usable) "yes" else "no",
                    s.elevationDeg?.let { "%.0f".format(it) } ?: "-",
                    s.residualM?.let { "%.1f".format(it) } ?: "-",
                ),
            )
        }
    }
}

@Composable
private fun LbsSection(status: LpsStatus) {
    val lbs = status.lbs
    Section("Cells and Wi-Fi (LBS)") {
        Line("Cells identified / visible", "${lbs.cells} / ${lbs.visibleCells}")
        Line("Wi-Fi usable / seen", "${lbs.wifiUsable} / ${lbs.wifiSeen}")
        Text("Last lookup: ${lbs.lastResult ?: "—"}")
        lbs.wifiOnlyResult?.let { Text("Wi-Fi only: $it") }
        FixLines(lbs.fix)
        lbs.cellList.forEach { c ->
            val serving = if (c.isServing) " serving" else ""
            Mono("${c.radio} ${c.mcc}-${c.mnc} ${c.areaCode}/${c.cellId} ${c.signalDbm ?: "?"} dBm$serving")
        }
        lbs.wifiList.sortedByDescending { it.first.signalDbm }.forEach { (ap, usable) ->
            Mono("${ap.bssid} ${ap.signalDbm} dBm${if (usable) "" else " (skipped)"} ${ap.ssid.orEmpty()}")
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

private fun stateFlags(state: Int): String =
    buildList {
        if (state and GnssState.CODE_LOCK != 0) add("CODE")
        if (state and (GnssState.TOW_DECODED or GnssState.TOW_KNOWN) != 0) add("TOW")
        if (state and GnssState.MSEC_AMBIGUOUS != 0) add("AMB")
    }.joinToString("|")

private const val CONSTELLATION_ABBREVIATION = 3
