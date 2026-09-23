package dev.mobilelps.lbs

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.WifiAccessPoint
import org.junit.Test

class AccessPointFilterTest {
    private fun ap(
        bssid: String,
        ssid: String? = "Cafe",
        signal: Int = -60,
    ) = WifiAccessPoint(bssid, ssid, signal, 2437, null)

    @Test
    fun `drops opted-out and randomized access points`() {
        val result =
            AccessPointFilter.usable(
                listOf(
                    ap("00:11:22:33:44:01"),
                    ap("00:11:22:33:44:02", ssid = "Home_nomap"),
                    ap("00:11:22:33:44:03", ssid = "Office_OptOut"),
                    // 0x02 bit set in the first octet: locally administered (random) MAC.
                    ap("02:11:22:33:44:04"),
                    ap("da:a1:19:00:00:05"),
                    ap("00:11:22:33:44:06"),
                ),
            )

        assertThat(result.map { it.bssid }).containsExactly("00:11:22:33:44:01", "00:11:22:33:44:06")
    }

    @Test
    fun `a single access point is not enough`() {
        assertThat(AccessPointFilter.usable(listOf(ap("00:11:22:33:44:01")))).isEmpty()
    }

    @Test
    fun `keeps the strongest twenty`() {
        val many = (0 until 30).map { ap("00:11:22:33:44:%02x".format(it), signal = -90 + it) }

        val result = AccessPointFilter.usable(many)

        assertThat(result).hasSize(20)
        assertThat(result.first().signalDbm).isEqualTo(-61)
    }
}
