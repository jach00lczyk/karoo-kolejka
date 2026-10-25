package pl.kolejka.karoo.domain.geo

import pl.kolejka.karoo.domain.model.Station
import kotlin.math.cos
import kotlin.math.floor

/** Siatka ~0,02° do szybkiego wyszukiwania stacji w promieniu (SPEC 6.3.2). */
class SpatialIndex(stations: List<Station>, private val cellDeg: Double = 0.02) {
    private val cells: Map<Long, List<Station>> = stations.groupBy { key(cellOf(it.pos.lat), cellOf(it.pos.lon)) }

    private fun cellOf(v: Double) = floor(v / cellDeg).toInt()
    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    /** Stacje w promieniu [radiusM] od [p], posortowane rosnąco po odległości. */
    fun within(p: LatLon, radiusM: Double): List<Pair<Station, Double>> =
        inBox(p.lat - degLat(radiusM), p.lat + degLat(radiusM), p.lon - degLon(radiusM, p.lat), p.lon + degLon(radiusM, p.lat))
            .map { it to haversineM(p, it.pos) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }

    fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Station> {
        val out = ArrayList<Station>()
        for (a in cellOf(minLat)..cellOf(maxLat)) {
            for (b in cellOf(minLon)..cellOf(maxLon)) {
                cells[key(a, b)]?.forEach {
                    if (it.pos.lat in minLat..maxLat && it.pos.lon in minLon..maxLon) out += it
                }
            }
        }
        return out
    }

    companion object {
        fun degLat(m: Double) = m / 111_320.0
        fun degLon(m: Double, lat: Double) = m / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    }
}
