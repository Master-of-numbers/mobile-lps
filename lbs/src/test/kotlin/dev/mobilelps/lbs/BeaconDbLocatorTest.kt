package dev.mobilelps.lbs

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.HttpClient
import dev.mobilelps.core.HttpResponse
import dev.mobilelps.core.RadioType
import dev.mobilelps.core.WifiAccessPoint
import org.junit.Test
import java.io.IOException

class BeaconDbLocatorTest {
    private val cell =
        CellTower(
            radio = RadioType.LTE,
            mcc = 255,
            mnc = 1,
            areaCode = 20310,
            cellId = 12_345_678,
            signalDbm = -90,
            timingAdvance = null,
            isServing = true,
            ageMillis = 500,
        )
    private val ap = WifiAccessPoint("00:11:22:33:44:55", "Home", -60, 2437, 1000)

    private class FakeHttp(
        private val response: () -> HttpResponse,
    ) : HttpClient {
        var lastBody: String? = null

        override fun get(url: String) = error("unused")

        override fun postJson(
            url: String,
            json: String,
        ): HttpResponse {
            lastBody = json
            return response()
        }
    }

    private fun cellsOnly() = NetworkQuery(listOf(cell), emptyList())

    @Test
    fun `sends cell towers without IP fallback`() {
        val http = FakeHttp { HttpResponse(404, ByteArray(0)) }

        BeaconDbLocator(http).locate(cellsOnly())

        assertThat(http.lastBody).isEqualTo(
            """{"considerIp":false,"cellTowers":[{"radioType":"lte","mobileCountryCode":255,"mobileNetworkCode":1,""" +
                """"locationAreaCode":20310,"cellId":12345678,"signalStrength":-90,"age":500,"serving":1}]}""",
        )
    }

    @Test
    fun `sends access points without ssid`() {
        val http = FakeHttp { HttpResponse(404, ByteArray(0)) }

        BeaconDbLocator(http).locate(NetworkQuery(emptyList(), listOf(ap, ap.copy(bssid = "00:11:22:33:44:66"))))

        assertThat(http.lastBody).isEqualTo(
            """{"considerIp":false,"wifiAccessPoints":[""" +
                """{"macAddress":"00:11:22:33:44:55","signalStrength":-60,"frequency":2437,"age":1000},""" +
                """{"macAddress":"00:11:22:33:44:66","signalStrength":-60,"frequency":2437,"age":1000}]}""",
        )
    }

    @Test
    fun `parses a found location`() {
        val http =
            FakeHttp { HttpResponse(200, """{"location":{"lat":50.45,"lng":30.52},"accuracy":1200.0}""".toByteArray()) }

        val result = BeaconDbLocator(http).locate(cellsOnly())

        assertThat(result).isEqualTo(LbsResult.Found(LbsLocation(50.45, 30.52, 1200.0)))
    }

    @Test
    fun `maps 404 to not found and errors to failures`() {
        assertThat(BeaconDbLocator(FakeHttp { HttpResponse(404, ByteArray(0)) }).locate(cellsOnly()))
            .isEqualTo(LbsResult.NotFound)
        assertThat(BeaconDbLocator(FakeHttp { HttpResponse(500, ByteArray(0)) }).locate(cellsOnly()))
            .isInstanceOf(LbsResult.Failed::class.java)
        assertThat(BeaconDbLocator(FakeHttp { throw IOException("offline") }).locate(cellsOnly()))
            .isEqualTo(LbsResult.Failed("offline"))
    }

    @Test
    fun `does not query without transmitters`() {
        val http = FakeHttp { error("must not be called") }

        assertThat(BeaconDbLocator(http).locate(NetworkQuery(emptyList(), emptyList()))).isEqualTo(LbsResult.NotFound)
    }
}
