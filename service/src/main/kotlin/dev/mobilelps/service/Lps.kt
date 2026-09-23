package dev.mobilelps.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Entry point for the UI: starts and stops the service and exposes its status. */
object Lps {
    internal val mutableStatus = MutableStateFlow(LpsStatus())
    val status: StateFlow<LpsStatus> = mutableStatus.asStateFlow()

    /** The caller must hold ACCESS_FINE_LOCATION; the service stops itself otherwise. */
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, LpsService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, LpsService::class.java).setAction(LpsService.ACTION_STOP))
    }
}
