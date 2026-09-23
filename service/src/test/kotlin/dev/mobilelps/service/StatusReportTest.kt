package dev.mobilelps.service

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.Constellation
import dev.mobilelps.core.FixSource
import dev.mobilelps.core.GnssState
import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.RadioType
import dev.mobilelps.core.WifiAccessPoint
import dev.mobilelps.detector.Assessment
import dev.mobilelps.detector.Indicator
import dev.mobilelps.detector.SpoofingState
import org.junit.Test

class StatusReportTest {
    private val fix = PositionFix(50.45, 30.52, 180.0, 8.0, 12.0, 90.0, 0L, 0L, FixSource.GNSS)
    private val status =
        LpsStatus(
            running = true,
            mock = MockState.ACTIVE,
            assessment =
                Assessment(
                    SpoofingState.SPOOFED,
                    0.84,
                    mapOf(Indicator.LBS_MISMATCH to "12.0 km from cell position"),
                ),
            output = fix,
            gnss =
                GnssStats(
                    epochs = 10,
                    lastEpochElapsedNanos = 1_000_000_000L,
                    fix = fix,
                    signals =
                        listOf(
                            SignalInfo(
                                Constellation.GPS,
                                5,
                                1575.42,
                                38.5,
                                GnssState.CODE_LOCK or GnssState.TOW_DECODED,
                                true,
                                45.0,
                                -2.3,
                            ),
                        ),
                ),
            lbs =
                LbsStats(
                    cellList = listOf(CellTower(RadioType.LTE, 255, 1, 100, 200, -80, 3, true, 0)),
                    wifiList = listOf(WifiAccessPoint("00:11:22:33:44:55", "Cafe", -60, 2437, 0) to true),
                    wifiOnlyResult = "Transmitters unknown to BeaconDB",
                ),
        )

    @Test
    fun `full report lists detector, signals, cells and access points`() {
        val report = StatusReport.full(status, nowElapsedNanos = 3_000_000_000L)

        assertThat(report).contains("detector: SPOOFED score=0.84")
        assertThat(report).contains("LBS_MISMATCH: 12.0 km from cell position")
        assertThat(report).contains("lastEvent=2.0s ago")
        assertThat(report).containsMatch("GPS5 +1575.42 +38.5 +CODE\\|TOW +true +45 +-2.3")
        assertThat(report).contains("cell LTE 255-1 area=100 id=200")
        assertThat(report).contains("wifi 00:11:22:33:44:55 -60dBm 2437MHz usable=true")
        assertThat(report).contains("wifi-only lookup: Transmitters unknown to BeaconDB")
    }

    @Test
    fun `summary fits one line`() {
        val line = StatusReport.summary(status)

        assertThat(line).doesNotContain("\n")
        assertThat(line).contains("state=SPOOFED")
        assertThat(line).contains("out=50.450000,30.520000 ±8m GNSS")
    }
}
