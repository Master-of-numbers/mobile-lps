package dev.mobilelps.detector

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.FixSource
import dev.mobilelps.core.PositionFix
import org.junit.Test

class SpoofingDetectorTest {
    private val second = 1_000_000_000L
    private val kyivLbs = LbsEvidence(50.45, 30.52, 1_500.0, 0L)
    private val detector = SpoofingDetector()
    private var t = 0L

    private fun fix(
        lat: Double = 50.451,
        lon: Double = 30.521,
    ) = PositionFix(lat, lon, 180.0, 8.0, 0.0, null, 0L, t, FixSource.GNSS)

    private fun genuine(
        fix: PositionFix? = fix(),
        cn0: List<Double> = listOf(28.0, 33.0, 36.0, 40.0, 42.0, 45.0, 38.0),
    ) = GnssEvidence(fix, 6.0, 0.1, cn0, -2.0, 0, 7)

    private fun step(
        gnss: GnssEvidence?,
        lbs: LbsEvidence? = kyivLbs.copy(elapsedRealtimeNanos = t),
    ): Assessment {
        val result = detector.update(DetectorInput(t, gnss, lbs))
        t += second
        return result
    }

    @Test
    fun `genuine signal is clean`() {
        val result = step(genuine())

        assertThat(result.state).isEqualTo(SpoofingState.CLEAN)
        assertThat(result.indicators).isEmpty()
    }

    @Test
    fun `position far from cells latches spoofing after two epochs`() {
        val moscow = genuine(fix(55.75, 37.62))

        assertThat(step(moscow).state).isEqualTo(SpoofingState.SUSPECT)
        val second = step(moscow)

        assertThat(second.state).isEqualTo(SpoofingState.SPOOFED)
        assertThat(second.indicators).containsKey(Indicator.LBS_MISMATCH)
    }

    @Test
    fun `satellites below the horizon latch immediately`() {
        val result = step(genuine().copy(satellitesBelowHorizon = 4, satellitesChecked = 8))

        assertThat(result.state).isEqualTo(SpoofingState.SPOOFED)
    }

    @Test
    fun `spoofing is released only after sustained clean epochs`() {
        repeat(2) { step(genuine(fix(55.75, 37.62))) }

        repeat(29) { assertThat(step(genuine()).state).isEqualTo(SpoofingState.SPOOFED) }

        assertThat(step(genuine()).state).isEqualTo(SpoofingState.CLEAN)
    }

    @Test
    fun `time offset and position jump combine into spoofing`() {
        step(genuine())
        val jumped = genuine(fix(50.60, 30.52)).copy(timeOffsetS = 30.0)

        val result = step(jumped, lbs = null)

        assertThat(result.indicators.keys).containsExactly(Indicator.POSITION_JUMP, Indicator.TIME_OFFSET)
        assertThat(result.state).isEqualTo(SpoofingState.SPOOFED)
    }

    @Test
    fun `uniform strong C N0 is suspicious but not conclusive`() {
        val result = step(genuine(cn0 = List(8) { 50.0 }))

        assertThat(result.indicators.keys).containsExactly(Indicator.CN0_UNIFORM, Indicator.CN0_HIGH)
        assertThat(result.state).isEqualTo(SpoofingState.SUSPECT)
    }

    @Test
    fun `weak uniform C N0 indoors is not suspicious`() {
        val result = step(genuine(cn0 = List(8) { 20.0 }))

        assertThat(result.indicators).isEmpty()
    }

    @Test
    fun `agc drop is compared with the learned baseline`() {
        repeat(30) { step(genuine()) }

        val result = step(genuine().copy(agcDb = -10.0))

        assertThat(result.indicators).containsKey(Indicator.AGC_DROP)
    }

    @Test
    fun `reports no gnss after timeout`() {
        step(genuine())
        repeat(5) { step(null) }

        assertThat(step(null).state).isEqualTo(SpoofingState.NO_GNSS)
    }

    @Test
    fun `stale lbs position is ignored`() {
        val stale = kyivLbs.copy(elapsedRealtimeNanos = -1_000 * second)

        val result = step(genuine(fix(55.75, 37.62)), lbs = stale)

        assertThat(result.indicators).doesNotContainKey(Indicator.LBS_MISMATCH)
    }
}
