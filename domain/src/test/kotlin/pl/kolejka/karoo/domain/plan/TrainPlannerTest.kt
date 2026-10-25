package pl.kolejka.karoo.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.geo.FinishCandidate
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.geo.StationOnRoute
import pl.kolejka.karoo.domain.model.PreferenceMode
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.time.ServiceTime
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable.StopData
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable.TripData
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class TrainPlannerTest {
    private val day = LocalDate.of(2026, 10, 26) // poniedziałek

    private fun station(id: Int, name: String) = Station(id, name, name.lowercase(), LatLon(52.0, 21.0), "KM", null)
    private val minsk = station(1, "Mińsk Mazowiecki")
    private val anielina = station(2, "Mińsk Mazowiecki Anielina")
    private val gdanska = station(10, "Warszawa Gdańska")
    private val wilenska = station(11, "Warszawa Wileńska")
    private val srod = station(12, "Warszawa Śródmieście")
    private val srodWkd = station(13, "Warszawa Śródmieście WKD")
    private val alt = station(20, "Sulejówek")
    private val alt2 = station(21, "Halinów")
    private val targets = TargetGroups(listOf(setOf(10), setOf(11), setOf(12, 13)))

    private fun s(h: Int, m: Int) = h * 3600 + m * 60
    private fun at(h: Int, m: Int, date: LocalDate = day): Instant = LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(ServiceTime.ZONE).toInstant()
    private fun clock(i: Instant) = Clock.fixed(i, ZoneOffset.UTC)

    private val baseTrips = listOf(
        TripData(1, setOf(day), listOf(StopData(1, s(17, 12)), StopData(11, s(17, 50)), StopData(10, s(18, 1))), routeShortName = "R2"),
        TripData(2, setOf(day), listOf(StopData(1, s(17, 42), platform = "2", track = "4"), StopData(10, s(18, 31))), routeShortName = "R2"),
        TripData(3, setOf(day), listOf(StopData(1, s(18, 12)), StopData(10, s(19, 1))), routeShortName = "R2"),
        TripData(4, setOf(day), listOf(StopData(20, s(19, 0)), StopData(10, s(19, 40)))),
        TripData(5, setOf(day), listOf(StopData(21, s(19, 10)), StopData(10, s(19, 50)))),
    )
    private val allStations = listOf(minsk, anielina, gdanska, wilenska, srod, srodWkd, alt, alt2)

    private fun geometry(candidates: List<FinishCandidate> = listOf(FinishCandidate(minsk, 400.0))) = RouteGeometry(
        finish = LatLon(52.0, 21.0),
        lengthM = 40_000.0,
        finishCandidates = candidates,
        stationsOnRoute = listOf(
            StationOnRoute(alt, 300.0, listOf(20_000.0)),
            StationOnRoute(alt2, 200.0, listOf(35_000.0)),
        ),
        nearestToFinish = candidates.firstOrNull(),
    )

    private val data = DataStatus(validTo = day.plusDays(10), generatedAt = at(3, 0))

    private fun plan(
        now: Instant,
        eta: Instant?,
        remainingM: Double = 10_000.0,
        trips: List<TripData> = baseTrips,
        settings: Settings = Settings(),
        geometry: RouteGeometry = geometry(),
        dataStatus: DataStatus? = data,
    ): TileState = TrainPlanner(InMemoryTimetable(allStations, trips), clock(now))
        .plan(geometry, 40_000.0, RideProgress(remainingM, eta), settings, targets, dataStatus)

    @Test
    fun `classifies catchable, sure main and next with last flag`() {
        val st = plan(now = at(16, 30), eta = at(17, 20)) as TileState.Ok
        // t_ready = 17:20 + 400 m / 15 km/h (96 s) + 5 min = 17:26:36
        assertEquals(listOf(1, 2, 3), st.display.map { it.train.tripId })
        assertEquals(TrainClass.CATCHABLE, st.catchable!!.cls)
        assertEquals(16.86, st.catchable!!.vReqKmh!!, 0.05) // 10,4 km w 37 min
        assertEquals(2, st.main.train.tripId)
        assertEquals(TrainClass.SURE, st.main.cls)
        assertEquals(15 * 60 + 24L, st.main.slack.seconds)
        assertEquals("2", st.main.train.platform)
        assertEquals("4", st.main.train.track)
        assertTrue(st.next!!.isLast)
        assertFalse(st.main.isLast)
        assertFalse(st.etaApproximate)
        assertNull(st.safePlan)
    }

    @Test
    fun `label is highest group served after departure, earliest arrival is any group`() {
        val st = plan(now = at(16, 0), eta = at(16, 50)) as TileState.Ok
        val t1 = st.main.train
        assertEquals(1, t1.tripId)
        assertEquals(0, t1.labelGroup)
        assertEquals(10, t1.labelStationId)
        assertEquals(at(18, 1), t1.labelArrival)
        assertEquals(at(17, 50), t1.earliestArrival)
    }

    @Test
    fun `fallback eta without karoo is approximate`() {
        // 10 km przy 25 km/h = 24 min → meta 16:54, t_ready 17:00:36 → T1 17:12
        val st = plan(now = at(16, 30), eta = null) as TileState.Ok
        assertTrue(st.etaApproximate)
        assertEquals(1, st.main.train.tripId)
    }

    @Test
    fun `main train beyond three hour window still found until end of day`() {
        val st = plan(now = at(12, 0), eta = at(12, 30)) as TileState.Ok
        assertEquals(1, st.main.train.tripId)
    }

    @Test
    fun `last train missed suggests farthest reachable station on route`() {
        // 18:30, meta 19:30, zostało 30 km → pozycja 10 km, v = 30 km/h
        val st = plan(now = at(18, 30), eta = at(19, 30), remainingM = 30_000.0) as TileState.LastTrainMissed
        assertEquals(minsk, st.station)
        assertEquals(3, st.lastFromFinish!!.train.tripId)
        assertEquals(TrainClass.MISSED, st.lastFromFinish!!.cls)
        // Halinów (35 km): na stacji 19:20 + dojazd + zapas > 19:10 → odpada; Sulejówek (20 km): 18:50 → 19:00 OK
        val alt = st.alternative!!
        assertEquals(20, alt.station.id)
        assertEquals(4, alt.train.tripId)
        assertEquals(at(18, 50), alt.etaStation)
    }

    @Test
    fun `safe plan when main is last and slack below ten minutes`() {
        // 17:00, meta 18:00 → t_ready 18:06:36; T3 18:12 (ostatni), zapas 5:24
        val st = plan(now = at(17, 0), eta = at(18, 0)) as TileState.Ok
        assertEquals(3, st.main.train.tripId)
        assertTrue(st.main.isLast)
        assertEquals(TrainClass.CATCHABLE, st.catchable!!.cls) // T2 17:42 przy ~17 km/h
        val safe = st.safePlan!!
        assertEquals(21, safe.station.id) // Sulejówek (20 km) już minięty przy pozycji 30 km
        assertEquals(5, safe.train.tripId)
    }

    @Test
    fun `no trains today`() {
        val otherDay = baseTrips.map { it.copy(dates = setOf(day.plusDays(1))) }
        val st = plan(now = at(16, 30), eta = at(17, 20), trips = otherDay)
        assertEquals(TileState.NoTrainsToday(minsk), st)
    }

    @Test
    fun `already at target when finish candidate is a target station`() {
        val st = plan(now = at(16, 30), eta = at(17, 20), geometry = geometry(listOf(FinishCandidate(gdanska, 150.0))))
        assertEquals(TileState.AlreadyAtTarget(gdanska), st)
    }

    @Test
    fun `no station near finish reports nearest and stations on route`() {
        val g = geometry(emptyList()).copy(nearestToFinish = FinishCandidate(minsk, 2_400.0))
        val st = plan(now = at(16, 30), eta = at(17, 20), geometry = g)
        assertEquals(TileState.NoStationNearFinish(2_400.0, 2), st)
    }

    @Test
    fun `data missing and stale`() {
        assertEquals(TileState.DataMissing, plan(at(16, 30), at(17, 20), dataStatus = null))
        val old = DataStatus(validTo = day.minusDays(1), generatedAt = at(3, 0, day.minusDays(16)))
        assertEquals(TileState.DataStale(16), plan(at(16, 30), at(17, 20), dataStatus = old))
        val ending = DataStatus(validTo = day, generatedAt = at(3, 0))
        assertTrue((plan(at(16, 30), at(17, 20), dataStatus = ending) as TileState.Ok).dataStale)
    }

    @Test
    fun `fastest candidate wins, tie goes to closer station`() {
        val trips = baseTrips + TripData(9, setOf(day), listOf(StopData(2, s(17, 35)), StopData(10, s(18, 20))))
        val cands = listOf(FinishCandidate(minsk, 400.0), FinishCandidate(anielina, 800.0))
        val st = plan(now = at(16, 30), eta = at(17, 20), trips = trips, geometry = geometry(cands)) as TileState.Ok
        assertEquals(anielina, st.station)
        assertEquals(9, st.main.train.tripId)

        val tie = baseTrips + TripData(9, setOf(day), listOf(StopData(2, s(17, 35)), StopData(10, s(18, 31))))
        val st2 = plan(now = at(16, 30), eta = at(17, 20), trips = tie, geometry = geometry(cands)) as TileState.Ok
        assertEquals(minsk, st2.station)
    }

    @Test
    fun `strict preference takes top group unless much later than fastest`() {
        val trips = listOf(
            TripData(1, setOf(day), listOf(StopData(1, s(17, 30)), StopData(11, s(18, 10)))),
            TripData(2, setOf(day), listOf(StopData(1, s(17, 40)), StopData(10, s(18, 31)))),
        )
        val fastest = plan(at(16, 30), at(17, 20), trips = trips) as TileState.Ok
        assertEquals(1, fastest.main.train.tripId)
        val strict = plan(at(16, 30), at(17, 20), trips = trips, settings = Settings(mode = PreferenceMode.STRICT)) as TileState.Ok
        assertEquals(2, strict.main.train.tripId)
        val strictTight = plan(
            at(16, 30), at(17, 20), trips = trips,
            settings = Settings(mode = PreferenceMode.STRICT, strictToleranceMin = 10),
        ) as TileState.Ok
        assertEquals(1, strictTight.main.train.tripId)
    }

    @Test
    fun `twin target stations form one group`() {
        val trips = listOf(TripData(7, setOf(day), listOf(StopData(1, s(17, 40)), StopData(13, s(18, 20))), agency = "WKD"))
        val st = plan(at(16, 30), at(17, 20), trips = trips) as TileState.Ok
        assertEquals(2, st.main.train.labelGroup)
        assertEquals(13, st.main.train.labelStationId)
    }

    @Test
    fun `train after midnight on saturday`() {
        val sat = LocalDate.of(2026, 10, 31)
        val trips = listOf(TripData(8, setOf(sat), listOf(StopData(1, s(23, 50)), StopData(10, s(24, 30)))))
        val st = plan(now = at(23, 0, sat), eta = at(23, 20, sat), trips = trips) as TileState.Ok
        assertEquals(at(23, 50, sat), st.main.train.departure)
        assertEquals(at(0, 30, sat.plusDays(1)), st.main.train.labelArrival)
        assertTrue(st.main.isLast)
    }

    @Test
    fun `eta after midnight finds next day early train`() {
        val trips = listOf(TripData(8, setOf(day.plusDays(1)), listOf(StopData(1, s(0, 40)), StopData(10, s(1, 30)))))
        val st = plan(now = at(23, 30), eta = at(0, 10, day.plusDays(1)), trips = trips) as TileState.Ok
        assertEquals(at(0, 40, day.plusDays(1)), st.main.train.departure)
    }

    @Test
    fun `no pickup at departure and no drop off at target are respected`() {
        val trips = listOf(
            TripData(1, setOf(day), listOf(StopData(1, s(17, 40), pickup = false), StopData(10, s(18, 20)))),
            TripData(2, setOf(day), listOf(StopData(1, s(17, 45)), StopData(10, s(18, 25), dropOff = false))),
            TripData(3, setOf(day), listOf(StopData(1, s(17, 50)), StopData(10, s(18, 30)))),
        )
        val st = plan(at(16, 30), at(17, 20), trips = trips) as TileState.Ok
        assertEquals(listOf(3), st.options.map { it.train.tripId })
    }

    @Test
    fun `required speed null when departure minus buffer already passed`() {
        val st = plan(now = at(17, 9), eta = at(17, 20)) as TileState.Ok
        val t1 = st.options.first { it.train.tripId == 1 }
        assertNull(t1.vReqKmh)
        assertEquals(TrainClass.MISSED, t1.cls)
        assertNotNull(st.main)
    }
}
