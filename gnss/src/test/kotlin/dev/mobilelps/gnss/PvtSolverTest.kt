package dev.mobilelps.gnss

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.Constellation
import dev.mobilelps.core.Ecef
import dev.mobilelps.core.Wgs84
import org.junit.Test

class PvtSolverTest {
    private val store = Fixtures.store()
    private val time = Fixtures.EPOCH + 900
    private val biases = mapOf(Constellation.GPS to 12_345.0, Constellation.GALILEO to 12_360.0)

    @Test
    fun `recovers position and clock from error-free observations`() {
        val observations = Fixtures.observations(store, Fixtures.KYIV, time, biases)

        val solution = PvtSolver(store).solve(ObservationEpoch(time, observations))

        checkNotNull(solution)
        val error = (solution.ecef - Wgs84.toEcef(Fixtures.KYIV)).norm()
        assertThat(error).isLessThan(0.05)
        assertThat(solution.satellitesUsed).isEqualTo(observations.size)
        assertThat(solution.gpsTime).isWithin(1e-9).of(time - 12_345.0 / GnssTime.SPEED_OF_LIGHT_MPS)
    }

    @Test
    fun `absorbs a receive time error in the clock bias`() {
        // What Observations does without the receiver clock: receive time and all pseudoranges shift together.
        val shift = 0.004
        val observations =
            Fixtures.observations(store, Fixtures.KYIV, time, biases).map {
                it.copy(pseudorangeM = it.pseudorangeM + shift * GnssTime.SPEED_OF_LIGHT_MPS)
            }

        val solution = checkNotNull(PvtSolver(store).solve(ObservationEpoch(time + shift, observations)))

        assertThat((solution.ecef - Wgs84.toEcef(Fixtures.KYIV)).norm()).isLessThan(0.05)
        assertThat(solution.gpsTime).isWithin(1e-9).of(time - 12_345.0 / GnssTime.SPEED_OF_LIGHT_MPS)
    }

    @Test
    fun `excludes a faulty satellite`() {
        val observations = Fixtures.observations(store, Fixtures.KYIV, time, biases).toMutableList()
        observations[0] = observations[0].copy(pseudorangeM = observations[0].pseudorangeM + 800.0)

        val solution = checkNotNull(PvtSolver(store).solve(ObservationEpoch(time, observations)))

        assertThat((solution.ecef - Wgs84.toEcef(Fixtures.KYIV)).norm()).isLessThan(0.05)
        assertThat(solution.satellitesUsed).isEqualTo(observations.size - 1)
    }

    @Test
    fun `solves velocity from pseudorange rates`() {
        // 20 m/s due north at the receiver.
        val north = Wgs84.toEcef(Fixtures.KYIV.copy(latDeg = Fixtures.KYIV.latDeg + 1e-3)) - Wgs84.toEcef(Fixtures.KYIV)
        val scale = 20.0 / north.norm()
        val velocity = Ecef(north.x * scale, north.y * scale, north.z * scale)
        val observations = Fixtures.observations(store, Fixtures.KYIV, time, biases, velocity, clockDriftMps = 50.0)

        val solution = checkNotNull(PvtSolver(store).solve(ObservationEpoch(time, observations)))

        assertThat(solution.speedMps).isWithin(0.05).of(20.0)
        assertThat(solution.bearingDeg!!.let { if (it > 180) it - 360 else it }).isWithin(0.5).of(0.0)
    }

    @Test
    fun `returns null without enough satellites`() {
        val gpsOnly = mapOf(Constellation.GPS to 0.0)
        val observations = Fixtures.observations(store, Fixtures.KYIV, time, gpsOnly).take(3)

        assertThat(PvtSolver(store).solve(ObservationEpoch(time, observations))).isNull()
    }
}
