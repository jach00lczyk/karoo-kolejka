package pl.kolejka.karoo.domain.text

import pl.kolejka.karoo.domain.model.Station
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Wyszukiwarka stacji (SPEC 8.3): bez polskich znaków („lodz" → „Łódź", „mins" → „Mińsk
 * Mazowiecki"), każde słowo zapytania musi być początkiem któregoś słowa nazwy.
 */
object StationSearch {
    private val collator: Collator = Collator.getInstance(Locale("pl", "PL")).apply { strength = Collator.SECONDARY }

    /** Ta sama normalizacja co `name_norm` w pipeline'ie (bez diakrytyków, małe litery). */
    fun normalize(s: String): String {
        val noL = s.replace('ł', 'l').replace('Ł', 'L')
        val decomposed = Normalizer.normalize(noL, Normalizer.Form.NFKD)
        return decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun matches(query: String, station: Station): Boolean {
        val q = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty()) return true
        val words = station.nameNorm.split(' ', '-', '(', ')').filter { it.isNotEmpty() }
        return q.all { part -> words.any { it.startsWith(part) } }
    }

    /** Alfabetycznie wg polskiej kolacji; przy tej samej nazwie — po przewoźniku. */
    fun sorted(stations: List<Station>): List<Station> =
        stations.sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.agencies.compareTo(b.agencies) }

    fun search(query: String, stations: List<Station>): List<Station> = stations.filter { matches(query, it) }
}
