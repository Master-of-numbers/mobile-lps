package dev.mobilelps.core

enum class RadioType { GSM, WCDMA, LTE, NR }

/** One observed cell with a fully known global identity. */
data class CellTower(
    val radio: RadioType,
    val mcc: Int,
    val mnc: Int,
    /** LAC for GSM/WCDMA, TAC for LTE/NR. */
    val areaCode: Int,
    /** CID, UTRAN CID, ECI or NCI. */
    val cellId: Long,
    val signalDbm: Int?,
    val timingAdvance: Int?,
    val isServing: Boolean,
    /** Age of the observation in milliseconds, if known. */
    val ageMillis: Long?,
)

data class CellScan(
    /** Cells with a complete global identity, usable for lookups. */
    val cells: List<CellTower>,
    val elapsedRealtimeNanos: Long,
    /** All cells the modems reported, including neighbours known only by PCI/PSC. */
    val visibleCells: Int = cells.size,
)
