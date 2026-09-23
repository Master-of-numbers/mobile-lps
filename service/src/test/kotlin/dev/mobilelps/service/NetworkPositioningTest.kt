package dev.mobilelps.service

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.CellScan
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.RadioType
import dev.mobilelps.core.WifiAccessPoint
import dev.mobilelps.core.WifiScan
import dev.mobilelps.lbs.LbsLocation
import dev.mobilelps.lbs.LbsResult
import dev.mobilelps.lbs.NetworkQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkPositioningTest {
    private val cell = CellTower(RadioType.LTE, 255, 1, 100, 200, -80, null, true, 0)
    private val aps =
        listOf(
            WifiAccessPoint("00:11:22:33:44:01", "Cafe", -60, 2437, 0),
            WifiAccessPoint("00:11:22:33:44:02", "Home_nomap", -55, 2412, 0),
            WifiAccessPoint("00:11:22:33:44:03", "Shop", -70, 5180, 0),
        )
    private val queries = mutableListOf<NetworkQuery>()
    private val found = LbsResult.Found(LbsLocation(50.45, 30.52, 40.0))

    private fun TestScope.positioning(
        cells: Flow<CellScan>,
        wifi: Flow<WifiScan>,
        diagnostics: Boolean = false,
    ) = NetworkPositioning(
        cellScans = cells,
        wifiScans = wifi,
        locator = {
            queries += it
            found
        },
        clock =
            object : EngineClock {
                override fun elapsedRealtimeNanos() = testScheduler.currentTime * 1_000_000

                override fun wallMillis() = testScheduler.currentTime
            },
        io = StandardTestDispatcher(testScheduler),
        diagnostics = diagnostics,
    )

    @Test
    fun `sends cells and filtered wifi in one query`() =
        runTest {
            val positioning = positioning(flowOf(CellScan(listOf(cell), 0L)), flowOf(WifiScan(aps, 0L, false)))

            val job = launch { positioning.run() }
            runCurrent()
            advanceTimeBy(10_001)

            val last = queries.last { it.wifi.isNotEmpty() }
            assertThat(last.cells).containsExactly(cell)
            assertThat(last.wifi.map { it.bssid }).containsExactly("00:11:22:33:44:01", "00:11:22:33:44:03")
            // The same answer with and without Wi-Fi: cell-based, so the LTE cell radius replaces the claimed 40 m.
            assertThat(positioning.fix.value?.horizontalAccuracyM).isEqualTo(1_500.0)
            assertThat(positioning.stats.value.wifiSeen).isEqualTo(3)
            assertThat(positioning.stats.value.wifiUsable).isEqualTo(2)
            assertThat(positioning.stats.value.wifiThrottled).isTrue()
            job.cancel()
        }

    @Test
    fun `stale scans are not sent`() =
        runTest {
            val positioning = positioning(flowOf(CellScan(listOf(cell), 0L)), emptyFlow())

            val job = launch { positioning.run() }
            advanceTimeBy(40_001)

            assertThat(positioning.stats.value.lastResult).isEqualTo("No identified cells or Wi-Fi access points")
            job.cancel()
        }

    @Test
    fun `diagnostics mode checks wifi alone once a minute`() =
        runTest {
            val positioning =
                positioning(flowOf(CellScan(listOf(cell), 0L)), flowOf(WifiScan(aps, 0L, true)), diagnostics = true)

            val job = launch { positioning.run() }
            advanceTimeBy(30_001)

            val wifiOnly = queries.filter { it.cells.isEmpty() }
            assertThat(wifiOnly).hasSize(1)
            assertThat(positioning.stats.value.wifiOnlyResult).startsWith("Found ±40 m")
            job.cancel()
        }

    @Test
    fun `keeps the claimed accuracy when wifi changed the answer`() =
        runTest {
            val wifiFix = LbsResult.Found(LbsLocation(50.46, 30.53, 30.0))
            val positioning =
                NetworkPositioning(
                    cellScans = flowOf(CellScan(listOf(cell), 0L)),
                    wifiScans = flowOf(WifiScan(aps, 0L, true)),
                    locator = { if (it.wifi.isEmpty()) found else wifiFix },
                    clock =
                        object : EngineClock {
                            override fun elapsedRealtimeNanos() = testScheduler.currentTime * 1_000_000

                            override fun wallMillis() = testScheduler.currentTime
                        },
                    io = StandardTestDispatcher(testScheduler),
                )

            val job = launch { positioning.run() }
            advanceTimeBy(10_001)

            assertThat(positioning.fix.value?.horizontalAccuracyM).isEqualTo(30.0)
            assertThat(positioning.stats.value.lastResult).contains("Wi-Fi;")
            job.cancel()
        }
}
