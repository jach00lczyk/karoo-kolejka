package pl.kolejka.karoo.domain.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong

/** Google Encoded Polyline z parametrem precyzji (SPEC 6.8). */
object Polyline {

    /** Zwraca pustą listę dla niepoprawnego ciągu zamiast rzucać wyjątek (dane z Karoo, SPEC 11.6). */
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val dLat = readValue(encoded, index) ?: return emptyList()
            index = dLat.second
            val dLon = readValue(encoded, index) ?: return emptyList()
            index = dLon.second
            lat += dLat.first
            lon += dLon.first
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    /**
     * Wybiera precyzję (5 lub 6), dla której zdekodowana linia ma poprawne współrzędne i długość
     * najbliższą oczekiwanej (np. `routeDistance` z Karoo). Null, gdy żadna nie pasuje.
     * Używane w fazie 0 do ustalenia formatu; patrz docs/device-findings.md.
     */
    fun detectPrecision(encoded: String, expectedLengthM: Double): Int? =
        listOf(5, 6)
            .mapNotNull { prec ->
                val pts = decode(encoded, prec)
                if (pts.size < 2 || pts.any { !it.isValid }) null
                else prec to abs(polylineLengthM(pts) - expectedLengthM)
            }
            .minByOrNull { it.second }
            ?.takeIf { expectedLengthM <= 0 || it.second / expectedLengthM < 0.5 }
            ?.first

    fun encode(points: List<LatLon>, precision: Int = 5): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            writeValue(sb, lat - prevLat)
            writeValue(sb, lon - prevLon)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun readValue(s: String, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            if (i >= s.length || shift > 60) return null
            val b = s[i++].code - 63
            if (b < 0 || b > 63) return null
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }

    private fun writeValue(sb: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }

    /**
     * Uproszczenie Douglas-Peucker z tolerancją w metrach (lokalna projekcja równoodległościowa).
     * Iteracyjnie, bez rekurencji — długie trasy nie przepełnią stosu.
     */
    fun simplify(points: List<LatLon>, toleranceM: Double = 20.0): List<LatLon> {
        if (points.size < 3) return points
        val proj = LocalProjection(points[0])
        val xs = DoubleArray(points.size)
        val ys = DoubleArray(points.size)
        points.forEachIndexed { i, p -> proj.project(p).let { (x, y) -> xs[i] = x; ys[i] = y } }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = pointSegmentDistance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx))
                stack.addLast(intArrayOf(idx, b))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/** Równoodległościowa projekcja lokalna wokół punktu odniesienia; wynik w metrach. */
class LocalProjection(ref: LatLon) {
    private val lat0 = ref.lat
    private val lon0 = ref.lon
    private val kx = Math.toRadians(1.0) * EARTH_RADIUS_M * cos(Math.toRadians(ref.lat))
    private val ky = Math.toRadians(1.0) * EARTH_RADIUS_M

    fun project(p: LatLon): Pair<Double, Double> = ((p.lon - lon0) * kx) to ((p.lat - lat0) * ky)
}

/** Odległość punktu od odcinka w płaszczyźnie. */
internal fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val t = segmentParam(px, py, ax, ay, bx, by)
    val cx = ax + (bx - ax) * t
    val cy = ay + (by - ay) * t
    return kotlin.math.hypot(px - cx, py - cy)
}

/** Parametr t∈[0,1] rzutu punktu na odcinek. */
internal fun segmentParam(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0 || abs(len2) < 1e-12) return 0.0
    return (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
}
