package pl.kolejka.karoo.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainClass
import pl.kolejka.karoo.domain.plan.TrainOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TileTextTest {
    private val t0 = Instant.parse("2026-10-25T14:00:00Z")
    private val station = Station(1, "Mrozy", "mrozy", LatLon(52.16, 21.8), "KM", null)

    private fun option(id: Int, slackMin: Long, cls: TrainClass) = TrainOption(
        Train(id, LocalDate.of(2026, 10, 25), 1, t0.plusSeconds(id * 1800L), "1", null, "KM", "R2", false, 2, 33571,
            t0.plusSeconds(id * 1800L + 3600), t0.plusSeconds(id * 1800L + 3600)),
        cls, Duration.ofMinutes(slackMin), null,
    )

    private fun mainMark(slackMin: Long): String {
        val st = TileState.Ok(station, 300.0, listOf(option(1, slackMin, TrainClass.SURE), option(2, slackMin + 30, TrainClass.SURE)), 0, false)
        val lines = TileText.lines(TileModel(st, stationName = { "Warszawa Śródmieście" }), TileLayout.TALL)
        return lines[2].text.substringBefore(' ')
    }

    @Test
    fun `main train with less than five minutes slack gets warning sign`() {
        assertEquals(TileText.SURE, mainMark(5))
        assertEquals(TileText.WARNING, mainMark(4))
        assertEquals(TileText.WARNING, mainMark(0))
    }
}
