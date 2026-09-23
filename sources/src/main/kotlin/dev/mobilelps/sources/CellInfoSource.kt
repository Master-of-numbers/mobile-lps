package dev.mobilelps.sources

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import androidx.core.content.getSystemService
import dev.mobilelps.core.CellScan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Periodically scans visible cells. Only cells with a complete global identity are emitted. */
class CellInfoSource(
    context: Context,
) {
    private val telephony = context.getSystemService<TelephonyManager>()
    private val executor = Executors.newSingleThreadExecutor()

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun scans(interval: Duration = DEFAULT_INTERVAL): Flow<CellScan> =
        flow {
            val manager = telephony ?: return@flow
            while (true) {
                // requestCellInfoUpdate refreshes only the default SIM's modem; the cached list covers every modem,
                // so a second SIM contributes its serving cell as well.
                val infos = requestUpdate(manager) + manager.allCellInfo.orEmpty()
                val reported = infos.mapNotNull(CellInfoMapper::toReported)
                val cells = CellIdentities.complete(reported)
                val visible = reported.distinctBy { listOf(it.radio, it.mcc, it.mnc, it.areaCode, it.cellId) }.size
                emit(CellScan(cells, SystemClock.elapsedRealtimeNanos(), visible))
                delay(interval)
            }
        }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestUpdate(manager: TelephonyManager): List<CellInfo> =
        suspendCancellableCoroutine { continuation ->
            manager.requestCellInfoUpdate(
                executor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (continuation.isActive) continuation.resume(cellInfo)
                    }

                    override fun onError(
                        errorCode: Int,
                        detail: Throwable?,
                    ) {
                        // A failed scan is skipped; the next one follows after the scan interval.
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                },
            )
        }

    private companion object {
        val DEFAULT_INTERVAL = 10.seconds
    }
}
