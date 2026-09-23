package dev.mobilelps.detector

import dev.mobilelps.core.PositionFix
import dev.mobilelps.core.Wgs84
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rule-based GNSS spoofing detector. Each update evaluates independent indicators, combines them into a score
 * and runs a hysteresis state machine: spoofing latches quickly and is released only after sustained clean data.
 * Not thread-safe; feed it from one coroutine.
 */
class SpoofingDetector(
    private val config: Config = Config(),
) {
    data class Config(
        val latchScore: Double = 0.5,
        val immediateLatchScore: Double = 0.8,
        val latchUpdates: Int = 2,
        val clearScore: Double = 0.2,
        val clearUpdates: Int = 30,
        val noGnssTimeoutNanos: Long = 5_000_000_000L,
        val lbsMaxAgeNanos: Long = 120_000_000_000L,
        val lbsMinRadiusM: Double = 3_000.0,
        val maxTimeOffsetS: Double = 2.0,
        val maxSpeedMps: Double = 150.0,
        val jumpMemoryNanos: Long = 10_000_000_000L,
        val jumpMaxGapNanos: Long = 5_000_000_000L,
        val cn0MinSatellites: Int = 6,
        val cn0MinStdDevDb: Double = 1.5,
        /** Weak signals near the tracking threshold are naturally uniform; spoofers transmit strong ones. */
        val cn0UniformMinMeanDbHz: Double = 35.0,
        val cn0MaxMeanDbHz: Double = 48.0,
        val agcMinSamples: Int = 30,
        val agcDropDb: Double = 6.0,
        val agcSmoothing: Double = 0.05,
        val maxResidualRmsM: Double = 60.0,
        val skyMinChecked: Int = 4,
        val skyMinBelow: Int = 2,
        val skyMinFraction: Double = 0.25,
    )

    private var spoofed = false
    private var highStreak = 0
    private var cleanStreak = 0
    private var lastGnssNanos: Long? = null
    private var previousFix: PositionFix? = null
    private var lastJumpNanos: Long? = null
    private var agcBaseline: Double? = null
    private var agcSamples = 0
    private var lastState = SpoofingState.NO_GNSS

    fun update(input: DetectorInput): Assessment {
        val now = input.elapsedRealtimeNanos
        val gnss = input.gnss
        if (gnss != null && (gnss.fix != null || gnss.cn0DbHz.isNotEmpty())) lastGnssNanos = now

        val indicators = if (gnss != null) evaluate(gnss, input.lbs, now) else emptyMap()
        val score = 1 - indicators.keys.fold(1.0) { acc, indicator -> acc * (1 - indicator.weight) }
        updateLatch(score, hasFix = gnss?.fix != null)

        val gnssLost = lastGnssNanos.let { it == null || now - it > config.noGnssTimeoutNanos }
        val state =
            when {
                gnssLost -> SpoofingState.NO_GNSS
                spoofed -> SpoofingState.SPOOFED
                score >= config.clearScore -> SpoofingState.SUSPECT
                else -> SpoofingState.CLEAN
            }
        if (state == SpoofingState.CLEAN) learnAgc(gnss?.agcDb)
        lastState = state
        return Assessment(state, score, indicators)
    }

    private fun updateLatch(
        score: Double,
        hasFix: Boolean,
    ) {
        if (score >= config.latchScore) highStreak++ else highStreak = 0
        if (score < config.clearScore && hasFix) cleanStreak++ else cleanStreak = 0
        if (score >= config.immediateLatchScore || highStreak >= config.latchUpdates) spoofed = true
        if (spoofed && cleanStreak >= config.clearUpdates) {
            spoofed = false
            highStreak = 0
        }
    }

    private fun evaluate(
        gnss: GnssEvidence,
        lbs: LbsEvidence?,
        now: Long,
    ): Map<Indicator, String> {
        val result = mutableMapOf<Indicator, String>()
        val fix = gnss.fix
        val freshLbs = lbs?.takeIf { now - it.elapsedRealtimeNanos <= config.lbsMaxAgeNanos }

        checkSky(gnss)?.let { result[Indicator.SATELLITES_BELOW_HORIZON] = it }
        val lbsDistance = if (fix != null && freshLbs != null) lbsDistance(fix, freshLbs) else null
        val agreesWithLbs = lbsDistance != null && lbsDistance.first <= lbsDistance.second
        if (lbsDistance != null && !agreesWithLbs) {
            result[Indicator.LBS_MISMATCH] = "%.1f km from cell position".format(lbsDistance.first / METERS_PER_KM)
        }
        gnss.timeOffsetS?.takeIf { abs(it) > config.maxTimeOffsetS }?.let {
            result[Indicator.TIME_OFFSET] = "clock differs by %.1f s".format(it)
        }
        checkJump(fix, now, agreesWithLbs)?.let { result[Indicator.POSITION_JUMP] = it }
        checkCn0(gnss.cn0DbHz, result)
        checkAgc(gnss.agcDb)?.let { result[Indicator.AGC_DROP] = it }
        gnss.residualRmsM?.takeIf { fix != null && it > config.maxResidualRmsM }?.let {
            result[Indicator.HIGH_RESIDUALS] = "residuals %.0f m".format(it)
        }
        return result
    }

    private fun checkSky(gnss: GnssEvidence): String? {
        val below = gnss.satellitesBelowHorizon
        val checked = gnss.satellitesChecked
        val suspicious =
            checked >= config.skyMinChecked && below >= config.skyMinBelow && below >= config.skyMinFraction * checked
        return if (suspicious) "$below of $checked below horizon" else null
    }

    /** Distance between GNSS and LBS positions and the largest distance still consistent with both. */
    private fun lbsDistance(
        fix: PositionFix,
        lbs: LbsEvidence,
    ): Pair<Double, Double> {
        val distance = Wgs84.distanceM(fix.latDeg, fix.lonDeg, lbs.latDeg, lbs.lonDeg)
        val allowed =
            max(UNCERTAINTY_SIGMAS * lbs.accuracyM, config.lbsMinRadiusM) + UNCERTAINTY_SIGMAS * fix.horizontalAccuracyM
        return distance to allowed
    }

    private fun checkAgc(agcDb: Double?): String? {
        val baseline = agcBaseline
        if (agcDb == null || baseline == null || agcSamples < config.agcMinSamples) return null
        val drop = baseline - agcDb
        return if (drop > config.agcDropDb) "AGC %.1f dB below normal".format(drop) else null
    }

    /** A jump that lands on the cell-tower position is the receiver recovering, not a spoofer taking over. */
    private fun checkJump(
        fix: PositionFix?,
        now: Long,
        agreesWithLbs: Boolean,
    ): String? {
        var detail: String? = null
        val previous = previousFix
        if (agreesWithLbs) lastJumpNanos = null
        if (fix != null && previous != null && !agreesWithLbs) {
            val dtNanos = fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
            if (dtNanos in 1..config.jumpMaxGapNanos) {
                val distance = Wgs84.distanceM(previous.latDeg, previous.lonDeg, fix.latDeg, fix.lonDeg)
                val speed = distance / (dtNanos / NANOS_PER_SECOND)
                if (speed > config.maxSpeedMps) {
                    lastJumpNanos = now
                    detail = "jumped %.0f m at %.0f m/s".format(distance, speed)
                }
            }
        }
        if (fix != null) previousFix = fix
        val recentJump = lastJumpNanos?.let { now - it <= config.jumpMemoryNanos } == true
        return detail ?: if (recentJump) "recent position jump" else null
    }

    private fun checkCn0(
        cn0: List<Double>,
        result: MutableMap<Indicator, String>,
    ) {
        if (cn0.size < MIN_CN0_FOR_MEAN) return
        val mean = cn0.average()
        if (mean > config.cn0MaxMeanDbHz) result[Indicator.CN0_HIGH] = "mean C/N0 %.1f dB-Hz".format(mean)
        if (cn0.size >= config.cn0MinSatellites && mean >= config.cn0UniformMinMeanDbHz) {
            val stdDev = sqrt(cn0.sumOf { (it - mean) * (it - mean) } / cn0.size)
            if (stdDev < config.cn0MinStdDevDb) result[Indicator.CN0_UNIFORM] = "C/N0 spread %.1f dB".format(stdDev)
        }
    }

    private fun learnAgc(agcDb: Double?) {
        if (agcDb == null) return
        val baseline = agcBaseline
        agcBaseline = if (baseline == null) agcDb else baseline + config.agcSmoothing * (agcDb - baseline)
        agcSamples++
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val MIN_CN0_FOR_MEAN = 4
        const val UNCERTAINTY_SIGMAS = 3
        const val METERS_PER_KM = 1000
    }
}
