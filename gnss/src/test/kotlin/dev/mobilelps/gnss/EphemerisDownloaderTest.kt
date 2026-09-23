package dev.mobilelps.gnss

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.HttpClient
import dev.mobilelps.core.HttpResponse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

class EphemerisDownloaderTest {
    private val noHttp =
        object : HttpClient {
            override fun get(url: String) = error("unused")

            override fun postJson(
                url: String,
                json: String,
            ) = error("unused")
        }

    @Test
    fun `uses the current day file`() {
        val urls = EphemerisDownloader(noHttp, "https://x").urls(Instant.parse("2026-09-23T14:00:00Z"))

        assertThat(urls).containsExactly("https://x/2026/266/BRDC00WRD_S_20262660000_01D_MN.rnx.gz")
    }

    @Test
    fun `adds the previous day right after midnight`() {
        val urls = EphemerisDownloader(noHttp, "https://x").urls(Instant.parse("2026-01-01T01:00:00Z"))

        assertThat(urls)
            .containsExactly(
                "https://x/2025/365/BRDC00WRD_S_20253650000_01D_MN.rnx.gz",
                "https://x/2026/001/BRDC00WRD_S_20260010000_01D_MN.rnx.gz",
            ).inOrder()
    }

    @Test
    fun `downloads and parses gzip files`() {
        val raw = checkNotNull(javaClass.getResourceAsStream("/brdc-2026-266-0200.rnx")).readBytes()
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        val http =
            object : HttpClient {
                override fun get(url: String) = HttpResponse(200, gz)

                override fun postJson(
                    url: String,
                    json: String,
                ) = error("unused")
            }

        val files = EphemerisDownloader(http).download(Instant.parse("2026-09-23T14:00:00Z"))

        assertThat(EphemerisDownloader.parseGzip(files.single()).size).isEqualTo(Fixtures.store().size)
    }
}
