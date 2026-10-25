package pl.kolejka.karoo.domain.timetable

import pl.kolejka.karoo.domain.model.Connection
import pl.kolejka.karoo.domain.model.Station
import java.time.LocalDate

/** Rozkład w pamięci — testy i tryb symulacji. Semantyka identyczna jak zapytanie SQLite. */
class InMemoryTimetable(
    override val stations: List<Station>,
    private val trips: List<TripData>,
    override val version: String = "in-memory",
) : Timetable {

    data class StopData(
        val stationId: Int,
        val arrS: Int,
        val depS: Int = arrS,
        val platform: String? = null,
        val track: String? = null,
        val pickup: Boolean = true,
        val dropOff: Boolean = true,
    )

    data class TripData(
        val id: Int,
        val dates: Set<LocalDate>,
        val stops: List<StopData>,
        val agency: String = "KM",
        val routeShortName: String? = null,
        val headsign: String? = null,
        val isBus: Boolean = false,
    )

    override fun connections(from: Set<Int>, targets: Set<Int>, serviceDate: LocalDate, depRange: IntRange): List<Connection> {
        val out = ArrayList<Connection>()
        for (t in trips) {
            if (serviceDate !in t.dates) continue
            t.stops.forEachIndexed { i, a ->
                if (a.stationId !in from || !a.pickup || a.depS !in depRange) return@forEachIndexed
                for (b in t.stops.subList(i + 1, t.stops.size)) {
                    if (b.stationId in targets && b.dropOff) {
                        out += Connection(
                            tripId = t.id, serviceDate = serviceDate,
                            fromStationId = a.stationId, depS = a.depS,
                            toStationId = b.stationId, arrS = b.arrS,
                            platform = a.platform, track = a.track,
                            agency = t.agency, routeShortName = t.routeShortName, headsign = t.headsign, isBus = t.isBus,
                        )
                    }
                }
            }
        }
        return out.sortedWith(compareBy({ it.depS }, { it.arrS }))
    }
}
