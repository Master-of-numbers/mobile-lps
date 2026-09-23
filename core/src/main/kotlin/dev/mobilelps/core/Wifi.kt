package dev.mobilelps.core

/** One Wi-Fi access point from a scan. */
data class WifiAccessPoint(
    /** BSSID in lower case, colon separated. */
    val bssid: String,
    val ssid: String?,
    val signalDbm: Int,
    val frequencyMhz: Int,
    /** Age of the observation in milliseconds, if known. */
    val ageMillis: Long?,
)

data class WifiScan(
    val accessPoints: List<WifiAccessPoint>,
    val elapsedRealtimeNanos: Long,
    /** False when the platform rejected the scan request (throttling); results then come from system scans. */
    val scanRequestAccepted: Boolean,
)
