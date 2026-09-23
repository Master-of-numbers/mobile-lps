package dev.mobilelps.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.mobilelps.core.FixSource
import dev.mobilelps.detector.SpoofingState
import dev.mobilelps.service.LpsStatus
import dev.mobilelps.service.MockState

/** What matters while driving: is GNSS trusted, what do navigators get, does anything need fixing. */
@Composable
internal fun DashboardTab(status: LpsStatus) {
    if (!status.running) {
        Section("Stopped") { Text("Navigation apps use the phone's own location.") }
        return
    }
    val state = status.assessment?.state
    Section("GNSS", color = stateColor(state)) {
        Text(stateTitle(state), style = MaterialTheme.typography.headlineSmall)
        status.assessment
            ?.indicators
            ?.values
            ?.forEach { Text("• $it") }
    }
    Section("Navigation apps receive") {
        val output = status.output
        val source =
            when {
                status.mock != MockState.ACTIVE -> "Nothing: mock location is not active"
                output == null -> "Nothing yet: no trusted position"
                output.source == FixSource.GNSS -> "GNSS position"
                else -> "Cell / Wi-Fi position"
            }
        Text(source, style = MaterialTheme.typography.titleLarge)
        FixLines(output)
        Line("Mock location", mockText(status))
    }
    Section("Sources") {
        val gnss = status.gnss
        Line("Satellites used / tracked", "${gnss.satellitesUsed} / ${gnss.tracked}")
        Line("Cell / Wi-Fi accuracy", status.lbs.fix?.let { "±%.0f m".format(it.horizontalAccuracyM) } ?: "—")
        Line("Cells / Wi-Fi used", "${status.lbs.cells} / ${status.lbs.wifiUsable}")
    }
    SetupHints(status)
}

@Composable
private fun SetupHints(status: LpsStatus) {
    val context = LocalContext.current
    if (status.mock == MockState.NOT_MOCK_APP) {
        Section("Setup needed", color = MaterialTheme.colorScheme.errorContainer) {
            Text("Select mobile-lps in Developer options → Select mock location app.")
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open developer options") }
        }
    }
    if (status.lbs.wifiThrottled) {
        Section("Wi-Fi scans are throttled") {
            Text(
                listOf(
                    "Android limits apps to 4 Wi-Fi scans per 2 minutes.",
                    "Disable Developer options → Wi-Fi scan throttling for faster updates.",
                ).joinToString(" "),
            )
        }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Section("Android 10–11") {
            Text("Enable Developer options → Force full GNSS measurements, otherwise measurements arrive with gaps.")
        }
    }
}

private fun stateTitle(state: SpoofingState?) =
    when (state) {
        SpoofingState.CLEAN -> "Clean"
        SpoofingState.SUSPECT -> "Suspicious"
        SpoofingState.SPOOFED -> "Spoofing detected"
        SpoofingState.NO_GNSS -> "No GNSS signal"
        null -> "Starting…"
    }

private fun mockText(status: LpsStatus) =
    when (status.mock) {
        MockState.ACTIVE -> "Active"
        MockState.INACTIVE -> "Inactive"
        MockState.NOT_MOCK_APP -> "Not selected as mock app"
        MockState.FAILED -> "Error: ${status.mockError}"
    }
