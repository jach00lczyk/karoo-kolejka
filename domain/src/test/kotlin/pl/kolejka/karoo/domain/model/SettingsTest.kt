package pl.kolejka.karoo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {
    @Test
    fun `targets round trip for 1, 3 and 5 groups`() {
        for (t in listOf(
            listOf(setOf(36103)),
            listOf(setOf(36103), setOf(37440), setOf(33571, 14357927)),
            listOf(setOf(1), setOf(2), setOf(3, 4), setOf(5), setOf(6)),
        )) {
            assertEquals(t, Settings.decodeTargets(Settings.encodeTargets(t)))
        }
    }

    @Test
    fun `broken targets string means defaults`() {
        assertNull(Settings.decodeTargets(""))
        assertNull(Settings.decodeTargets("|,|x"))
        assertEquals(listOf(setOf(5)), Settings.decodeTargets("x|5"))
    }
}
