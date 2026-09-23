package dev.mobilelps.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeodesyTest {
    @Test
    fun `ecef round trip keeps coordinates`() {
        val kyiv = Geodetic(50.4501, 30.5234, 179.0)

        val back = Wgs84.toGeodetic(Wgs84.toEcef(kyiv))

        assertThat(back.latDeg).isWithin(1e-9).of(kyiv.latDeg)
        assertThat(back.lonDeg).isWithin(1e-9).of(kyiv.lonDeg)
        assertThat(back.heightM).isWithin(1e-3).of(kyiv.heightM)
    }

    @Test
    fun `enu of a point straight above is pure up`() {
        val origin = Geodetic(48.0, 35.0, 0.0)
        val above = Wgs84.toEcef(origin.copy(heightM = 1000.0))

        val enu = Wgs84.toEnu(above - Wgs84.toEcef(origin), origin)

        assertThat(enu.east).isWithin(1e-6).of(0.0)
        assertThat(enu.north).isWithin(1e-6).of(0.0)
        assertThat(enu.up).isWithin(1e-6).of(1000.0)
    }

    @Test
    fun `distance between Kyiv and Lviv is about 470 km`() {
        val d = Wgs84.distanceM(50.4501, 30.5234, 49.8397, 24.0297)

        assertThat(d).isWithin(5_000.0).of(469_000.0)
    }
}
