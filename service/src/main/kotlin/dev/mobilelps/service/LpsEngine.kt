package dev.mobilelps.service

import dev.mobilelps.core.Constellation
import dev.mobilelps.core.Ecef
import dev.mobilelps.core.FixSource
import dev.mobilelps.core.Geodetic
import dev.mobilelps.core.GnssEpoch
import dev.mobilelps.core.GnssState
import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.Wgs84
import dev.mobilelps.detector.Assessment
import dev.mobilelps.detector.DetectorInput
import dev.mobilelps.detector.GnssEvidence
import dev.mobilelps.detector.LbsEvidence
import dev.mobilelps.detector.SpoofingDetector
import dev.mobilelps.gnss.EphemerisStore
import dev.mobilelps.gnss.GnssTime
import dev.mobilelps.gnss.ObservationEpoch
import dev.mobilelps.gnss.Observations
import dev.mobilelps.gnss.PvtSolution
import dev.mobilelps.gnss.PvtSolver
import dev.mobilelps.gnss.SkyCheck
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * The location pipeline: raw GNSS → PVT → spoofing detector, cells and Wi-Fi → LBS position, and once per second the
 * trusted position goes to the [LocationSink]. Free of Android APIs so it runs in JVM tests.
 */
class LpsEngine(
    private val gnssEpochs: Flow<GnssEpoch>,
    private val network: NetworkPositioning,
    private val ephemerides: Ephemerides,
    private val sink: LocationSink,
    private val clock: EngineClock,
    private val detector: SpoofingDetector = SpoofingDetector(),
) {
    /** Ephemeris source; [EphemerisRepository] in production. */
    interface Ephemerides {
        val store: StateFlow<EphemerisStore?>
        val stats: StateFlow<EphemerisStats>

        suspend fun run()
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow(LpsStatus())
    val status: StateFlow<LpsStatus> = _status.asStateFlow()

    private var gnssFix: PositionFix? = null
    private var lastPvt: Ecef? = null
    private var assessment: Assessment? = null
    private var lastGnssUpdateNanos = 0L

    suspend fun run() {
        val started = sink.start()
        _status.update { it.copy(running = true, mock = started.state, mockError = started.error) }
        try {
            coroutineScope {
                launch { ephemerides.run() }
                launch { ephemerides.stats.collect { stats -> _status.update { it.copy(ephemeris = stats) } } }
                launch { gnssEpochs.collect(::onGnssEpoch) }
                launch { network.run() }
                launch { network.stats.collect { stats -> _status.update { it.copy(lbs = stats) } } }
                launch { publishLoop() }
            }
        } finally {
            sink.stop()
            _status.update { it.copy(running = false, mock = MockState.INACTIVE) }
        }
    }

    private suspend fun publishLoop() {
        while (true) {
            val now = clock.elapsedRealtimeNanos()
            mutex.withLock {
                // Keep the detector running when measurement events stop arriving (jamming, receiver off).
                if (now - lastGnssUpdateNanos > NO_EVENT_UPDATE_NANOS) updateDetector(null, now)
                // The user may pick this app as mock location app while the service runs.
                if (_status.value.mock != MockState.ACTIVE) {
                    val started = sink.start()
                    _status.update { it.copy(mock = started.state, mockError = started.error) }
                }
                val fix = LocationArbiter.choose(assessment?.state, gnssFix, network.fix.value, now, clock.wallMillis())
                if (fix != null && _status.value.mock == MockState.ACTIVE) {
                    val result = sink.publish(fix)
                    _status.update { it.copy(output = fix, mock = result.state, mockError = result.error) }
                } else {
                    _status.update { it.copy(output = null) }
                }
            }
            delay(PUBLISH_INTERVAL_MILLIS)
        }
    }

    private suspend fun onGnssEpoch(epoch: GnssEpoch) {
        val store = ephemerides.store.value
        val now = epoch.clock.elapsedRealtimeNanos ?: epoch.receivedElapsedNanos
        val wallAtEpoch = epoch.receivedWallMillis - (epoch.receivedElapsedNanos - now) / NANOS_PER_MILLI
        val observations = Observations.from(epoch, GnssTime.unixMillisToGpsSeconds(wallAtEpoch))
        mutex.withLock {
            val initialGuess = lastPvt ?: lbsEvidence?.let { Wgs84.toEcef(Geodetic(it.latDeg, it.lonDeg)) }
            val pvt = store?.let { PvtSolver(it).solve(observations, initialGuess) }
            val fix = pvt?.toFix(wallAtEpoch, now)
            val timeOffset =
                pvt?.let {
                    (
                        GnssTime.gpsSecondsToUnixMillis(
                            it.gpsTime,
                        ) - wallAtEpoch
                    ) / MILLIS_PER_SECOND
                }
            val tracked = trackedL1(epoch)
            val sky = skyCheck(store, observations.receiveTime, tracked.keys.toList())

            gnssFix = fix ?: gnssFix
            lastPvt = pvt?.ecef ?: lastPvt
            updateDetector(
                GnssEvidence(
                    fix = fix,
                    residualRmsM = pvt?.residualRmsM,
                    timeOffsetS = timeOffset,
                    cn0DbHz = tracked.values.toList(),
                    agcDb = epoch.agcDb,
                    satellitesBelowHorizon = sky.first,
                    satellitesChecked = sky.second,
                ),
                now,
            )
            _status.update {
                it.copy(
                    gnss =
                        GnssStats(
                            epochs = it.gnss.epochs + 1,
                            lastEpochElapsedNanos = now,
                            tracked = tracked.size,
                            usable = observations.observations.size,
                            receiverClock = epoch.clock.fullBiasNanos != null,
                            fix = fix ?: it.gnss.fix,
                            satellitesUsed = pvt?.satellitesUsed ?: 0,
                            residualRmsM = pvt?.residualRmsM,
                            timeOffsetS = timeOffset,
                            error = gnssError(store, observations.observations.size, pvt),
                            signals = signals(epoch, observations, pvt),
                        ),
                )
            }
        }
    }

    private fun signals(
        epoch: GnssEpoch,
        observations: ObservationEpoch,
        pvt: PvtSolution?,
    ): List<SignalInfo> {
        val usable = observations.observations.map { it.constellation to it.svid }.toSet()
        val used = pvt?.satellites.orEmpty().associateBy { it.constellation to it.svid }
        return epoch.measurements.map { m ->
            val key = m.constellation to m.svid
            val isL1 = m.carrierFrequencyHz.let { it == null || abs(it - L1_FREQUENCY_HZ) < L1_TOLERANCE_HZ }
            SignalInfo(
                constellation = m.constellation,
                svid = m.svid,
                carrierMhz = m.carrierFrequencyHz?.let { it / HZ_PER_MHZ },
                cn0DbHz = m.cn0DbHz,
                state = m.state,
                usable = isL1 && key in usable,
                elevationDeg = if (isL1) used[key]?.elevationDeg else null,
                residualM = if (isL1) used[key]?.residualM else null,
            )
        }
    }

    /** Strongest L1/E1-band C/N0 per tracked satellite. Other bands would count a satellite twice. */
    private fun trackedL1(epoch: GnssEpoch): Map<Pair<Constellation, Int>, Double> =
        epoch.measurements
            .filter { it.state and GnssState.CODE_LOCK != 0 && it.cn0DbHz > 0 }
            .filter { m -> m.carrierFrequencyHz.let { it == null || abs(it - L1_FREQUENCY_HZ) < L1_TOLERANCE_HZ } }
            .groupBy { it.constellation to it.svid }
            .mapValues { (_, signals) -> signals.maxOf { it.cn0DbHz } }

    private fun gnssError(
        store: EphemerisStore?,
        usable: Int,
        pvt: PvtSolution?,
    ): String? =
        when {
            store == null -> "No ephemeris yet"
            usable == 0 -> "No GPS/Galileo L1 signal with decoded time of week"
            pvt == null -> "Not enough satellites for a position"
            else -> null
        }

    /** Counts tracked satellites that are below the horizon of the cell-tower position. */
    private fun skyCheck(
        store: EphemerisStore?,
        receiveTime: Double,
        satellites: List<Pair<Constellation, Int>>,
    ): Pair<Int, Int> {
        val lbs = lbsEvidence ?: return 0 to 0
        if (store == null) return 0 to 0
        val elevations = SkyCheck.elevationsDeg(store, Geodetic(lbs.latDeg, lbs.lonDeg), receiveTime, satellites)
        return elevations.values.count { it < BELOW_HORIZON_DEG } to elevations.size
    }

    private fun updateDetector(
        gnss: GnssEvidence?,
        now: Long,
    ) {
        lastGnssUpdateNanos = now
        val result = detector.update(DetectorInput(now, gnss, lbsEvidence))
        assessment = result
        _status.update { it.copy(assessment = result) }
    }

    private val lbsEvidence: LbsEvidence?
        get() =
            network.fix.value?.let {
                LbsEvidence(
                    it.latDeg,
                    it.lonDeg,
                    it.horizontalAccuracyM,
                    it.elapsedRealtimeNanos,
                )
            }

    private fun PvtSolution.toFix(
        wallMillis: Long,
        elapsedNanos: Long,
    ) = PositionFix(
        latDeg = position.latDeg,
        lonDeg = position.lonDeg,
        altitudeM = position.heightM,
        horizontalAccuracyM = horizontalAccuracyM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        // The system clock is used on purpose: GNSS time is exactly what a spoofer controls.
        timeMillis = wallMillis,
        elapsedRealtimeNanos = elapsedNanos,
        source = FixSource.GNSS,
    )

    private companion object {
        const val PUBLISH_INTERVAL_MILLIS = 1_000L
        const val NO_EVENT_UPDATE_NANOS = 1_500_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000.0
        const val BELOW_HORIZON_DEG = -10.0
        const val L1_FREQUENCY_HZ = 1_575.42e6
        const val L1_TOLERANCE_HZ = 1e6
        const val HZ_PER_MHZ = 1e6
    }
}
