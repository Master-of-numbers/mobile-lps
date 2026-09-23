package dev.mobilelps.sources

import android.Manifest
import android.content.Context
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.getSystemService
import dev.mobilelps.core.Constellation
import dev.mobilelps.core.GnssClockData
import dev.mobilelps.core.GnssEpoch
import dev.mobilelps.core.GnssMeasurementData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executors
import kotlin.math.abs

/** Streams raw GNSS measurement events from the platform. */
class GnssMeasurementSource(
    context: Context,
) {
    private val locationManager = checkNotNull(context.getSystemService<LocationManager>())

    /**
     * Emits one [GnssEpoch] per measurement event. On Android 12+ full tracking is requested (no duty cycling);
     * on Android 10-11 the user enables "Force full GNSS measurements" in Developer options.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun epochs(): Flow<GnssEpoch> =
        callbackFlow {
            val callback =
                object : GnssMeasurementsEvent.Callback() {
                    override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                        trySend(event.toEpoch())
                    }
                }
            val executor = Executors.newSingleThreadExecutor()
            val registered =
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        val request = GnssMeasurementRequest.Builder().setFullTracking(true).build()
                        locationManager.registerGnssMeasurementsCallback(request, executor, callback)
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        locationManager.registerGnssMeasurementsCallback(executor, callback)
                    }

                    else -> {
                        @Suppress("DEPRECATION")
                        locationManager.registerGnssMeasurementsCallback(callback, Handler(Looper.getMainLooper()))
                    }
                }
            if (!registered) close(IllegalStateException("GNSS measurements are not supported"))
            awaitClose {
                locationManager.unregisterGnssMeasurementsCallback(callback)
                executor.shutdown()
            }
        }

    private fun GnssMeasurementsEvent.toEpoch(): GnssEpoch {
        val c = clock
        return GnssEpoch(
            clock =
                GnssClockData(
                    timeNanos = c.timeNanos,
                    fullBiasNanos = if (c.hasFullBiasNanos()) c.fullBiasNanos else null,
                    biasNanos = if (c.hasBiasNanos()) c.biasNanos else 0.0,
                    hardwareClockDiscontinuityCount = c.hardwareClockDiscontinuityCount,
                    elapsedRealtimeNanos = if (c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else null,
                ),
            measurements = measurements.map { it.toData() },
            agcDb = l1AgcDb(),
            receivedWallMillis = System.currentTimeMillis(),
            receivedElapsedNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }

    private fun GnssMeasurementsEvent.l1AgcDb(): Double? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            gnssAutomaticGainControls
                .firstOrNull { abs(it.carrierFrequencyHz - L1_FREQUENCY_HZ) < FREQUENCY_TOLERANCE_HZ }
                ?.levelDb
        } else {
            @Suppress("DEPRECATION")
            measurements
                .firstOrNull { it.hasAutomaticGainControlLevelDb() && it.isL1() }
                ?.automaticGainControlLevelDb
        }

    private fun GnssMeasurement.isL1(): Boolean =
        !hasCarrierFrequencyHz() || abs(carrierFrequencyHz - L1_FREQUENCY_HZ) < FREQUENCY_TOLERANCE_HZ

    private fun GnssMeasurement.toData() =
        GnssMeasurementData(
            constellation = constellation(constellationType),
            svid = svid,
            state = state,
            receivedSvTimeNanos = receivedSvTimeNanos,
            receivedSvTimeUncertaintyNanos = receivedSvTimeUncertaintyNanos,
            timeOffsetNanos = timeOffsetNanos,
            cn0DbHz = cn0DbHz,
            pseudorangeRateMps = pseudorangeRateMetersPerSecond,
            pseudorangeRateUncertaintyMps = pseudorangeRateUncertaintyMetersPerSecond,
            carrierFrequencyHz = if (hasCarrierFrequencyHz()) carrierFrequencyHz.toDouble() else null,
        )

    private fun constellation(type: Int) =
        when (type) {
            GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
            GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
            GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
            GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
            GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
            GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
            GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
            else -> Constellation.UNKNOWN
        }

    private companion object {
        const val L1_FREQUENCY_HZ = 1_575.42e6
        const val FREQUENCY_TOLERANCE_HZ = 1e6
    }
}
