package pl.kolejka.karoo.domain.delay

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class DelayPolicyTest {
    private val t0 = Instant.parse("2026-11-03T16:00:00Z")
    private val day = LocalDate.of(2026, 11, 3)

    private fun train(oid: Long?, depMin: Long) = Train(
        tripId = oid?.toInt() ?: 0, serviceDate = day, fromStationId = 1, departure = t0.plusSeconds(depMin * 60),
        platform = null, track = null, agency = "KM", routeShortName = "R2", isBus = false, labelGroup = 0,
        labelStationId = 10, labelArrival = t0.plusSeconds(depMin * 60 + 2400), earliestArrival = t0.plusSeconds(depMin * 60 + 2400),
        ref = oid?.let { TrainRef(2026, it, day) },
    )

    private fun min(m: Long) = t0.plus(Duration.ofMinutes(m))
    private fun List<TrainRef>.oids() = map { it.oid }

    @Test
    fun `automatic fetch covers all trains with progressive intervals`() {
        val p = DelayPolicy()
        // 20 min → co 5 min, 50 min → co 10 min, 200 min → co 30 min, bez ID kursu → nigdy
        val trains = listOf(train(1, 20), train(2, 50), train(3, 200), train(null, 30))
        assertEquals(listOf(1L, 2L, 3L), p.dueAutomatic(trains, t0).oids())
        assertEquals(emptyList<Long>(), p.dueAutomatic(trains, min(4)).oids())
        assertEquals(listOf(1L), p.dueAutomatic(trains, min(5)).oids())
        assertEquals(listOf(1L, 2L), p.dueAutomatic(trains, min(10)).oids())
        assertEquals(listOf(1L), p.dueAutomatic(trains, min(15)).oids())
        assertEquals(listOf(1L, 2L), p.dueAutomatic(trains, min(20)).oids())
        // pociąg 1 odjechał ponad 5 min temu; pociąg 2 (za 25 min) przechodzi na co 5 min
        assertEquals(listOf(2L), p.dueAutomatic(trains, min(26)).oids())
        assertEquals(listOf(3L), p.dueAutomatic(trains, min(30)).oids())
        assertEquals(listOf(2L), p.dueAutomatic(trains, min(31)).oids())
    }

    @Test
    fun `train approaching departure switches to shorter interval`() {
        val p = DelayPolicy()
        val trains = listOf(train(1, 100)) // za 100 min → co 30 min
        assertEquals(1, p.dueAutomatic(trains, t0).size)
        assertEquals(0, p.dueAutomatic(trains, min(9)).size) // za 91 min, wciąż co 30 min
        assertEquals(1, p.dueAutomatic(trains, min(11)).size) // za 89 min → co 10 min
        assertEquals(0, p.dueAutomatic(trains, min(20)).size)
        assertEquals(1, p.dueAutomatic(trains, min(21)).size)
        assertEquals(1, p.dueAutomatic(trains, min(75)).size) // za 25 min → co 5 min
        assertEquals(0, p.dueAutomatic(trains, min(79)).size)
        assertEquals(1, p.dueAutomatic(trains, min(80)).size)
    }

    @Test
    fun `route change makes all shown trains due at once except just fetched`() {
        val p = DelayPolicy()
        assertEquals(true, p.onRoute("A", t0))
        val old = listOf(train(1, 200))
        assertEquals(1, p.dueAutomatic(old, t0).size)
        val fresh = listOf(train(1, 200), train(2, 150))
        assertEquals(listOf(2L), p.dueAutomatic(fresh, min(3)).oids())
        assertEquals(true, p.onRoute("B", min(3).plusSeconds(30)))
        // pociąg 2 pobrany 30 s temu zostaje, pociąg 1 (3,5 min temu) wraca do kolejki
        assertEquals(listOf(1L), p.dueAutomatic(fresh, min(4)).oids())
        assertEquals(true, p.onRoute("A", min(6)))
        assertEquals(listOf(1L, 2L).sorted(), p.dueAutomatic(fresh, min(6)).oids().sorted())
    }

    @Test
    fun `same route again does not force a check`() {
        val p = DelayPolicy()
        val trains = listOf(train(1, 200))
        p.onRoute("A", t0)
        assertEquals(1, p.dueAutomatic(trains, t0).size)
        // reroute / ponowne podłączenie kafelka → ta sama trasa
        assertEquals(false, p.onRoute("A", min(5)))
        assertEquals(0, p.dueAutomatic(trains, min(5)).size)
    }

    @Test
    fun `manual check takes up to six nearest trains with one minute cooldown`() {
        val p = DelayPolicy()
        val trains = (1L..8L).map { train(it, it * 3) }
        assertEquals((1L..6L).toList(), p.dueManual(trains, t0).oids())
        assertEquals(emptyList<Long>(), p.dueManual(trains, t0.plusSeconds(30)).oids())
        assertEquals((1L..6L).toList(), p.dueManual(trains, min(1)).oids())
        // automat nie dubluje świeżo sprawdzonych, dobiera tylko te poza limitem przycisku
        assertEquals(listOf(7L, 8L), p.dueAutomatic(trains, min(2)).oids())
    }

    @Test
    fun `hourly budget caps requests`() {
        val p = DelayPolicy(maxPerHour = 5)
        val trains = (1L..4L).map { train(it, it * 10) }
        assertEquals(4, p.dueAutomatic(trains, t0).size)
        assertEquals(1, p.dueAutomatic(trains, min(10)).size)
        assertEquals(0, p.budget(min(20)))
        assertEquals(0, p.dueManual(trains, min(20)).size)
        assertEquals(5, p.budget(min(71)))
    }

    @Test
    fun `clock moved back does not trigger refetch`() {
        val p = DelayPolicy()
        val trains = listOf(train(1, 20))
        assertEquals(1, p.dueAutomatic(trains, t0).size)
        assertEquals(0, p.dueAutomatic(trains, t0.minusSeconds(30)).size)
    }

    @Test
    fun `manual candidates tell nothing to check from checked a moment ago`() {
        val p = DelayPolicy()
        assertEquals(false, p.hasManualCandidates(listOf(train(1, -10), train(null, 30)), t0))
        assertEquals(true, p.hasManualCandidates(listOf(train(1, 300)), t0))
        val soon = listOf(train(1, 30))
        assertEquals(1, p.dueManual(soon, t0).size)
        assertEquals(0, p.dueManual(soon, t0.plusSeconds(20)).size)
        assertEquals(true, p.hasManualCandidates(soon, t0.plusSeconds(20)))
    }
}
