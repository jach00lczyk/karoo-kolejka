package pl.kolejka.karoo.domain.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {
    val isValid: Boolean
        get() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}

const val EARTH_RADIUS_M = 6_371_000.0

fun haversineM(a: LatLon, b: LatLon): Double {
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dp = p2 - p1
    val dl = Math.toRadians(b.lon - a.lon)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun polylineLengthM(points: List<LatLon>): Double {
    var sum = 0.0
    for (i in 1 until points.size) sum += haversineM(points[i - 1], points[i])
    return sum
}
