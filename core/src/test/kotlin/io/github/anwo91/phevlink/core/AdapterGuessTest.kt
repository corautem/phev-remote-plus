package io.github.anwo91.phevlink.core

import io.github.anwo91.phevlink.core.obd.AdapterGuess
import io.github.anwo91.phevlink.core.obd.AdapterGuess.Major
import io.github.anwo91.phevlink.core.obd.AdapterGuess.Rank
import org.junit.Assert.assertEquals
import org.junit.Test

class AdapterGuessTest {
    private fun rank(name: String?, major: Int? = null, services: List<String> = emptyList(), companies: List<Int> = emptyList()) =
        AdapterGuess.guess(name, major, services, companies).rank

    @Test
    fun adapterNamesComeFirst() {
        for (n in listOf("OBDII", "OBDLink MX+", "Android-Vlink", "IOS-Vlink", "V-LINK", "Vgate iCar Pro", "KONNWEI", "Veepeak OBDCheck BLE")) {
            assertEquals(n, Rank.ADAPTER, rank(n, Major.UNCATEGORIZED))
        }
    }

    @Test
    fun fieldLogAutelToolIsProprietary() {
        val g = AdapterGuess.guess("Maxi-CFJ2LAV02885", null, listOf("49535343-fe7d-4ae5-8fa9-9fafd205e455"))
        assertEquals(Rank.PROPRIETARY, g.rank)
        assertEquals("Autel", g.brand)
        assertEquals(Rank.PROPRIETARY, rank("X431 PRO"))
        // "MAXI" only counts at the start of the name.
        assertEquals(Rank.UNKNOWN, rank("Minimaxi Lamp"))
    }

    @Test
    fun phonesHeadphonesAndWatchesAreHidden() {
        assertEquals(Rank.NOT_ADAPTER, rank("Andreas sin telefon", Major.PHONE))
        assertEquals(Rank.NOT_ADAPTER, rank("WH-1000XM4", Major.AUDIO_VIDEO))
        assertEquals(Rank.NOT_ADAPTER, rank("Car Multimedia", Major.AUDIO_VIDEO))
        assertEquals(Rank.NOT_ADAPTER, rank("LE-Bose", null, companies = listOf(0x009E)))
        assertEquals(Rank.NOT_ADAPTER, rank("Someone's AirPods"))
        assertEquals(Rank.NOT_ADAPTER, rank("abc", null, companies = listOf(0x004C)))
        assertEquals(Rank.NOT_ADAPTER, rank("Polar H10", null, services = listOf("0000180d-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun genericModulesAndUartServicesAreLikely() {
        assertEquals(Rank.LIKELY, rank("HC-05", Major.UNCATEGORIZED))
        assertEquals(Rank.LIKELY, rank("SPP-CA", Major.UNCATEGORIZED))
        assertEquals(Rank.LIKELY, rank("BLE Device", null, services = listOf("0000fff0-0000-1000-8000-00805f9b34fb")))
        assertEquals(Rank.UNKNOWN, rank("Something", Major.UNCATEGORIZED))
        assertEquals(Rank.UNKNOWN, rank(null))
    }

    @Test
    fun adapterNameWinsOverOddClass() {
        // Some clones report odd classes; a clear adapter name keeps them visible.
        assertEquals(Rank.ADAPTER, rank("OBDII", Major.COMPUTER))
    }
}
