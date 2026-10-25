package pl.kolejka.karoo.domain.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Wartości z logu jazdy 25.10.2026 (Karoo 3). */
class KarooUnitsTest {
    private val now = Instant.parse("2026-10-25T08:56:00.477Z")

    @Test
    fun `time of arrival is epoch millis`() {
        assertEquals(Instant.parse("2026-10-25T13:38:21.459Z"), KarooUnits.etaFromTimeOfArrival(1.792935501459457E12))
        assertNull(KarooUnits.etaFromTimeOfArrival(52_000.0))
        assertNull(KarooUnits.etaFromTimeOfArrival(null))
        assertNull(KarooUnits.etaFromTimeOfArrival(Double.NaN))
    }

    @Test
    fun `time to destination is millis`() {
        // 1,694e7 ms ≈ 4 h 42 min — zgodne z TIME_OF_ARRIVAL z tej samej chwili
        val eta = KarooUnits.etaFromTimeToDestination(1.6942875854850687E7, now)!!
        assertEquals(Instant.parse("2026-10-25T13:38:23Z").epochSecond.toDouble(), eta.epochSecond.toDouble(), 5.0)
        assertNull(KarooUnits.etaFromTimeToDestination(0.0, now))
    }

    @Test
    fun `distance in meters, may exceed route length when far from route`() {
        assertEquals(107_065.66, KarooUnits.distanceM(107_065.66)!!, 0.01)
        assertEquals(77_945.6, KarooUnits.distanceM(77_945.6)!!, 0.01) // trasa 46 km, rowerzysta w domu
        assertNull(KarooUnits.distanceM(2_000_000.0))
        assertNull(KarooUnits.distanceM(-1.0))
        assertNull(KarooUnits.distanceM(Double.NaN))
    }

    @Test
    fun `speed in m per s`() {
        assertEquals(5.54, KarooUnits.speedMps(5.54)!!, 0.0)
        assertNull(KarooUnits.speedMps(80.0))
        assertNull(KarooUnits.speedMps(null))
    }
}
