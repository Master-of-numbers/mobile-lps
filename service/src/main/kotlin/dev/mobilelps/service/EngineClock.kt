package dev.mobilelps.service

/** Time sources used by the engine, replaceable in tests. */
interface EngineClock {
    fun elapsedRealtimeNanos(): Long

    fun wallMillis(): Long
}
