package dev.mobilelps.service

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.CellScan
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.FixSource
import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.RadioType
import dev.mobilelps.detector.SpoofingState
import dev.mobilelps.gnss.EphemerisStore
import dev.mobilelps.lbs.LbsLocation
import dev.mobilelps.lbs.LbsResult
import dev.mobilelps.lbs.NetworkQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LpsEngineTest {
    private val cell = CellTower(RadioType.LTE, 255, 1, 100, 200, -80, null, true, 0)

    private class FakeSink(
        var state: MockState = MockState.ACTIVE,
    ) : LocationSink {
        val published = mutableListOf<PositionFix>()
        var stopped = false

        override fun start() = SinkResult(state)

        override fun publish(fix: PositionFix): SinkResult {
            published += fix
            return SinkResult(state)
        }

        override fun stop() {
            stopped = true
        }
    }

    private object NoEphemerides : LpsEngine.Ephemerides {
        override val store = MutableStateFlow<EphemerisStore?>(null)
        override val stats = MutableStateFlow(EphemerisStats())

        override suspend fun run() = awaitCancellation()
    }

    private fun TestScope.engine(
        sink: LocationSink,
        locate: (NetworkQuery) -> LbsResult,
    ): LpsEngine {
        val clock =
            object : EngineClock {
                override fun elapsedRealtimeNanos() = testScheduler.currentTime * 1_000_000

                override fun wallMillis() = 1_700_000_000_000 + testScheduler.currentTime
            }
        return LpsEngine(
            gnssEpochs = emptyFlow(),
            network =
                NetworkPositioning(
                    cellScans = flowOf(CellScan(listOf(cell), 0L)),
                    wifiScans = emptyFlow(),
                    locator = locate,
                    clock = clock,
                    io = StandardTestDispatcher(testScheduler),
                ),
            ephemerides = NoEphemerides,
            sink = sink,
            clock = clock,
        )
    }

    @Test
    fun `without gnss publishes the cell position every second`() =
        runTest {
            val sink = FakeSink()
            val engine = engine(sink) { LbsResult.Found(LbsLocation(50.45, 30.52, 1_200.0)) }

            val job = launch { engine.run() }
            advanceTimeBy(10_500)

            assertThat(
                engine.status.value.assessment
                    ?.state,
            ).isEqualTo(SpoofingState.NO_GNSS)
            assertThat(sink.published.size).isAtLeast(9)
            assertThat(sink.published.last().source).isEqualTo(FixSource.LBS)
            assertThat(sink.published.last().latDeg).isEqualTo(50.45)
            job.cancel()
        }

    @Test
    fun `does not publish until the app is the mock location app and removes providers on stop`() =
        runTest {
            val sink = FakeSink(MockState.NOT_MOCK_APP)
            val engine = engine(sink) { LbsResult.Found(LbsLocation(50.45, 30.52, 1_200.0)) }

            val job = launch { engine.run() }
            advanceTimeBy(3_500)
            assertThat(sink.published).isEmpty()
            assertThat(engine.status.value.mock).isEqualTo(MockState.NOT_MOCK_APP)

            sink.state = MockState.ACTIVE
            advanceTimeBy(2_000)
            assertThat(sink.published).isNotEmpty()

            job.cancel()
            testScheduler.advanceUntilIdle()
            assertThat(sink.stopped).isTrue()
        }

    @Test
    fun `reports lbs errors`() =
        runTest {
            val engine = engine(FakeSink()) { LbsResult.Failed("offline") }

            val job = launch { engine.run() }
            advanceTimeBy(1_500)

            assertThat(engine.status.value.lbs.lastResult).isEqualTo("Error: offline")
            job.cancel()
        }
}
