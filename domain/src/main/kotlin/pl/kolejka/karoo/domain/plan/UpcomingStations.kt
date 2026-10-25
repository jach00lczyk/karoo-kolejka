package pl.kolejka.karoo.domain.plan

import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.model.Train
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Stacja po drodze z ETA i pociągami, na które zdążysz (SPEC 6.6, 8.2). */
data class UpcomingStation(
    val station: Station,
    val alongM: Double,
    val distanceFromRouteM: Double,
    /** Dystans do stacji wzdłuż trasy od bieżącej pozycji [m]; ujemny dla minionych. */
    val distanceM: Double,
    val eta: Instant,
    /** Pociągi osiągalne z tej stacji (ETA + dojazd + zapas), od najwcześniejszego. */
    val trains: List<Train>,
    val passed: Boolean = false,
) {
    val train: Train? get() = trains.firstOrNull()
}

/**
 * Najbliższe nieminięte stacje po drodze. ETA stacji z prędkości spójnej z Karoo:
 * pozostały dystans / czas do mety (SPEC 6.6.3).
 */
class UpcomingStations(private val planner: TrainPlanner, private val clock: Clock) {

    /**
     * @param limit ile nieminiętych stacji (od najbliższej)
     * @param trainsPerStation ile pociągów na stację (kafel: 1, zakładka „Po drodze": 4)
     * @param includePassed czy dołączyć minięte stacje (na końcu listy, SPEC 8.2)
     */
    fun compute(
        geometry: RouteGeometry,
        routeDistanceM: Double,
        progress: RideProgress,
        finishEta: Eta,
        settings: Settings,
        targets: TargetGroups,
        limit: Int,
        trainsPerStation: Int = 1,
        includePassed: Boolean = false,
    ): List<UpcomingStation> {
        val now = clock.instant()
        val remaining = progress.remainingM?.takeIf { it.isFinite() && it >= 0 } ?: routeDistanceM
        val current = (routeDistanceM - remaining).coerceAtLeast(0.0)
        // Poza trasą (przed jej startem) dystans do mety zawiera dojazd do trasy.
        val offRoute = (remaining - routeDistanceM).coerceAtLeast(0.0)
        val toFinish = Duration.between(now, finishEta.instant).seconds
        val v = if (toFinish > 0 && remaining > 0) remaining / toFinish else settings.fallbackSpeedKmh / 3.6
        val candidates = geometry.stationsOnRoute.filter { targets.groupOf(it.station.id) == null }
        val ahead = candidates
            .filter { !it.isPassed(current) }
            .sortedBy { it.positionAhead(current) }
            .take(limit)
            .map { s ->
                val pos = s.positionAhead(current)
                val distance = pos - current + offRoute
                val eta = now.plusSeconds((distance / v).toLong())
                val ready = eta
                    .plusSeconds((s.distanceFromRouteM / (settings.accessSpeedKmh / 3.6)).toLong())
                    .plus(Duration.ofMinutes(settings.bufferMin.toLong()))
                val trains = planner.trainsFrom(s.station.id, ready, TrainPlanner.serviceDayEnd(ready), targets).take(trainsPerStation)
                UpcomingStation(s.station, pos, s.distanceFromRouteM, distance, eta, trains)
            }
        if (!includePassed) return ahead
        val passed = candidates
            .filter { it.isPassed(current) }
            .sortedByDescending { it.occurrencesM.last() }
            .map { s ->
                val pos = s.occurrencesM.last()
                UpcomingStation(s.station, pos, s.distanceFromRouteM, pos - current, now, emptyList(), passed = true)
            }
        return ahead + passed
    }
}
