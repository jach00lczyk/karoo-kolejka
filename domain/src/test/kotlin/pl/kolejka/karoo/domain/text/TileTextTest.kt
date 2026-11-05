package pl.kolejka.karoo.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.LiveTimes
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.plan.Alternative
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

    private fun mainLine(slackMin: Long): TileLine {
        val st = TileState.Ok(station, 300.0, listOf(option(1, slackMin, TrainClass.SURE), option(2, slackMin + 30, TrainClass.SURE)), 0, false)
        return TileText.lines(TileModel(st, stationName = { "Warszawa Śródmieście" }), TileLayout.TALL)[2]
    }

    @Test
    fun `main train with less than five minutes slack is yellow, mark stays green`() {
        for (slack in listOf(5L, 4L, 0L)) {
            val line = mainLine(slack)
            assertEquals(Seg("${TileText.SURE} ", Tint.GREEN), line.segments.first())
            assertEquals(if (slack < 5) Emphasis.WARN else Emphasis.NORMAL, line.emphasis)
            assertTrue(line.times)
        }
    }

    private fun live(o: TrainOption, delayMin: Long = 0, cancelled: Boolean = false) =
        o.copy(train = o.train.copy(live = LiveTimes(o.train.departure.plusSeconds(delayMin * 60), null, cancelled, t0)))

    @Test
    fun `delay is shown next to planned departure`() {
        val main = live(option(1, 10, TrainClass.SURE), delayMin = 6)
        val cancelled = live(option(2, 40, TrainClass.MISSED), cancelled = true)
        val st = TileState.Ok(station, 300.0, listOf(main, cancelled), 0, false)
        val model = TileModel(st, stationName = { "Warszawa Śródmieście" })
        val tall = TileText.lines(model, TileLayout.TALL)
        assertEquals("${TileText.SURE} 15:30 +6 → 16:30", tall[2].text)
        assertEquals(Seg(" +6", Tint.RED), tall[2].segments[2])
        assertEquals("${TileText.MISSED} 16:00 → 17:00", tall[4].text)
        assertEquals("[P1] ${TileText.CANCELLED}", tall[5].text)
        val wide = TileText.lines(model, TileLayout.WIDE)
        assertEquals("${TileText.SURE} 15:30+6→16:30  zapas 10 min", wide[1].text)
        assertEquals("15:30+6→16:30", TileText.lines(model, TileLayout.COMPACT)[0].text)
    }

    @Test
    fun `delay below one minute is not shown`() {
        assertEquals("15:30", TileText.dep(live(option(1, 10, TrainClass.SURE), delayMin = 0).train))
        assertEquals("15:30", TileText.dep(option(1, 10, TrainClass.SURE).train))
    }

    @Test
    fun `last train missed in tall tile splits warning and full alternative`() {
        val alt = Alternative(Station(2, "Tłuszcz", "tluszcz", LatLon(52.4, 21.4), "KM", null), 64_000.0, t0.plusSeconds(1200), option(1, 0, TrainClass.SURE).train)
        val st = TileState.LastTrainMissed(station, option(2, -18, TrainClass.MISSED).copy(isLast = true), alt, etaApproximate = false)
        val lines = TileText.lines(TileModel(st, stationName = { "Warszawa Śródmieście" }), TileLayout.TALL)
        assertEquals(
            listOf("${TileText.WARNING} Nie zdążysz", "ostatni z Mrozy 16:00", "Zjedź: Tłuszcz", "15:30 → 16:30", "W-wa Śródm. [P1]", "${TileText.ETA} 15:20 · km 64"),
            lines.map { it.text },
        )
        assertEquals(listOf(false, false, true, true, true, false), lines.map { it.keepWithNext })
    }

    @Test
    fun `delay age chip in top right shows minutes and turns yellow then red`() {
        fun chip(min: Long?) = TileText.delayChip(TileModel(TileState.NoRoute, delayAgeMin = min))
        assertEquals(null, chip(null))
        assertEquals(Seg("${TileText.CLOCK} 3'", null), chip(3))
        assertEquals(Tint.YELLOW, chip(15)!!.tint)
        assertEquals(Tint.RED, chip(31)!!.tint)
        assertEquals("${TileText.CLOCK} >1h", chip(75)!!.text)
        assertEquals(Seg("${TileText.CLOCK} —", Tint.RED), chip(TileModel.NEVER))
        val st = TileState.Ok(station, 300.0, listOf(option(1, 10, TrainClass.SURE)), 0, false)
        val tall = TileText.lines(TileModel(st, stationName = { "Warszawa Śródmieście" }, delayAgeMin = 3), TileLayout.TALL)
        assertEquals(Seg("${TileText.CLOCK} 3'", null), tall[0].trailing)
        assertEquals("Mrozy → W-wa Śródm.", tall[0].text)
        assertEquals(listOf(listOf(Seg("Mrozy → W-wa Śr."))), tall[0].variants)
        assertEquals(null, tall[1].trailing)
        assertEquals(Seg("${TileText.CLOCK} …"), TileText.delayChip(TileModel(TileState.NoRoute, delayAgeMin = 3, delayRefreshing = true)))
    }
}
