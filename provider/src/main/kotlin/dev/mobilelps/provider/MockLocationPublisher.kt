package dev.mobilelps.provider

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import androidx.core.content.getSystemService
import dev.mobilelps.core.PositionFix

/**
 * Replaces the platform location providers with test providers and feeds them positions.
 *
 * Requires the app to be selected as "mock location app" in Developer options; otherwise every call fails
 * with a [SecurityException], reported as [Status.NotMockApp].
 */
class MockLocationPublisher(
    context: Context,
) {
    sealed interface Status {
        data object Inactive : Status

        data object Active : Status

        data object NotMockApp : Status

        data class Failed(
            val message: String,
        ) : Status
    }

    private val locationManager = checkNotNull(context.getSystemService<LocationManager>())

    /** GPS carries the fix itself; network and fused are replaced so that no provider leaks the spoofed position. */
    private val providers: List<String> =
        buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }

    var status: Status = Status.Inactive
        private set

    fun start(): Status {
        status =
            guarded {
                providers.forEach { provider ->
                    addTestProvider(provider)
                    locationManager.setTestProviderEnabled(provider, true)
                }
                Status.Active
            }
        if (status != Status.Active) stop()
        return status
    }

    fun publish(fix: PositionFix): Status {
        if (status != Status.Active) return status
        status =
            guarded {
                providers.forEach { provider ->
                    locationManager.setTestProviderLocation(provider, fix.toLocation(provider))
                }
                Status.Active
            }
        return status
    }

    fun stop() {
        providers.forEach { provider ->
            runCatching { locationManager.removeTestProvider(provider) }
        }
        if (status == Status.Active) status = Status.Inactive
    }

    // The pre-31 overload is annotated with ProviderProperties constants, which do not exist before API 31;
    // the Criteria constants have the same values.
    @SuppressLint("WrongConstant")
    private fun addTestProvider(name: String) {
        val isGps = name == LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties =
                ProviderProperties
                    .Builder()
                    .setHasSatelliteRequirement(isGps)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(if (isGps) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE)
                    .build()
            locationManager.addTestProvider(name, properties)
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                name,
                false,
                isGps,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                if (isGps) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE,
            )
        }
    }

    private inline fun guarded(block: () -> Status): Status =
        try {
            block()
        } catch (_: SecurityException) {
            Status.NotMockApp
        } catch (e: IllegalArgumentException) {
            Status.Failed(e.message ?: "IllegalArgumentException")
        }

    private fun PositionFix.toLocation(provider: String) =
        Location(provider).apply {
            latitude = latDeg
            longitude = lonDeg
            accuracy = horizontalAccuracyM.toFloat()
            time = timeMillis
            elapsedRealtimeNanos = this@toLocation.elapsedRealtimeNanos
            altitudeM?.let { altitude = it }
            speedMps?.let { speed = it.toFloat() }
            bearingDeg?.let { bearing = it.toFloat() }
        }
}
