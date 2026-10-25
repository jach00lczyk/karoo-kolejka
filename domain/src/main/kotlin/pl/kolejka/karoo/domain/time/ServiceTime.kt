package pl.kolejka.karoo.domain.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Czasy GTFS są liczone od „południa minus 12 h" dnia kursowania (SPEC 6.7). Dzięki temu
 * dni zmiany czasu i kursy po północy (czasy > 24:00:00) przeliczają się poprawnie.
 */
object ServiceTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Feed ma czasy do ~42 h; dwa dni wstecz pokrywają każdy kurs trwający w danej chwili. */
    private const val LOOKBACK_DAYS = 2L

    fun origin(serviceDate: LocalDate): Instant =
        ZonedDateTime.of(serviceDate, LocalTime.NOON, ZONE).minusHours(12).toInstant()

    fun toInstant(serviceDate: LocalDate, seconds: Int): Instant = origin(serviceDate).plusSeconds(seconds.toLong())

    fun toSeconds(serviceDate: LocalDate, instant: Instant): Long = Duration.between(origin(serviceDate), instant).seconds

    fun localDate(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    /**
     * Dni kursowania, których kursy mogą odjeżdżać w przedziale [from, to], razem z przedziałem
     * sekund do zapytania. Przedziały z ujemnym końcem są pomijane.
     */
    fun windows(from: Instant, to: Instant): List<Pair<LocalDate, IntRange>> {
        val first = localDate(from).minusDays(LOOKBACK_DAYS)
        val last = localDate(to)
        val out = ArrayList<Pair<LocalDate, IntRange>>()
        var d = first
        while (!d.isAfter(last)) {
            val a = toSeconds(d, from).coerceAtLeast(0)
            val b = toSeconds(d, to)
            if (b >= 0 && a <= b) out += d to (a.toInt()..b.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            d = d.plusDays(1)
        }
        return out
    }
}
