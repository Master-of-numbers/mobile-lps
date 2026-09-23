package dev.mobilelps.sources

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.RadioType
import org.junit.Test

class CellIdentitiesTest {
    private val serving = ReportedCell(RadioType.GSM, 255, 3, 100, 200, -70, 2, isServing = true, ageMillis = 0)

    @Test
    fun `gsm neighbours inherit the operator of the serving cell`() {
        val neighbour = serving.copy(mcc = null, mnc = null, cellId = 201, isServing = false)

        val cells = CellIdentities.complete(listOf(serving, neighbour))

        assertThat(cells.map { it.cellId }).containsExactly(200L, 201L)
        assertThat(cells.last().mcc).isEqualTo(255)
        assertThat(cells.last().mnc).isEqualTo(3)
    }

    @Test
    fun `lte neighbours known only by pci are dropped`() {
        val lteServing = serving.copy(radio = RadioType.LTE, cellId = 12_345_678)
        val pciOnly = lteServing.copy(mcc = null, mnc = null, areaCode = null, cellId = null, isServing = false)

        assertThat(CellIdentities.complete(listOf(lteServing, pciOnly))).hasSize(1)
    }

    @Test
    fun `operator is not borrowed across radio types`() {
        val umtsNeighbour = serving.copy(radio = RadioType.WCDMA, mcc = null, mnc = null, isServing = false)

        assertThat(CellIdentities.complete(listOf(serving, umtsNeighbour))).hasSize(1)
    }

    @Test
    fun `keeps both sims serving cells and removes duplicates`() {
        val secondSim = serving.copy(radio = RadioType.LTE, mnc = 1, areaCode = 5, cellId = 999)
        val cachedCopy = serving.copy(ageMillis = 5_000)

        val cells = CellIdentities.complete(listOf(serving, secondSim, cachedCopy))

        assertThat(cells).hasSize(2)
        assertThat(cells.first { it.cellId == 200L }.ageMillis).isEqualTo(0)
    }

    @Test
    fun `all-ones placeholders reported by some modems are dropped`() {
        val lte = serving.copy(radio = RadioType.LTE, areaCode = 1234, cellId = 12_345_678)
        val placeholder = lte.copy(areaCode = 65535, cellId = 268_435_455, isServing = false)
        val placeholderId = lte.copy(cellId = 268_435_455, isServing = false)

        assertThat(
            CellIdentities.complete(listOf(lte, placeholder, placeholderId)).map {
                it.cellId
            },
        ).containsExactly(12_345_678L)
    }
}
