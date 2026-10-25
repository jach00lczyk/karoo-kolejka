package pl.kolejka.karoo.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    // Przykład z dokumentacji Google Encoded Polyline.
    private val googleExample = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun `decodes google reference example`() {
        val pts = Polyline.decode(googleExample, 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9)
        assertEquals(-120.2, pts[0].lon, 1e-9)
        assertEquals(40.7, pts[1].lat, 1e-9)
        assertEquals(-120.95, pts[1].lon, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9)
        assertEquals(-126.453, pts[2].lon, 1e-9)
    }

    @Test
    fun `encode and decode round trip for precision 5 and 6`() {
        val pts = listOf(LatLon(52.17901, 21.56701), LatLon(52.25912, 20.99312), LatLon(52.229386, 21.007598))
        for (prec in listOf(5, 6)) {
            val decoded = Polyline.decode(Polyline.encode(pts, prec), prec)
            assertEquals(pts.size, decoded.size)
            pts.zip(decoded).forEach { (a, b) ->
                assertEquals(a.lat, b.lat, 1e-5)
                assertEquals(a.lon, b.lon, 1e-5)
            }
        }
    }

    @Test
    fun `invalid input decodes to empty list instead of throwing`() {
        assertTrue(Polyline.decode("_p~iF~ps|U_", 5).isEmpty()) // urwany w połowie wartości
        assertTrue(Polyline.decode("  ", 5).isEmpty())
        assertTrue(Polyline.decode("", 5).isEmpty())
    }

    @Test
    fun `detects precision from expected route length`() {
        val pts = (0..50).map { LatLon(52.0 + it * 0.001, 21.0) } // ~5,56 km
        val length = polylineLengthM(pts)
        assertEquals(5, Polyline.detectPrecision(Polyline.encode(pts, 5), length))
        assertEquals(6, Polyline.detectPrecision(Polyline.encode(pts, 6), length))
        assertNull(Polyline.detectPrecision(Polyline.encode(pts, 5), length * 10))
    }

    @Test
    fun `douglas peucker drops collinear points and keeps endpoints and corners`() {
        val line = (0..100).map { LatLon(52.0, 21.0 + it * 0.0001) } + (1..100).map { LatLon(52.0 + it * 0.0001, 21.01) }
        val s = Polyline.simplify(line, 20.0)
        assertEquals(3, s.size)
        assertEquals(line.first(), s.first())
        assertEquals(line[100], s[1])
        assertEquals(line.last(), s.last())
    }

    @Test
    fun `simplify keeps deviations above tolerance`() {
        val zigzag = listOf(LatLon(52.0, 21.0), LatLon(52.0005, 21.001), LatLon(52.0, 21.002)) // ~55 m odchylenia
        assertEquals(3, Polyline.simplify(zigzag, 20.0).size)
        assertEquals(2, Polyline.simplify(zigzag, 100.0).size)
    }

    @Test
    fun `haversine matches known distance`() {
        // Warszawa Gdańska → Warszawa Wileńska ≈ 3,1 km
        val d = haversineM(LatLon(52.25912, 20.99312), LatLon(52.25570, 21.03832))
        assertEquals(3100.0, d, 50.0)
    }
}
