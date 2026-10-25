package pl.kolejka.karoo.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.Station

class StationSearchTest {
    private fun st(name: String, agencies: String = "KM") =
        Station(name.hashCode(), name, StationSearch.normalize(name), LatLon(52.0, 21.0), agencies, null)

    private val stations = listOf(
        st("Łódź Fabryczna"), st("Mińsk Mazowiecki"), st("Warszawa Śródmieście", "KM,SKM"),
        st("Warszawa Śródmieście WKD", "WKD"), st("Żyrardów"), st("Zielonka"), st("Ząbki"),
        st("Warszawa Zachodnia (Peron 9)"),
    )

    @Test
    fun `normalization matches pipeline`() {
        assertEquals("lodz fabryczna", StationSearch.normalize("Łódź  Fabryczna"))
        assertEquals("minsk mazowiecki", StationSearch.normalize("Mińsk Mazowiecki"))
    }

    @Test
    fun `search without diacritics by word prefixes`() {
        assertEquals(listOf("Łódź Fabryczna"), StationSearch.search("lodz", stations).map { it.name })
        assertEquals(listOf("Mińsk Mazowiecki"), StationSearch.search("mins", stations).map { it.name })
        assertEquals(listOf("Mińsk Mazowiecki"), StationSearch.search("maz min", stations).map { it.name })
        assertTrue(StationSearch.search("w-wa srod", stations).isEmpty()) // skrót „W-wa" to tylko zapis na kafelku
        assertEquals(2, StationSearch.search("warszawa srod", stations).size)
        assertTrue(StationSearch.matches("peron 9", stations.last()))
        assertFalse(StationSearch.matches("arszawa", stations[2]))
    }

    @Test
    fun `polish collation order`() {
        val names = StationSearch.sorted(stations).map { it.name }
        assertEquals(listOf("Łódź Fabryczna", "Mińsk Mazowiecki"), names.take(2))
        // Z < Ź < Ż w polskim alfabecie; „Ząbki" przed „Zielonka" (ą po a)
        assertEquals(listOf("Ząbki", "Zielonka", "Żyrardów"), names.takeLast(3))
    }
}
