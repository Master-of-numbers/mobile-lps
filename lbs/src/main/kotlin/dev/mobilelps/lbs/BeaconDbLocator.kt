package dev.mobilelps.lbs

import dev.mobilelps.core.CellTower
import dev.mobilelps.core.HttpClient
import dev.mobilelps.core.RadioType
import dev.mobilelps.core.WifiAccessPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * [NetworkLocator] backed by the BeaconDB geolocation API (Ichnaea / Google Geolocation API compatible).
 * SSIDs are never sent, and `considerIp` is disabled so the server never falls back to IP geolocation.
 */
class BeaconDbLocator(
    private val http: HttpClient,
    private val endpoint: String = "https://api.beacondb.net/v1/geolocate",
) : NetworkLocator {
    private val json =
        Json {
            ignoreUnknownKeys = true
            // `considerIp = false` must be sent explicitly: the server default is true.
            encodeDefaults = true
            explicitNulls = false
        }

    override fun locate(query: NetworkQuery): LbsResult {
        if (query.isEmpty) return LbsResult.NotFound
        val request =
            Request(
                cellTowers = query.cells.map(::toRequestCell).ifEmpty { null },
                wifiAccessPoints = query.wifi.map(::toRequestAccessPoint).ifEmpty { null },
            )
        val response =
            try {
                http.postJson(endpoint, json.encodeToString(Request.serializer(), request))
            } catch (e: IOException) {
                return LbsResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        return when (response.code) {
            HTTP_OK -> parse(response.body.decodeToString())
            HTTP_NOT_FOUND -> LbsResult.NotFound
            else -> LbsResult.Failed("HTTP ${response.code}")
        }
    }

    private fun parse(text: String): LbsResult =
        runCatching { json.decodeFromString(Response.serializer(), text) }
            .fold(
                onSuccess = { LbsResult.Found(LbsLocation(it.location.lat, it.location.lng, it.accuracy)) },
                onFailure = { LbsResult.Failed("Malformed response: ${it.message}") },
            )

    private fun toRequestCell(cell: CellTower) =
        RequestCell(
            radioType =
                when (cell.radio) {
                    RadioType.GSM -> "gsm"
                    RadioType.WCDMA -> "wcdma"
                    RadioType.LTE -> "lte"
                    RadioType.NR -> "nr"
                },
            mobileCountryCode = cell.mcc,
            mobileNetworkCode = cell.mnc,
            locationAreaCode = cell.areaCode,
            cellId = cell.cellId,
            signalStrength = cell.signalDbm,
            timingAdvance = cell.timingAdvance,
            age = cell.ageMillis,
            serving = if (cell.isServing) 1 else 0,
        )

    private fun toRequestAccessPoint(ap: WifiAccessPoint) =
        RequestAccessPoint(
            macAddress = ap.bssid,
            signalStrength = ap.signalDbm,
            frequency = ap.frequencyMhz,
            age = ap.ageMillis,
        )

    @Serializable
    private data class Request(
        val considerIp: Boolean = false,
        val cellTowers: List<RequestCell>?,
        val wifiAccessPoints: List<RequestAccessPoint>?,
    )

    @Serializable
    private data class RequestCell(
        val radioType: String,
        val mobileCountryCode: Int,
        val mobileNetworkCode: Int,
        val locationAreaCode: Int,
        val cellId: Long,
        val signalStrength: Int?,
        val timingAdvance: Int?,
        val age: Long?,
        val serving: Int,
    )

    @Serializable
    private data class RequestAccessPoint(
        val macAddress: String,
        val signalStrength: Int,
        val frequency: Int,
        val age: Long?,
    )

    @Serializable
    private data class Response(
        val location: LatLng,
        val accuracy: Double,
    )

    @Serializable
    private data class LatLng(
        val lat: Double,
        val lng: Double,
    )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}
