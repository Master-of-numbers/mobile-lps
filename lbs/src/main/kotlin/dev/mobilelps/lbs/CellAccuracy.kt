package dev.mobilelps.lbs

import dev.mobilelps.core.CellTower
import dev.mobilelps.core.RadioType

/**
 * Realistic accuracy of a position derived from cells alone. BeaconDB can report tens of meters for a cell it has
 * seen only a few times, which is far from the true cell radius.
 */
object CellAccuracy {
    fun floorM(cells: List<CellTower>): Double =
        cells.minOfOrNull {
            when (it.radio) {
                RadioType.GSM -> GSM_M
                RadioType.WCDMA -> WCDMA_M
                RadioType.LTE -> LTE_M
                RadioType.NR -> NR_M
            }
        } ?: 0.0

    private const val GSM_M = 3_000.0
    private const val WCDMA_M = 2_000.0
    private const val LTE_M = 1_500.0
    private const val NR_M = 1_000.0
}
