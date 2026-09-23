package dev.mobilelps.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mobilelps.core.PositionFix
import dev.mobilelps.detector.SpoofingState
import kotlinx.coroutines.delay

@Composable
internal fun Section(
    title: String,
    color: Color? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (color != null) CardDefaults.cardColors(containerColor = color) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun Line(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun FixLines(fix: PositionFix?) {
    if (fix == null) return
    Line("Position", "%.6f, %.6f".format(fix.latDeg, fix.lonDeg))
    Line("Accuracy", "±%.0f m".format(fix.horizontalAccuracyM))
    fix.speedMps?.let { Line("Speed", "%.1f km/h".format(it * MPS_TO_KMH)) }
}

@Composable
internal fun stateColor(state: SpoofingState?): Color? =
    when (state) {
        SpoofingState.CLEAN -> MaterialTheme.colorScheme.primaryContainer
        SpoofingState.SUSPECT -> MaterialTheme.colorScheme.tertiaryContainer
        SpoofingState.SPOOFED, SpoofingState.NO_GNSS -> MaterialTheme.colorScheme.errorContainer
        null -> null
    }

/** Current `elapsedRealtimeNanos`, refreshed every second so ages stay live. */
@Composable
internal fun rememberElapsedNanos(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MILLIS)
            now = SystemClock.elapsedRealtimeNanos()
        }
    }
    return now
}

internal fun age(
    elapsedNanos: Long?,
    now: Long,
): String = if (elapsedNanos == null) "never" else "%.1f s ago".format((now - elapsedNanos) / NANOS_PER_SECOND)

private const val TICK_MILLIS = 1_000L
private const val NANOS_PER_SECOND = 1e9
private const val MPS_TO_KMH = 3.6
