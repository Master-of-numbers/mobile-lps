package dev.mobilelps.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.mobilelps.core.UrlConnectionHttpClient
import dev.mobilelps.detector.SpoofingState
import dev.mobilelps.gnss.EphemerisDownloader
import dev.mobilelps.lbs.BeaconDbLocator
import dev.mobilelps.lbs.CachingLocator
import dev.mobilelps.provider.MockLocationPublisher
import dev.mobilelps.sources.CellInfoSource
import dev.mobilelps.sources.GnssMeasurementSource
import dev.mobilelps.sources.WifiScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter

/** Foreground service that owns the [LpsEngine] for as long as the proxy is enabled. */
class LpsService : LifecycleService() {
    private var engineJob: Job? = null

    /** Diagnostics (dumpsys report, logcat, extra lookups) are enabled only in debuggable builds. */
    private val debuggable: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        if (engineJob == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                stopSelf()
                return START_NOT_STICKY
            }
            engineJob = startEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engineJob?.cancel()
        engineJob = null
        // The status collector is cancelled together with the engine and never sees its final state,
        // so the stopped state is published here. Diagnostics are kept for the UI.
        Lps.mutableStatus.value =
            Lps.mutableStatus.value.copy(running = false, mock = MockState.INACTIVE, output = null)
        super.onDestroy()
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startEngine(): Job {
        val http = UrlConnectionHttpClient(USER_AGENT)
        val clock =
            object : EngineClock {
                override fun elapsedRealtimeNanos() = SystemClock.elapsedRealtimeNanos()

                override fun wallMillis() = System.currentTimeMillis()
            }
        val publisher = MockLocationPublisher(this)
        val engine =
            LpsEngine(
                gnssEpochs = GnssMeasurementSource(this).epochs(),
                network =
                    NetworkPositioning(
                        cellScans = CellInfoSource(this).scans(),
                        wifiScans = WifiScanSource(this).scans(),
                        locator = CachingLocator(BeaconDbLocator(http), clock::wallMillis),
                        clock = clock,
                        io = Dispatchers.IO,
                        diagnostics = debuggable,
                    ),
                ephemerides =
                    EphemerisRepository(
                        EphemerisDownloader(http),
                        File(cacheDir, "ephemeris"),
                        clock,
                        Dispatchers.IO,
                    ),
                sink = MockSink(publisher),
                clock = clock,
            )
        return lifecycleScope.launch {
            launch { engine.status.collect { Lps.mutableStatus.value = it } }
            launch {
                engine.status
                    .map { it.assessment?.state }
                    .distinctUntilChanged()
                    .collect { state ->
                        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification(state))
                    }
            }
            if (debuggable) launch { logStatus() }
            engine.run()
        }
    }

    private suspend fun logStatus() {
        var lastState: SpoofingState? = null
        var lastLog = 0L
        while (true) {
            val status = Lps.status.value
            val state = status.assessment?.state
            val now = SystemClock.elapsedRealtime()
            if (state != lastState || now - lastLog >= LOG_INTERVAL_MILLIS) {
                Log.i(LOG_TAG, StatusReport.summary(status))
                lastState = state
                lastLog = now
            }
            delay(LOG_POLL_MILLIS)
        }
    }

    /** `adb shell dumpsys activity service dev.mobilelps/dev.mobilelps.service.LpsService` */
    override fun dump(
        fd: FileDescriptor?,
        writer: PrintWriter,
        args: Array<out String>?,
    ) {
        if (!debuggable) {
            writer.println("Diagnostics are available only in debug builds.")
            return
        }
        writer.print(StatusReport.full(Lps.status.value, SystemClock.elapsedRealtimeNanos()))
    }

    private fun startForegroundWithNotification() {
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.lps_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun notification(state: SpoofingState?) =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lps_notification)
            .setContentTitle(getString(R.string.lps_notification_title))
            .setContentText(getString(stateText(state)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
                },
            ).addAction(
                0,
                getString(R.string.lps_notification_stop),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, LpsService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

    private fun stateText(state: SpoofingState?) =
        when (state) {
            SpoofingState.CLEAN -> R.string.lps_state_clean
            SpoofingState.SUSPECT -> R.string.lps_state_suspect
            SpoofingState.SPOOFED -> R.string.lps_state_spoofed
            SpoofingState.NO_GNSS -> R.string.lps_state_no_gnss
            null -> R.string.lps_state_starting
        }

    private class MockSink(
        private val publisher: MockLocationPublisher,
    ) : LocationSink {
        override fun start() = publisher.start().toResult()

        override fun publish(fix: dev.mobilelps.core.PositionFix) = publisher.publish(fix).toResult()

        override fun stop() = publisher.stop()

        private fun MockLocationPublisher.Status.toResult() =
            when (this) {
                MockLocationPublisher.Status.Active -> SinkResult(MockState.ACTIVE)
                MockLocationPublisher.Status.Inactive -> SinkResult(MockState.INACTIVE)
                MockLocationPublisher.Status.NotMockApp -> SinkResult(MockState.NOT_MOCK_APP)
                is MockLocationPublisher.Status.Failed -> SinkResult(MockState.FAILED, message)
            }
    }

    internal companion object {
        const val ACTION_STOP = "dev.mobilelps.service.STOP"
        private const val CHANNEL_ID = "lps"
        private const val NOTIFICATION_ID = 1
        private const val LOG_TAG = "LPS"
        private const val LOG_INTERVAL_MILLIS = 5_000L
        private const val LOG_POLL_MILLIS = 500L
        private const val USER_AGENT = "mobile-lps/0.1 (+https://github.com/Master-of-numbers/mobile-lps)"
    }
}
