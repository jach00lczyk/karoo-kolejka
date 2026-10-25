package pl.kolejka.karoo.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ServiceTimeTest {
    private fun local(date: LocalDate, s: Int) = ServiceTime.toInstant(date, s).atZone(ServiceTime.ZONE).toLocalDateTime()

    @Test
    fun `normal day maps gtfs seconds to local time`() {
        val d = LocalDate.of(2026, 10, 26)
        assertEquals(LocalDateTime.of(d, LocalTime.of(17, 42)), local(d, 17 * 3600 + 42 * 60))
    }

    @Test
    fun `spring forward day keeps timetable times`() {
        val d = LocalDate.of(2026, 3, 29) // CET → CEST o 02:00
        assertEquals(LocalDateTime.of(d, LocalTime.of(8, 0)), local(d, 8 * 3600))
        assertEquals(LocalDateTime.of(d, LocalTime.of(23, 50)), local(d, 23 * 3600 + 50 * 60))
    }

    @Test
    fun `fall back day keeps timetable times`() {
        val d = LocalDate.of(2026, 10, 25) // CEST → CET o 03:00
        assertEquals(LocalDateTime.of(d, LocalTime.of(8, 0)), local(d, 8 * 3600))
        assertEquals(LocalDateTime.of(d, LocalTime.of(22, 47)), local(d, 22 * 3600 + 47 * 60))
    }

    @Test
    fun `trip after midnight lands on next calendar day`() {
        val d = LocalDate.of(2026, 10, 31)
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 20), local(d, 24 * 3600 + 20 * 60))
    }

    @Test
    fun `windows include previous service day for after midnight departures`() {
        val from = LocalDateTime.of(2026, 11, 1, 0, 10).atZone(ServiceTime.ZONE).toInstant()
        val to = from.plusSeconds(3600)
        val w = ServiceTime.windows(from, to).toMap()
        val prev = w[LocalDate.of(2026, 10, 31)]!!
        assertTrue(24 * 3600 + 20 * 60 in prev)
        val same = w[LocalDate.of(2026, 11, 1)]!!
        assertEquals(10 * 60, same.first)
        assertEquals(70 * 60, same.last)
    }

    @Test
    fun `round trip seconds and instant`() {
        val d = LocalDate.of(2026, 3, 29)
        val i = ServiceTime.toInstant(d, 40_000)
        assertEquals(40_000L, ServiceTime.toSeconds(d, i))
    }
}
