package pl.kolejka.karoo.domain.plan

import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.model.Train
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Stacja po drodze z ETA i pierwszym pociągiem, na który zdążysz (SPEC 6.6, 8.2). */
data class UpcomingStation(
    val station: Station,
    val alongM: Double,
    val distanceFromRouteM: Double,
    /** Dystans do stacji wzdłuż trasy od bieżącej pozycji [m]. */
    val distanceM: Double,
    val eta: Instant,
    val train: Train?,
)

/**
 * Najbliższe nieminięte stacje po drodze. ETA stacji z prędkości spójnej z Karoo:
 * pozostały dystans / czas do mety (SPEC 6.6.3).
 */
class UpcomingStations(private val planner: TrainPlanner, private val clock: Clock) {

    fun compute(
        geometry: RouteGeometry,
        routeDistanceM: Double,
        progress: RideProgress,
        finishEta: Eta,
        settings: Settings,
        targets: TargetGroups,
        limit: Int,
    ): List<UpcomingStation> {
        val now = clock.instant()
        val remaining = progress.remainingM?.takeIf { it.isFinite() && it >= 0 } ?: routeDistanceM
        val current = (routeDistanceM - remaining).coerceAtLeast(0.0)
        // Poza trasą (przed jej startem) dystans do mety zawiera dojazd do trasy.
        val offRoute = (remaining - routeDistanceM).coerceAtLeast(0.0)
        val toFinish = Duration.between(now, finishEta.instant).seconds
        val v = if (toFinish > 0 && remaining > 0) remaining / toFinish else settings.fallbackSpeedKmh / 3.6
        return geometry.stationsOnRoute
            .filter { !it.isPassed(current) && targets.groupOf(it.station.id) == null }
            .sortedBy { it.positionAhead(current) }
            .take(limit)
            .map { s ->
                val pos = s.positionAhead(current)
                val distance = pos - current + offRoute
                val eta = now.plusSeconds((distance / v).toLong())
                val ready = eta
                    .plusSeconds((s.distanceFromRouteM / (settings.accessSpeedKmh / 3.6)).toLong())
                    .plus(Duration.ofMinutes(settings.bufferMin.toLong()))
                val train = planner.trainsFrom(s.station.id, ready, TrainPlanner.serviceDayEnd(ready), targets).firstOrNull()
                UpcomingStation(s.station, pos, s.distanceFromRouteM, distance, eta, train)
            }
    }
}
