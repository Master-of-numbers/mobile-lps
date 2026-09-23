package dev.mobilelps.service

import dev.mobilelps.core.PositionFix

/** Destination of the chosen position: the mock location providers in production. */
interface LocationSink {
    fun start(): SinkResult

    fun publish(fix: PositionFix): SinkResult

    fun stop()
}

data class SinkResult(
    val state: MockState,
    val error: String? = null,
)
