package dev.mobilelps.sources

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.mobilelps.core.WifiAccessPoint
import dev.mobilelps.core.WifiScan
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wi-Fi scan results. Scans are requested periodically; Android allows 4 requests per 2 minutes per app unless
 * "Wi-Fi scan throttling" is disabled in Developer options. Results of scans started by the system or other apps
 * arrive through the same broadcast, so data keeps flowing even when a request is rejected.
 */
class WifiScanSource(
    private val context: Context,
) {
    private val wifi = context.applicationContext.getSystemService<WifiManager>()

    fun scans(interval: Duration = DEFAULT_INTERVAL): Flow<WifiScan> =
        callbackFlow {
            val manager =
                wifi ?: run {
                    close()
                    return@callbackFlow
                }
            var lastRequestAccepted = true
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        val granted =
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED
                        if (!granted) return
                        val results = manager.scanResults.orEmpty()
                        trySend(
                            WifiScan(
                                results.mapNotNull(::toAccessPoint),
                                SystemClock.elapsedRealtimeNanos(),
                                lastRequestAccepted,
                            ),
                        )
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            launch {
                while (true) {
                    @Suppress("DEPRECATION") // Still the only way for apps to request a scan.
                    lastRequestAccepted = manager.startScan()
                    delay(interval)
                }
            }
            awaitClose { context.unregisterReceiver(receiver) }
        }

    private fun toAccessPoint(result: ScanResult): WifiAccessPoint? {
        val ageMillis = SystemClock.elapsedRealtime() - result.timestamp / MICROS_PER_MILLI
        if (ageMillis > MAX_AGE_MILLIS) return null
        val bssid = result.BSSID?.lowercase() ?: return null
        return WifiAccessPoint(
            bssid = bssid,
            ssid = ssid(result),
            signalDbm = result.level,
            frequencyMhz = result.frequency,
            ageMillis = ageMillis,
        )
    }

    private fun ssid(result: ScanResult): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.wifiSsid?.toString()?.removeSurrounding("\"")
        } else {
            @Suppress("DEPRECATION")
            result.SSID
        }?.takeIf { it.isNotEmpty() }

    private companion object {
        /** Four requests per two minutes are allowed; a slightly longer interval never hits the limit. */
        val DEFAULT_INTERVAL = 31.seconds
        const val MICROS_PER_MILLI = 1_000L
        const val MAX_AGE_MILLIS = 60_000L
    }
}
