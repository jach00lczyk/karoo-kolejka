package pl.kolejka.karoo.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TileStabilizerTest {
    private val t0 = Instant.parse("2026-10-26T15:00:00Z")
    private val station = Station(1, "Mińsk Mazowiecki", "minsk mazowiecki", LatLon(52.0, 21.0), "KM", null)

    private fun option(tripId: Int, depMin: Long, slackSec: Long, vReq: Double? = null) = TrainOption(
        train = Train(
            tripId = tripId, serviceDate = LocalDate.of(2026, 10, 26), fromStationId = 1,
            departure = t0.plusSeconds(depMin * 60), platform = null, track = null, agency = "KM",
            routeShortName = "R2", isBus = false, labelGroup = 0, labelStationId = 10,
            labelArrival = t0.plusSeconds(depMin * 60 + 3000), earliestArrival = t0.plusSeconds(depMin * 60 + 3000),
        ),
        cls = if (slackSec >= 0) TrainClass.SURE else TrainClass.CATCHABLE,
        slack = Duration.ofSeconds(slackSec),
        vReqKmh = vReq,
    )

    /** Trzy pociągi co 30 min; [etaShiftSec] przesuwa t_ready (dodatnie = później). */
    private fun state(etaShiftSec: Long, vReq: Double? = null): TileState.Ok {
        val baseSlacks = listOf(-600L, 300L, 2100L) // względem t_ready w chwili zero
        val options = baseSlacks.mapIndexed { i, s -> option(i + 1, 30L * (i + 1), s - etaShiftSec, vReq.takeIf { i == 0 }) }
        val main = options.indexOfFirst { it.cls == TrainClass.SURE }
        return TileState.Ok(station, 400.0, options, main, etaApproximate = false)
    }

    private fun TileState.main() = (this as TileState.Ok).main.train.tripId

    @Test
    fun `first result is accepted as is`() {
        assertEquals(2, TileStabilizer().apply(state(0), t0).main())
    }

    @Test
    fun `switch to later train only after slack below minus one minute for two minutes`() {
        val s = TileStabilizer()
        s.apply(state(0), t0)
        // ETA gorsze o 6 min → zapas pociągu 2 = −1 min (nie poniżej −1): bez zmiany
        assertEquals(2, s.apply(state(360), t0.plusSeconds(10)).main())
        // −1,5 min
        assertEquals(2, s.apply(state(390), t0.plusSeconds(20)).main())
        assertEquals(2, s.apply(state(390), t0.plusSeconds(80)).main())
        assertEquals(2, s.apply(state(390), t0.plusSeconds(139)).main())
        assertEquals(3, s.apply(state(390), t0.plusSeconds(140)).main())
    }

    @Test
    fun `timer resets when slack recovers`() {
        val s = TileStabilizer()
        s.apply(state(0), t0)
        s.apply(state(390), t0.plusSeconds(0))
        s.apply(state(300), t0.plusSeconds(60)) // zapas wraca do 0
        assertEquals(2, s.apply(state(390), t0.plusSeconds(120)).main())
        assertEquals(2, s.apply(state(390), t0.plusSeconds(239)).main())
        assertEquals(3, s.apply(state(390), t0.plusSeconds(240)).main())
    }

    @Test
    fun `return to earlier train only with at least two minutes slack`() {
        val s = TileStabilizer()
        s.apply(state(390), t0)
        // Pierwszy wynik po wahaniu: zaczynamy od pociągu 3
        assertEquals(3, s.apply(state(390), t0).main())
        // Przyspieszenie: pociąg 2 ma zapas +1 min → zostajemy przy 3
        assertEquals(3, s.apply(state(240), t0.plusSeconds(30)).main())
        // +2,5 min → wracamy
        assertEquals(2, s.apply(state(150), t0.plusSeconds(60)).main())
    }

    @Test
    fun `required speed is a 60 second moving average`() {
        val s = TileStabilizer()
        fun v(st: TileState) = (st as TileState.Ok).options[0].vReqKmh!!
        assertEquals(30.0, v(s.apply(state(0, 30.0), t0)), 1e-9)
        assertEquals(25.0, v(s.apply(state(0, 20.0), t0.plusSeconds(30))), 1e-9)
        assertEquals(20.0, v(s.apply(state(0, 20.0), t0.plusSeconds(90))), 1e-9)
    }

    @Test
    fun `non ok state resets history`() {
        val s = TileStabilizer()
        s.apply(state(390), t0)
        s.apply(TileState.NoRoute, t0.plusSeconds(1))
        assertEquals(2, s.apply(state(150), t0.plusSeconds(2)).main())
    }
}
