package dev.mobilelps.gnss

import dev.mobilelps.core.Constellation
import dev.mobilelps.core.GnssEpoch
import dev.mobilelps.core.GnssMeasurementData
import dev.mobilelps.core.GnssState
import kotlin.math.abs
import kotlin.math.max

/** A pseudorange ready for positioning. */
data class Observation(
    val constellation: Constellation,
    val svid: Int,
    val pseudorangeM: Double,
    val sigmaM: Double,
    val pseudorangeRateMps: Double,
    val rateSigmaMps: Double,
    val cn0DbHz: Double,
)

/** Pseudoranges of one epoch and the receive time they refer to. */
data class ObservationEpoch(
    /** Receive time, GPS seconds; the solved clock bias corrects it. */
    val receiveTime: Double,
    val observations: List<Observation>,
    /** True when the receiver reported its full clock bias; false when the time was derived from satellites. */
    val receiverClock: Boolean = true,
)

/**
 * Converts Android raw GNSS measurements into pseudoranges, following the approach of Google's
 * GPS Measurement Tools. Only GPS L1 C/A and Galileo E1 with a known time of week are used.
 */
object Observations {
    private const val L1_FREQUENCY_HZ = 1_575.42e6
    private const val FREQUENCY_TOLERANCE_HZ = 1e6
    private const val MAX_TIME_UNCERTAINTY_NS = 500L
    private const val MIN_CN0_DBHZ = 18.0
    private const val MIN_PSEUDORANGE_M = 1.8e7
    private const val MAX_PSEUDORANGE_M = 3.2e7
    private const val MIN_SIGMA_M = 3.0
    private const val MIN_RATE_SIGMA_MPS = 0.1
    private const val NANOS_PER_SECOND = 1e9

    /** Travel time from a satellite near zenith; the closest satellite has the latest transmit time. */
    private const val NOMINAL_TRAVEL_NANOS = 68_000_000L

    /**
     * Converts one measurement event. [approxGpsSeconds] is the system clock expressed in GPS time; it is only
     * used to resolve the GPS week when the receiver does not report its full clock bias.
     */
    fun from(
        epoch: GnssEpoch,
        approxGpsSeconds: Double,
    ): ObservationEpoch {
        val usable = epoch.measurements.filter(::isUsable)
        val fullBias = epoch.clock.fullBiasNanos
        return if (fullBias != null) {
            fromReceiverClock(epoch, usable, fullBias)
        } else {
            fromSatelliteTime(usable, approxGpsSeconds)
        }
    }

    /** Receive time from the receiver clock: `timeNanos - (fullBiasNanos + biasNanos)`. */
    private fun fromReceiverClock(
        epoch: GnssEpoch,
        usable: List<GnssMeasurementData>,
        fullBias: Long,
    ): ObservationEpoch {
        val clock = epoch.clock
        // Integer and fractional parts are kept apart: the full value in nanoseconds exceeds double precision.
        val receiveNanos = clock.timeNanos - fullBias
        val weekNanos = Math.floorMod(receiveNanos, GnssTime.NANOS_PER_WEEK)
        val observations =
            usable.mapNotNull { m ->
                var travelNanos = (weekNanos - m.receivedSvTimeNanos) + (m.timeOffsetNanos - clock.biasNanos)
                if (travelNanos < -GnssTime.NANOS_PER_WEEK / 2) travelNanos += GnssTime.NANOS_PER_WEEK
                observation(m, travelNanos)
            }
        return ObservationEpoch(
            receiveNanos / NANOS_PER_SECOND - clock.biasNanos * 1e-9,
            observations,
            receiverClock = true,
        )
    }

    /**
     * Without the receiver clock, the receive time is anchored to the latest transmit time plus a nominal travel
     * time. The anchor error is common to all pseudoranges and ends up in the solved clock bias; satellite
     * positions are unaffected because the solver computes transmit time as receive time minus pseudorange.
     */
    private fun fromSatelliteTime(
        usable: List<GnssMeasurementData>,
        approxGpsSeconds: Double,
    ): ObservationEpoch {
        val approxNanos = (approxGpsSeconds * NANOS_PER_SECOND).toLong()
        val weekStart = Math.floorDiv(approxNanos, GnssTime.NANOS_PER_WEEK) * GnssTime.NANOS_PER_WEEK
        val transmitNanos =
            usable.map { m ->
                var t = weekStart + m.receivedSvTimeNanos
                // Near a week boundary the satellite time of week may belong to the neighbouring week.
                if (t - approxNanos > GnssTime.NANOS_PER_WEEK / 2) t -= GnssTime.NANOS_PER_WEEK
                if (approxNanos - t > GnssTime.NANOS_PER_WEEK / 2) t += GnssTime.NANOS_PER_WEEK
                t
            }
        val receiveNanos = (transmitNanos.maxOrNull() ?: approxNanos) + NOMINAL_TRAVEL_NANOS
        val observations =
            usable.zip(transmitNanos).mapNotNull { (m, t) -> observation(m, (receiveNanos - t) + m.timeOffsetNanos) }
        return ObservationEpoch(receiveNanos / NANOS_PER_SECOND, observations, receiverClock = false)
    }

    private fun observation(
        m: GnssMeasurementData,
        travelNanos: Double,
    ): Observation? {
        val pseudorange = travelNanos * 1e-9 * GnssTime.SPEED_OF_LIGHT_MPS
        if (pseudorange !in MIN_PSEUDORANGE_M..MAX_PSEUDORANGE_M) return null
        return Observation(
            constellation = m.constellation,
            svid = m.svid,
            pseudorangeM = pseudorange,
            sigmaM = max(MIN_SIGMA_M, m.receivedSvTimeUncertaintyNanos * 1e-9 * GnssTime.SPEED_OF_LIGHT_MPS),
            pseudorangeRateMps = m.pseudorangeRateMps,
            rateSigmaMps = max(MIN_RATE_SIGMA_MPS, m.pseudorangeRateUncertaintyMps),
            cn0DbHz = m.cn0DbHz,
        )
    }

    private fun isUsable(m: GnssMeasurementData): Boolean {
        if (m.constellation != Constellation.GPS && m.constellation != Constellation.GALILEO) return false
        val frequency = m.carrierFrequencyHz ?: L1_FREQUENCY_HZ
        if (abs(frequency - L1_FREQUENCY_HZ) > FREQUENCY_TOLERANCE_HZ) return false
        val towKnown = m.state and (GnssState.TOW_DECODED or GnssState.TOW_KNOWN) != 0
        val ambiguous = m.state and GnssState.MSEC_AMBIGUOUS != 0
        return m.state and GnssState.CODE_LOCK != 0 &&
            towKnown &&
            !ambiguous &&
            m.receivedSvTimeUncertaintyNanos <= MAX_TIME_UNCERTAINTY_NS &&
            m.cn0DbHz >= MIN_CN0_DBHZ
    }
}
