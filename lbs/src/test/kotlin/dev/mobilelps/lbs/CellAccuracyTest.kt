package dev.mobilelps.lbs

import com.google.common.truth.Truth.assertThat
import dev.mobilelps.core.CellTower
import dev.mobilelps.core.RadioType
import org.junit.Test

class CellAccuracyTest {
    private val gsm = CellTower(RadioType.GSM, 255, 3, 100, 200, -70, null, true, null)

    @Test
    fun `the best radio type among the cells sets the floor`() {
        assertThat(CellAccuracy.floorM(listOf(gsm))).isEqualTo(3_000.0)
        assertThat(CellAccuracy.floorM(listOf(gsm, gsm.copy(radio = RadioType.LTE)))).isEqualTo(1_500.0)
        assertThat(CellAccuracy.floorM(emptyList())).isEqualTo(0.0)
    }
}
