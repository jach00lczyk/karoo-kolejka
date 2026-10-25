package pl.kolejka.karoo.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.model.Station

class RouteGeometryTest {
    private fun st(id: Int, lat: Double, lon: Double) = Station(id, "S$id", "s$id", LatLon(lat, lon), "KM", null)

    // Trasa wschód→zachód wzdłuż równoleżnika 52°: 1° długości ≈ 68,6 km.
    private val route = (0..400).map { LatLon(52.0, 21.4 - it * 0.001) } // ~27,5 km
    private val finish = route.last()

    @Test
    fun `finish candidates within radius sorted by distance`() {
        val near = st(1, 52.0036, finish.lon) // ~400 m
        val nearer = st(2, 52.0018, finish.lon) // ~200 m
        val far = st(3, 52.02, finish.lon) // ~2,2 km
        val g = RouteAnalyzer.analyze(route, SpatialIndex(listOf(near, nearer, far)), 1000.0, 1000.0)!!
        assertEquals(listOf(2, 1), g.finishCandidates.map { it.station.id })
        assertEquals(200.0, g.finishCandidates[0].distanceM, 10.0)
    }

    @Test
    fun `no candidate reports nearest station`() {
        val far = st(3, 52.02, finish.lon)
        val g = RouteAnalyzer.analyze(route, SpatialIndex(listOf(far)), 1000.0, 1000.0)!!
        assertTrue(g.finishCandidates.isEmpty())
        assertEquals(2200.0, g.nearestToFinish!!.distanceM, 50.0)
    }

    @Test
    fun `stations on route with along position and distance, excluding finish candidates`() {
        val onRoute = st(10, 52.0018, 21.4 - 0.146) // ~10 km od startu, 200 m od trasy
        val offRoute = st(11, 52.02, 21.3) // 2,2 km od trasy
        val atFinish = st(12, 52.0, finish.lon)
        val g = RouteAnalyzer.analyze(route, SpatialIndex(listOf(onRoute, offRoute, atFinish)), 1000.0, 1000.0)!!
        assertEquals(listOf(10), g.stationsOnRoute.map { it.station.id })
        val s = g.stationsOnRoute.single()
        assertEquals(200.0, s.distanceFromRouteM, 10.0)
        assertEquals(10_020.0, s.occurrencesM.single(), 150.0)
        assertEquals(27_440.0, g.lengthM, 200.0)
    }

    @Test
    fun `loop passing the same station twice yields two occurrences`() {
        // Tam (10 km na zachód) i z powrotem tą samą drogą, meta na wschód od startu.
        val out = (0..146).map { LatLon(52.0, 21.4 - it * 0.001) }
        val back = (145 downTo 0).map { LatLon(52.0005, 21.4 - it * 0.001) }
        val tail = (1..50).map { LatLon(52.0005, 21.4 + it * 0.001) }
        val loop = out + back + tail
        val station = st(20, 52.002, 21.4 - 0.073) // ~5 km od startu w obu kierunkach
        val g = RouteAnalyzer.analyze(loop, SpatialIndex(listOf(station)), 1000.0, 1000.0)!!
        val s = g.stationsOnRoute.single()
        assertEquals(2, s.occurrencesM.size)
        assertEquals(5_000.0, s.occurrencesM[0], 300.0)
        assertEquals(15_000.0, s.occurrencesM[1], 300.0)
        // Przed pierwszym przejściem → pierwsze; między nimi → drugie (nie zawsze pierwsze!).
        assertEquals(s.occurrencesM[0], s.positionAhead(1_000.0), 0.0)
        assertEquals(s.occurrencesM[1], s.positionAhead(8_000.0), 0.0)
        assertTrue(s.isPassed(16_000.0))
    }

    @Test
    fun `destination overrides last polyline point`() {
        val dest = LatLon(52.1, 21.0)
        val station = st(30, 52.1018, 21.0)
        val g = RouteAnalyzer.analyze(route, SpatialIndex(listOf(station)), 1000.0, 1000.0, destination = dest)!!
        assertEquals(dest, g.finish)
        assertEquals(30, g.finishCandidates.single().station.id)
    }

    @Test
    fun `empty polyline gives null and single point gives no stations on route`() {
        assertNull(RouteAnalyzer.analyze(emptyList(), SpatialIndex(emptyList()), 1000.0, 1000.0))
        val g = RouteAnalyzer.analyze(listOf(finish), SpatialIndex(listOf(st(1, 52.0, finish.lon))), 1000.0, 1000.0)
        assertNotNull(g)
        assertTrue(g!!.stationsOnRoute.isEmpty())
        assertEquals(1, g.finishCandidates.size)
    }

    @Test
    fun `invalid coordinates are ignored`() {
        val pts = route + LatLon(Double.NaN, 21.0) + LatLon(95.0, 21.0)
        val g = RouteAnalyzer.analyze(pts, SpatialIndex(emptyList()), 1000.0, 1000.0)!!
        assertEquals(finish, g.finish)
    }
}
