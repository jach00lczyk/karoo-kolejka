package pl.kolejka.karoo.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.RouteAnalyzer
import pl.kolejka.karoo.domain.geo.SpatialIndex
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.domain.text.TileText
import pl.kolejka.karoo.domain.time.ServiceTime
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable.StopData
import pl.kolejka.karoo.domain.timetable.InMemoryTimetable.TripData
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Scenariusz testu w terenie z 25.10.2026: trasa przez Mrozy i Kotuń do Siedlec (R2, niedziela).
 * Współrzędne stacji z rozkładu (publiczne dane PLK), trasa uproszczona do linii.
 */
class SiedlceRouteTest {
    private val day = LocalDate.of(2026, 10, 25)
    private fun s(h: Int, m: Int) = h * 3600 + m * 60
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, LocalTime.of(h, m)).atZone(ServiceTime.ZONE).toInstant()

    private val mrozy = Station(39875, "Mrozy", "mrozy", LatLon(52.164198, 21.801489), "KM", null)
    private val kotun = Station(264895, "Kotuń", "kotun", LatLon(52.176284, 22.067826), "KM", null)
    private val siedlce = Station(39404, "Siedlce", "siedlce", LatLon(52.161814, 22.271674), "KM", null)
    private val srod = Station(33571, "Warszawa Śródmieście", "warszawa srodmiescie", LatLon(52.2294, 21.0076), "KM,SKM", 33571)
    private val targets = TargetGroups(listOf(setOf(36103), setOf(37440), setOf(33571)))

    // R2 do Warszawy w niedzielę (fragment z rozkładu): Siedlce :30 → Kotuń :41 → Mrozy :56 → Śródmieście :57
    private val trips = (12..19).map { h ->
        TripData(
            h, setOf(day),
            listOf(StopData(39404, s(h, 30), platform = "2"), StopData(264895, s(h, 41), platform = "2"), StopData(39875, s(h, 56), platform = "1"), StopData(33571, s(h + 1, 57))),
            routeShortName = "R2",
        )
    }
    private val timetable = InMemoryTimetable(listOf(mrozy, kotun, siedlce, srod), trips)

    // Trasa z zachodu: start 10 km przed Mrozami, meta 300 m od dworca w Siedlcach.
    private val waypoints = listOf(
        LatLon(52.165, 21.66), LatLon(52.1635, 21.80), LatLon(52.1755, 22.07), LatLon(52.1625, 22.2760),
    )
    private val route = waypoints.zipWithNext().flatMap { (p, q) ->
        (0 until 40).map { i -> LatLon(p.lat + (q.lat - p.lat) * i / 40, p.lon + (q.lon - p.lon) * i / 40) }
    } + waypoints.last()

    @Test
    fun `finish at Siedlce, Kotun and Mrozy ahead with first catchable train`() {
        val g = RouteAnalyzer.analyze(route, SpatialIndex(timetable.stations), 1000.0, 1000.0)!!
        assertEquals("Siedlce", g.finishCandidates.single().station.name)
        assertEquals(listOf("Mrozy", "Kotuń"), g.stationsOnRoute.map { it.station.name })

        val clock = Clock.fixed(at(13, 0), ZoneOffset.UTC)
        val planner = TrainPlanner(timetable, clock)
        val routeM = g.lengthM
        // Trasa ~42 km, Mrozy na ~9,7 km. Jesteśmy 5 km od startu, meta wg Karoo 14:50.
        val progress = RideProgress(remainingM = routeM - 5_000, karooEta = at(14, 50))
        val st = planner.plan(g, routeM, progress, Settings(), targets, DataStatus(day.plusDays(10), at(3, 0))) as TileState.Ok
        assertEquals(siedlce, st.station)
        assertEquals(at(15, 30), st.main.train.departure) // t_ready ≈ 14:56
        assertEquals(TrainClass.CATCHABLE, st.catchable!!.cls) // 14:30: ~37 km w 85 min ≈ 26 km/h

        val eta = EtaEstimator.finishEta(clock.instant(), progress.karooEta, null, progress.remainingM, routeM, null, 25.0)
        val up = UpcomingStations(planner, clock).compute(g, routeM, progress, eta, Settings(), targets, limit = 2)
        assertEquals(listOf("Mrozy", "Kotuń"), up.map { it.station.name })
        assertTrue(up.all { it.train != null && !it.train!!.departure.isBefore(it.eta) })

        val lines = TileText.lines(TileModel(st, up, stationName = { if (it == 33571) "Warszawa Śródmieście" else "?" }), compact = false)
        assertTrue(lines.first().text, lines.first().text.startsWith("Siedlce → W-wa Śródm.  P2"))
        assertTrue(lines.any { it.text.startsWith("${TileText.SURE} 15:30→16:57") })
        assertTrue(lines.any { it.text.startsWith("Mrozy ~") })

        // Wysoki kafel: meta i stacje po drodze ze „skąd → dokąd" i 🚲, kółka klas pociągów.
        val model = TileModel(st, up, { if (it == 33571) "Warszawa Śródmieście" else "?" }, Eta(at(14, 50), false), routeM - 5_000)
        val tall = TileText.lines(model, pl.kolejka.karoo.domain.text.TileLayout.TALL)
        assertEquals("Siedlce → W-wa Śródm.", tall[0].text)
        assertEquals("${TileText.BIKE} 14:50 · 37 km", tall[1].text)
        assertEquals("${TileText.CATCHABLE} 14:30 → 15:57", tall[2].text)
        assertTrue(tall[3].text, tall[3].text.startsWith("P2 · potrzeba "))
        val mainIdx = tall.indexOfFirst { it.text == "${TileText.SURE} 15:30 → 16:57" }
        assertTrue(tall.joinToString("\n") { it.text }, mainIdx > 0)
        assertTrue(tall[mainIdx + 1].small)
        assertTrue(tall[mainIdx + 1].text, tall[mainIdx + 1].text.startsWith("P2 · zapas "))
        assertEquals("${TileText.NEXT} 16:30 → 17:57", tall[mainIdx + 2].text)
        val mrozy = tall.indexOfFirst { it.text == "Mrozy → W-wa Śródm." }
        assertTrue(tall.joinToString("\n") { it.text }, mrozy > 0)
        assertTrue(tall.subList(mrozy, mrozy + 3).all { it.small })
        assertTrue(tall[mrozy + 1].text, Regex("${TileText.BIKE} \\d\\d:\\d\\d · \\d+(,\\d)? km").matches(tall[mrozy + 1].text))
        assertTrue(tall[mrozy + 2].text, Regex("\\d\\d:56 → \\d\\d:57 · P1").matches(tall[mrozy + 2].text))
        // Ostatni pociąg dnia w oknie ma czerwony dopisek zamiast tekstu w linii
        assertTrue(tall.none { "OSTATNI" in it.text })
    }
}
