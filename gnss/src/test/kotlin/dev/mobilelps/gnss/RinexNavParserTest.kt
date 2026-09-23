package dev.mobilelps.gnss

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.Constellation
import org.junit.Test

class RinexNavParserTest {
    @Test
    fun `parses GPS and Galileo records`() {
        val result = Fixtures.rinex()

        assertThat(result.ephemerides.count { it.constellation == Constellation.GPS }).isEqualTo(32)
        assertThat(result.ephemerides.count { it.constellation == Constellation.GALILEO }).isEqualTo(17)
    }

    @Test
    fun `reads fixed width fields that touch each other`() {
        val g27 = Fixtures.rinex().ephemerides.single { it.constellation == Constellation.GPS && it.svid == 27 }

        assertThat(g27.toc).isEqualTo(Fixtures.EPOCH)
        assertThat(g27.af0).isWithin(1e-20).of(3.902241587639e-07)
        assertThat(g27.crs).isWithin(1e-12).of(-13.25)
        assertThat(g27.sqrtA).isWithin(1e-9).of(5153.669952393)
        assertThat(g27.toe).isWithin(1e-6).of(2437 * 604_800.0 + 266_400.0)
        assertThat(g27.groupDelay).isWithin(1e-20).of(2.328306436539e-09)
        assertThat(g27.isHealthy).isTrue()
    }

    @Test
    fun `galileo records keep the E5b-E1 group delay and I-NAV source`() {
        val galileo = Fixtures.rinex().ephemerides.filter { it.constellation == Constellation.GALILEO }

        assertThat(galileo.all { it.isGalileoInav }).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects input without a header`() {
        RinexNavParser.parse("garbage".reader())
    }
}
