package dev.mobilelps.sources

import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import dev.mobilelps.core.RadioType

/** Maps platform [CellInfo] to [ReportedCell], turning "unavailable" markers into nulls. */
internal object CellInfoMapper {
    fun toReported(info: CellInfo): ReportedCell? =
        when (info) {
            is CellInfoLte -> lte(info)
            is CellInfoGsm -> gsm(info)
            is CellInfoWcdma -> wcdma(info)
            is CellInfoNr -> nr(info)
            else -> null
        }

    private fun lte(info: CellInfoLte): ReportedCell {
        val id = info.cellIdentity
        return ReportedCell(
            radio = RadioType.LTE,
            mcc = id.mccString?.toIntOrNull(),
            mnc = id.mncString?.toIntOrNull(),
            areaCode = id.tac.validArea(),
            cellId = id.ci.validId(),
            signalDbm = info.cellSignalStrength.dbm.valid(),
            timingAdvance = info.cellSignalStrength.timingAdvance.valid(),
            isServing = info.isRegistered,
            ageMillis = ageMillis(info),
        )
    }

    private fun gsm(info: CellInfoGsm): ReportedCell {
        val id = info.cellIdentity
        return ReportedCell(
            radio = RadioType.GSM,
            mcc = id.mccString?.toIntOrNull(),
            mnc = id.mncString?.toIntOrNull(),
            areaCode = id.lac.validArea(),
            cellId = id.cid.validId(),
            signalDbm = info.cellSignalStrength.dbm.valid(),
            timingAdvance = info.cellSignalStrength.timingAdvance.valid(),
            isServing = info.isRegistered,
            ageMillis = ageMillis(info),
        )
    }

    private fun wcdma(info: CellInfoWcdma): ReportedCell {
        val id = info.cellIdentity
        return ReportedCell(
            radio = RadioType.WCDMA,
            mcc = id.mccString?.toIntOrNull(),
            mnc = id.mncString?.toIntOrNull(),
            areaCode = id.lac.validArea(),
            cellId = id.cid.validId(),
            signalDbm = info.cellSignalStrength.dbm.valid(),
            timingAdvance = null,
            isServing = info.isRegistered,
            ageMillis = ageMillis(info),
        )
    }

    private fun nr(info: CellInfoNr): ReportedCell {
        val id = info.cellIdentity as CellIdentityNr
        return ReportedCell(
            radio = RadioType.NR,
            mcc = id.mccString?.toIntOrNull(),
            mnc = id.mncString?.toIntOrNull(),
            areaCode = id.tac.validArea(),
            cellId = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG && it > 0 },
            signalDbm = (info.cellSignalStrength as CellSignalStrengthNr).dbm.valid(),
            timingAdvance = null,
            isServing = info.isRegistered,
            ageMillis = ageMillis(info),
        )
    }

    private fun Int.validArea(): Int? = takeIf { it != CellInfo.UNAVAILABLE && it > 0 }

    private fun Int.validId(): Long? = takeIf { it != CellInfo.UNAVAILABLE && it > 0 }?.toLong()

    private fun ageMillis(info: CellInfo): Long {
        val timestampNanos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.timestampMillis * NANOS_PER_MILLI
            } else {
                @Suppress("DEPRECATION")
                info.timeStamp
            }
        return (SystemClock.elapsedRealtimeNanos() - timestampNanos) / NANOS_PER_MILLI
    }

    private fun Int.valid(): Int? = takeIf { it != CellInfo.UNAVAILABLE }

    private const val NANOS_PER_MILLI = 1_000_000L
}
