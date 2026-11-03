package pl.kolejka.karoo.domain.delay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import pl.kolejka.karoo.domain.time.ServiceTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class TrainRunTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val ref = TrainRef(2026, 868475983, day)
    private val fetched = Instant.parse("2026-11-03T19:35:00Z")
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(day, java.time.LocalTime.of(h, m, s)).atZone(ServiceTime.ZONE).toInstant()

    // Skrócona odpowiedź z 3.11.2026 (pociąg w drodze, +22 min, z dublem postoju jak w API).
    private val body = """
        {"scheduleId":2026,"orderId":868475983,"trainOrderId":982926673,"operatingDate":"2026-11-03","trainStatus":"P",
         "stations":[
          {"stationId":33506,"plannedSequenceNumber":3,"actualSequenceNumber":3,"actualArrival":"2026-11-03T19:57:30","actualDeparture":"2026-11-03T19:57:30","isConfirmed":true},
          {"stationId":33571,"plannedSequenceNumber":6,"actualSequenceNumber":6,"actualArrival":"2026-11-03T20:02:30","actualDeparture":"2026-11-03T20:04:00"},
          {"stationId":33571,"plannedSequenceNumber":6,"actualSequenceNumber":7,"actualArrival":"2026-11-03T20:02:30","actualDeparture":"2026-11-03T20:04:00"},
          {"stationId":39008,"plannedSequenceNumber":25,"actualSequenceNumber":25,"actualArrival":"2026-11-03T20:50:06","actualDeparture":"2026-11-03T20:51:06"},
          {"stationId":39404,"plannedSequenceNumber":38,"actualSequenceNumber":38,"actualArrival":"2026-11-03T21:31:06","isCancelled":true}
         ]}
    """.trimIndent()

    private fun train(from: Int, to: Int, dep: Instant) = Train(
        tripId = 1, serviceDate = day, fromStationId = from, departure = dep, platform = null, track = null,
        agency = "KM", routeShortName = "R2", isBus = false, labelGroup = 0, labelStationId = to,
        labelArrival = dep.plusSeconds(2700), earliestArrival = dep.plusSeconds(2700), ref = ref,
    )

    @Test
    fun `parses local times and flags`() {
        val run = TrainRun.parse(body, ref, fetched)!!
        assertEquals("P", run.status)
        assertEquals(5, run.stops.size)
        assertEquals(at(19, 57, 30), run.stops[0].departure)
        assertTrue(run.stops[0].confirmed)
        assertFalse(run.stops[1].confirmed)
        assertNull(run.stops[4].departure)
        assertTrue(run.stops[4].cancelled)
    }

    @Test
    fun `rejects response for another train or date`() {
        assertNull(TrainRun.parse(body, ref.copy(oid = 1), fetched))
        assertNull(TrainRun.parse(body, ref.copy(serviceDate = day.plusDays(1)), fetched))
        assertNull(TrainRun.parse("{nie json", ref, fetched))
    }

    @Test
    fun `live times for departure and target station`() {
        val run = TrainRun.parse(body, ref, fetched)!!
        val t = train(from = 33571, to = 39008, dep = at(19, 42)).let { it.copy(live = run.liveFor(it)) }
        assertEquals(at(20, 4), t.live!!.departure)
        assertEquals(at(20, 50, 6), t.live!!.arrival)
        assertFalse(t.cancelled)
        assertEquals(at(20, 4), t.expectedDeparture)
        assertEquals(22, t.delayMin)
    }

    @Test
    fun `cancelled target makes train cancelled`() {
        val run = TrainRun.parse(body, ref, fetched)!!
        assertTrue(run.liveFor(train(from = 33571, to = 39404, dep = at(19, 42)))!!.cancelled)
        assertTrue(run.copy(status = "X").liveFor(train(from = 33571, to = 39008, dep = at(19, 42)))!!.cancelled)
    }

    @Test
    fun `station missing in run gives no live times`() {
        assertNull(TrainRun.parse(body, ref, fetched)!!.liveFor(train(from = 1, to = 39008, dep = at(19, 42))))
    }

    @Test
    fun `early forecast never moves departure before plan`() {
        val run = TrainRun.parse(body, ref, fetched)!!
        val t = train(from = 33571, to = 39008, dep = at(20, 10)).let { it.copy(live = run.liveFor(it)) }
        assertEquals(at(20, 10), t.expectedDeparture)
        assertEquals(0, t.delayMin)
    }
}
