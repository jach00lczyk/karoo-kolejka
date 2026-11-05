package pl.kolejka.karoo.domain.delay

import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import java.time.Duration
import java.time.Instant

/**
 * Kiedy pytać API o opóźnienia (SPEC 7.3). Bez własnego timera: wołane przy przeliczeniu
 * kafelka (minutowy tik) albo z przycisku. Zwrócone kursy są od razu liczone jako pobrane
 * (także w limicie godzinowym) — wywołujący musi o nie zapytać.
 *
 * Czas z zegara ścianowego bywa cofany po starcie Karoo (docs/device-findings.md) — wpisy
 * „z przyszłości" traktujemy jak świeże, a w limicie godzinowym jak bieżące.
 */
class DelayPolicy(
    private val interval: Duration = Duration.ofMinutes(10),
    private val horizon: Duration = Duration.ofMinutes(90),
    private val manualHorizon: Duration = Duration.ofHours(3),
    /** Pociąg, który właśnie odjechał (wg oczekiwanego odjazdu), jeszcze nas interesuje. */
    private val recentPast: Duration = Duration.ofMinutes(5),
    /** Kafel: do 3 pociągów z mety + plan B + 2 stacje po drodze, co 10 min (pakiet Basic: 100/h). */
    private val maxPerHour: Int = 40,
    private val manualCooldown: Duration = Duration.ofMinutes(1),
    private val manualLimit: Int = 6,
) {
    private val lastFetch = HashMap<TrainRef, Instant>()
    private val requests = ArrayDeque<Instant>()

    /** Automatycznie: pociągi z kafelka z odjazdem w ciągu [horizon] i danymi starszymi niż [interval]. */
    @Synchronized
    fun dueAutomatic(trains: List<Train>, now: Instant): List<TrainRef> =
        take(candidates(trains, now, horizon).filter { isOlderThan(it, interval, now) }, now)

    /** Przycisk: do [manualLimit] najbliższych pociągów, nie częściej niż raz na [manualCooldown] na pociąg. */
    @Synchronized
    fun dueManual(trains: List<Train>, now: Instant): List<TrainRef> =
        take(candidates(trains, now, manualHorizon).take(manualLimit).filter { isOlderThan(it, manualCooldown, now) }, now)

    /** Ile zapytań zostało w bieżącej godzinie. */
    @Synchronized
    fun budget(now: Instant): Int {
        while (requests.isNotEmpty() && Duration.between(requests.first(), now) > HOUR) requests.removeFirst()
        return (maxPerHour - requests.size).coerceAtLeast(0)
    }

    private fun candidates(trains: List<Train>, now: Instant, ahead: Duration): List<TrainRef> =
        trains.asSequence()
            .filter { it.ref != null }
            .filter { t ->
                val dep = t.expectedDeparture
                !dep.isBefore(now.minus(recentPast)) && !dep.isAfter(now.plus(ahead))
            }
            .sortedBy { it.expectedDeparture }
            .mapNotNull { it.ref }
            .distinct()
            .toList()

    private fun isOlderThan(ref: TrainRef, age: Duration, now: Instant): Boolean {
        val last = lastFetch[ref] ?: return true
        return Duration.between(last, now) >= age
    }

    private fun take(refs: List<TrainRef>, now: Instant): List<TrainRef> {
        val chosen = refs.take(budget(now))
        for (r in chosen) {
            lastFetch[r] = now
            requests.addLast(now)
        }
        // Stare wpisy nie są już potrzebne (pociąg dawno odjechał).
        lastFetch.entries.removeIf { Duration.between(it.value, now) > Duration.ofHours(6) }
        return chosen
    }

    private companion object {
        val HOUR: Duration = Duration.ofHours(1)
    }
}
