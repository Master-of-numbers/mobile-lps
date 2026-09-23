package dev.mobilelps.lbs

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.RadioType
import dev.mobilelps.core.WifiAccessPoint
import org.junit.Test

class CachingLocatorTest {
    private val cell = CellTower(RadioType.GSM, 255, 3, 100, 200, -70, null, true, null)
    private val ap = WifiAccessPoint("00:11:22:33:44:55", null, -60, 2437, null)
    private val found = LbsResult.Found(LbsLocation(1.0, 2.0, 3.0))
    private var now = 0L
    private var calls = 0

    @Test
    fun `reuses results for the same transmitters until they expire`() {
        val locator =
            CachingLocator({
                calls++
                found
            }, { now }, ttlMillis = 1000)

        locator.locate(NetworkQuery(listOf(cell), listOf(ap)))
        locator.locate(NetworkQuery(listOf(cell.copy(signalDbm = -80)), listOf(ap.copy(signalDbm = -70))))
        now = 2000
        locator.locate(NetworkQuery(listOf(cell), listOf(ap)))

        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a different access point set is a new query`() {
        val locator =
            CachingLocator({
                calls++
                found
            }, { now })

        locator.locate(NetworkQuery(listOf(cell), listOf(ap)))
        locator.locate(NetworkQuery(listOf(cell), emptyList()))

        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `does not cache failures`() {
        val locator =
            CachingLocator({
                calls++
                LbsResult.Failed("x")
            }, { now })

        locator.locate(NetworkQuery(listOf(cell), emptyList()))
        locator.locate(NetworkQuery(listOf(cell), emptyList()))

        assertThat(calls).isEqualTo(2)
    }
}
