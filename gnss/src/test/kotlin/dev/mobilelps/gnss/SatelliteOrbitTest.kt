package dev.mobilelps.gnss

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class SatelliteOrbitTest {
    @Test
    fun `orbits have the nominal radius of each constellation`() {
        for (eph in Fixtures.rinex().ephemerides) {
            val state = SatelliteOrbit.state(eph, Fixtures.EPOCH + 600)
            // Radius stays between perigee and apogee (Galileo E14/E18 fly on eccentric orbits, e = 0.16).
            val a = eph.sqrtA * eph.sqrtA
            val margin = 2e3

            assertThat(state.position.norm()).isIn(
                Range.closed(
                    a * (1 - eph.eccentricity) - margin,
                    a * (1 + eph.eccentricity) + margin,
                ),
            )
            assertThat(state.velocity.norm()).isIn(Range.closed(1_000.0, 4_500.0))
            // Broadcast clock offsets stay within a few milliseconds.
            assertThat(abs(state.clockBias)).isLessThan(1e-2)
        }
    }

    @Test
    fun `velocity matches the change of position`() {
        val eph = Fixtures.rinex().ephemerides.first()
        val t = Fixtures.EPOCH + 1_000
        val a = SatelliteOrbit.state(eph, t)
        val b = SatelliteOrbit.state(eph, t + 1)

        val displacement = (b.position - a.position).norm()

        assertThat(displacement).isWithin(1.0).of(a.velocity.norm())
    }
}
