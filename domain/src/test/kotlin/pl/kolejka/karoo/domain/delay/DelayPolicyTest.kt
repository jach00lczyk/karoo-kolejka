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
    fun `automatic fetch only for trains within 90 minutes and then every 10 minutes`() {
        val p = DelayPolicy()
        val trains = listOf(train(1, 20), train(2, 50), train(3, 120), train(null, 30))
        assertEquals(listOf(1L, 2L), p.dueAutomatic(trains, t0).oids())
        assertEquals(emptyList<Long>(), p.dueAutomatic(trains, min(1)).oids())
        assertEquals(emptyList<Long>(), p.dueAutomatic(trains, min(9)).oids())
        assertEquals(listOf(1L, 2L), p.dueAutomatic(trains, min(10)).oids())
        // 40 min później pociąg 3 wchodzi w horyzont, pociąg 1 odjechał ponad 5 min temu
        assertEquals(listOf(2L, 3L), p.dueAutomatic(trains, min(40)).oids())
    }

    @Test
    fun `manual check takes up to six nearest trains with one minute cooldown`() {
        val p = DelayPolicy()
        val trains = (1L..8L).map { train(it, it * 20) }
        assertEquals((1L..6L).toList(), p.dueManual(trains, t0).oids())
        assertEquals(emptyList<Long>(), p.dueManual(trains, t0.plusSeconds(30)).oids())
        assertEquals((1L..6L).toList(), p.dueManual(trains, min(1)).oids())
        // automat nie dubluje świeżo sprawdzonych
        assertEquals(emptyList<Long>(), p.dueAutomatic(trains, min(2)).oids())
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
}
