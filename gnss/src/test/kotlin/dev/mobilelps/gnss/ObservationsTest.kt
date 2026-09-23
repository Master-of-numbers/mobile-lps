package dev.mobilelps.gnss

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.Constellation
import dev.mobilelps.core.GnssClockData
import dev.mobilelps.core.GnssEpoch
import dev.mobilelps.core.GnssMeasurementData
import dev.mobilelps.core.GnssState
import org.junit.Test

class ObservationsTest {
    private val week = 2437L
    private val receiveNanos = week * GnssTime.NANOS_PER_WEEK + 266_400L * GnssTime.NANOS_PER_SECOND + 70_000_000L
    private val timeNanos = 1_000_000_000_000L
    private val clock =
        GnssClockData(
            timeNanos = timeNanos,
            fullBiasNanos = timeNanos - receiveNanos,
            biasNanos = 0.0,
            hardwareClockDiscontinuityCount = 0,
            elapsedRealtimeNanos = null,
        )

    private fun measurement(
        constellation: Constellation = Constellation.GPS,
        state: Int = GnssState.CODE_LOCK or GnssState.TOW_DECODED,
        frequency: Double? = 1_575.42e6,
    ) = GnssMeasurementData(
        constellation = constellation,
        svid = 5,
        state = state,
        receivedSvTimeNanos = (266_400L * GnssTime.NANOS_PER_SECOND + 70_000_000L) - 75_000_000L,
        receivedSvTimeUncertaintyNanos = 20,
        timeOffsetNanos = 0.0,
        cn0DbHz = 40.0,
        pseudorangeRateMps = 100.0,
        pseudorangeRateUncertaintyMps = 0.1,
        carrierFrequencyHz = frequency,
    )

    /** System clock 300 ms off: only used to pick the GPS week. */
    private val approx = receiveNanos / 1e9 + 0.3

    private fun epoch(vararg m: GnssMeasurementData) = GnssEpoch(clock, m.toList(), null, 0L, 0L)

    @Test
    fun `computes pseudorange from receive and transmit time`() {
        val result = Observations.from(epoch(measurement()), approx)

        assertThat(result.observations.single().pseudorangeM).isWithin(1e-3).of(0.075 * GnssTime.SPEED_OF_LIGHT_MPS)
        assertThat(result.receiveTime).isWithin(1e-6).of(receiveNanos / 1e9)
    }

    @Test
    fun `skips measurements without a known time of week, other bands and constellations`() {
        val result =
            Observations.from(
                epoch(
                    measurement(state = GnssState.CODE_LOCK),
                    measurement(frequency = 1_176.45e6),
                    measurement(constellation = Constellation.GLONASS),
                ),
                approx,
            )

        assertThat(result.observations).isEmpty()
    }

    @Test
    fun `without the full clock bias derives receive time from satellites`() {
        val near = measurement()
        val far = near.copy(svid = 7, receivedSvTimeNanos = near.receivedSvTimeNanos - 10_000_000L)
        val noBias = GnssEpoch(clock.copy(fullBiasNanos = null), listOf(near, far), null, 0L, 0L)

        val result = Observations.from(noBias, approx)

        assertThat(result.receiverClock).isFalse()
        val (a, b) = result.observations
        // The anchor is 68 ms after the latest transmission; the 10 ms difference between satellites is kept.
        assertThat(a.pseudorangeM).isWithin(1e-3).of(0.068 * GnssTime.SPEED_OF_LIGHT_MPS)
        assertThat(b.pseudorangeM - a.pseudorangeM).isWithin(1e-3).of(0.010 * GnssTime.SPEED_OF_LIGHT_MPS)
        assertThat(result.receiveTime).isWithin(1e-6).of((receiveNanos - 75_000_000L + 68_000_000L) / 1e9)
    }
}
