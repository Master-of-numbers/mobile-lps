package dev.mobilelps.sources

import dev.mobilelps.core.CellTower
import dev.mobilelps.core.RadioType

/** A cell as reported by the modem; neighbours often lack parts of the identity. */
internal data class ReportedCell(
    val radio: RadioType,
    val mcc: Int?,
    val mnc: Int?,
    val areaCode: Int?,
    val cellId: Long?,
    val signalDbm: Int?,
    val timingAdvance: Int?,
    val isServing: Boolean,
    val ageMillis: Long?,
)

/** Turns modem reports into cells with a complete global identity. */
internal object CellIdentities {
    /**
     * GSM/WCDMA neighbours usually carry LAC and CID but no MCC/MNC; they belong to the operator of the serving
     * cell of the same radio type. LTE/NR neighbours carry only PCI and cannot be completed.
     */
    fun complete(reported: List<ReportedCell>): List<CellTower> {
        val servingOperator =
            reported
                .filter { it.isServing && it.mcc != null && it.mnc != null }
                .associate { it.radio to (it.mcc!! to it.mnc!!) }
        return reported
            .mapNotNull { cell ->
                val operator =
                    if (cell.mcc != null &&
                        cell.mnc != null
                    ) {
                        cell.mcc to cell.mnc
                    } else {
                        servingOperator[cell.radio]
                    }
                val area = cell.areaCode?.takeIf { it in 1..maxArea(cell.radio) }
                val id = cell.cellId?.takeIf { it in 1..maxCellId(cell.radio) }
                if (operator == null || area == null || id == null) return@mapNotNull null
                CellTower(
                    radio = cell.radio,
                    mcc = operator.first,
                    mnc = operator.second,
                    areaCode = area,
                    cellId = id,
                    signalDbm = cell.signalDbm,
                    timingAdvance = cell.timingAdvance,
                    isServing = cell.isServing,
                    ageMillis = cell.ageMillis,
                )
            }
            // The same cell can come from both the fresh scan and the cached list of all modems.
            .groupBy { listOf(it.radio, it.mcc, it.mnc, it.areaCode, it.cellId) }
            .map { (_, duplicates) -> duplicates.minBy { it.ageMillis ?: Long.MAX_VALUE } }
    }

    /**
     * Largest valid area code. The all-ones value of the field (e.g. 0xFFFF) is what some modems report instead
     * of `CellInfo.UNAVAILABLE`, so it is excluded.
     */
    private fun maxArea(radio: RadioType): Int =
        when (radio) {
            RadioType.NR -> NR_TAC_ALL_ONES - 1
            else -> LAC_TAC_ALL_ONES - 1
        }

    private fun maxCellId(radio: RadioType): Long =
        when (radio) {
            RadioType.GSM -> GSM_CID_ALL_ONES - 1
            RadioType.WCDMA, RadioType.LTE -> CI_28_BIT_ALL_ONES - 1
            RadioType.NR -> NCI_36_BIT_ALL_ONES - 1
        }

    private const val LAC_TAC_ALL_ONES = 0xFFFF
    private const val NR_TAC_ALL_ONES = 0xFFFFFF
    private const val GSM_CID_ALL_ONES = 0xFFFFL
    private const val CI_28_BIT_ALL_ONES = 0xFFFFFFFL
    private const val NCI_36_BIT_ALL_ONES = 0xFFFFFFFFFL
}
