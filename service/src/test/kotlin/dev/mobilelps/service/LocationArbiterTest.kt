package dev.mobilelps.service

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.FixSource
import dev.mobilelps.core.PositionFix
import dev.mobilelps.detector.SpoofingState
import org.junit.Test

class LocationArbiterTest {
    private val second = 1_000_000_000L
    private val now = 1_000 * second
    private val gnss = PositionFix(50.0, 30.0, 100.0, 5.0, 10.0, 90.0, 0L, now - second, FixSource.GNSS)
    private val lbs = PositionFix(50.01, 30.01, null, 1500.0, null, null, 0L, now - 30 * second, FixSource.LBS)

    @Test
    fun `publishes gnss while it is trusted`() {
        assertThat(LocationArbiter.choose(SpoofingState.CLEAN, gnss, lbs, now, 42L)).isEqualTo(gnss)
        assertThat(LocationArbiter.choose(SpoofingState.SUSPECT, gnss, lbs, now, 42L)).isEqualTo(gnss)
    }

    @Test
    fun `falls back to a re-stamped network position when spoofed gnss is elsewhere`() {
        val far = gnss.copy(latDeg = 55.75, lonDeg = 37.62)

        val result = LocationArbiter.choose(SpoofingState.SPOOFED, far, lbs, now, 42L)

        assertThat(result).isEqualTo(lbs.copy(timeMillis = 42L, elapsedRealtimeNanos = now))
    }

    @Test
    fun `uses spoofed gnss when it lies inside the network accuracy circle`() {
        // About 1.3 km from the network position, whose accuracy is 1.5 km.
        assertThat(LocationArbiter.choose(SpoofingState.SPOOFED, gnss, lbs, now, 42L)).isEqualTo(gnss)
    }

    @Test
    fun `does not use spoofed gnss just outside the circle or when it is less precise`() {
        val outside = gnss.copy(latDeg = 50.03)
        val imprecise = gnss.copy(horizontalAccuracyM = 2_000.0)

        assertThat(
            LocationArbiter.choose(SpoofingState.SPOOFED, outside, lbs, now, 42L)?.source,
        ).isEqualTo(FixSource.LBS)
        assertThat(
            LocationArbiter.choose(SpoofingState.SPOOFED, imprecise, lbs, now, 42L)?.source,
        ).isEqualTo(FixSource.LBS)
    }

    @Test
    fun `spoofed gnss without a network position is not published`() {
        assertThat(LocationArbiter.choose(SpoofingState.SPOOFED, gnss, null, now, 42L)).isNull()
    }

    @Test
    fun `falls back when the gnss fix is stale`() {
        val stale = gnss.copy(elapsedRealtimeNanos = now - 10 * second)

        assertThat(LocationArbiter.choose(SpoofingState.CLEAN, stale, lbs, now, 42L)?.source).isEqualTo(FixSource.LBS)
    }

    @Test
    fun `publishes nothing without a fresh position`() {
        val oldLbs = lbs.copy(elapsedRealtimeNanos = now - 600 * second)

        assertThat(LocationArbiter.choose(SpoofingState.NO_GNSS, null, oldLbs, now, 42L)).isNull()
    }
}
