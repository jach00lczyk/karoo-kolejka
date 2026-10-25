package pl.kolejka.karoo.domain.geo

import pl.kolejka.karoo.domain.model.Station
import kotlin.math.hypot

/** Stacja w pobliżu mety (SPEC 6.3). */
data class FinishCandidate(val station: Station, val distanceM: Double)

/**
 * Stacja po drodze (SPEC 6.6). [occurrencesM] — pozycje wzdłuż trasy (m od startu) wszystkich
 * przejść obok stacji (pętla może mijać tę samą stację kilka razy).
 */
data class StationOnRoute(val station: Station, val distanceFromRouteM: Double, val occurrencesM: List<Double>) {
    /** Najbliższe przejście przed bieżącą pozycją; gdy wszystkie minięte — ostatnie. */
    fun positionAhead(currentM: Double): Double = occurrencesM.firstOrNull { it >= currentM } ?: occurrencesM.last()

    fun isPassed(currentM: Double): Boolean = occurrencesM.all { it < currentM }
}

/** Wynik geometrii liczony raz na routeKey (SPEC 5.1, 6.6.4). */
data class RouteGeometry(
    val finish: LatLon,
    val lengthM: Double,
    val finishCandidates: List<FinishCandidate>,
    val stationsOnRoute: List<StationOnRoute>,
    /** Najbliższa stacja od mety (gdy brak kandydatów ≤ R): do komunikatu „najbliższa 2,4 km". */
    val nearestToFinish: FinishCandidate?,
)

object RouteAnalyzer {
    /** Odstęp wzdłuż trasy, powyżej którego dwa zbliżenia do stacji są osobnymi przejściami. */
    private const val SEPARATE_PASS_M = 2_000.0
    private const val NEAREST_SEARCH_M = 20_000.0

    /**
     * @param points punkty trasy w kierunku jazdy (ostatni = meta), już po ewentualnym odwróceniu.
     * @param destination dla nawigacji do pinezki — współrzędne celu zamiast ostatniego punktu.
     */
    fun analyze(
        points: List<LatLon>,
        index: SpatialIndex,
        finishRadiusM: Double,
        routeRadiusM: Double,
        destination: LatLon? = null,
        simplifyToleranceM: Double = 20.0,
    ): RouteGeometry? {
        val pts = Polyline.simplify(points.filter { it.isValid }, simplifyToleranceM)
        val finish = destination ?: pts.lastOrNull() ?: return null
        val finishCandidates = index.within(finish, finishRadiusM).map { FinishCandidate(it.first, it.second) }
        val nearest = finishCandidates.firstOrNull()
            ?: index.within(finish, NEAREST_SEARCH_M).firstOrNull()?.let { FinishCandidate(it.first, it.second) }
        if (pts.size < 2) {
            return RouteGeometry(finish, 0.0, finishCandidates, emptyList(), nearest)
        }

        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + haversineM(pts[i - 1], pts[i])

        // Prefiltr: bbox trasy + promień.
        val minLat = pts.minOf { it.lat } - SpatialIndex.degLat(routeRadiusM)
        val maxLat = pts.maxOf { it.lat } + SpatialIndex.degLat(routeRadiusM)
        val midLat = (minLat + maxLat) / 2
        val minLon = pts.minOf { it.lon } - SpatialIndex.degLon(routeRadiusM, midLat)
        val maxLon = pts.maxOf { it.lon } + SpatialIndex.degLon(routeRadiusM, midLat)
        val finishIds = finishCandidates.map { it.station.id }.toSet()

        val onRoute = index.inBox(minLat, maxLat, minLon, maxLon)
            .filter { it.id !in finishIds }
            .mapNotNull { st -> passes(st, pts, cum, routeRadiusM) }
            .sortedBy { it.occurrencesM.first() }

        return RouteGeometry(finish, cum.last(), finishCandidates, onRoute, nearest)
    }

    /** Wszystkie przejścia trasy w promieniu stacji: lokalne minima odległości, rozdzielone ≥ 2 km. */
    private fun passes(st: Station, pts: List<LatLon>, cum: DoubleArray, radiusM: Double): StationOnRoute? {
        // Projekcja wokół stacji: dokładna lokalnie niezależnie od długości trasy.
        val proj = LocalProjection(st.pos)
        val xy = pts.map { proj.project(it) }
        val px = 0.0
        val py = 0.0
        var best = Double.MAX_VALUE
        val occurrences = ArrayList<Double>()
        // Bieżące przejście: najmniejsza odległość i jej pozycja, dopóki trasa jest w promieniu.
        var passMin = Double.MAX_VALUE
        var passPos = 0.0
        var lastInRangeEnd = -Double.MAX_VALUE
        for (i in 0 until xy.size - 1) {
            val (ax, ay) = xy[i]
            val (bx, by) = xy[i + 1]
            val t = segmentParam(px, py, ax, ay, bx, by)
            val d = hypot(px - (ax + (bx - ax) * t), py - (ay + (by - ay) * t))
            val pos = cum[i] + (cum[i + 1] - cum[i]) * t
            if (d <= radiusM) {
                if (passMin != Double.MAX_VALUE && pos - lastInRangeEnd > SEPARATE_PASS_M) {
                    occurrences += passPos
                    passMin = Double.MAX_VALUE
                }
                if (d < passMin) {
                    passMin = d
                    passPos = pos
                }
                lastInRangeEnd = pos
                best = minOf(best, d)
            } else if (passMin != Double.MAX_VALUE && cum[i] - lastInRangeEnd > SEPARATE_PASS_M) {
                occurrences += passPos
                passMin = Double.MAX_VALUE
            }
        }
        if (passMin != Double.MAX_VALUE) occurrences += passPos
        return if (occurrences.isEmpty()) null else StationOnRoute(st, best, occurrences)
    }
}
