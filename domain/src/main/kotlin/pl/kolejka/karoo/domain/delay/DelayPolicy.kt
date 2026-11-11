package pl.kolejka.karoo.domain.delay

import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import java.time.Duration
import java.time.Instant

/**
 * Kiedy pytać API o opóźnienia (SPEC 7.3). Bez własnego timera: wołane przy przeliczeniu
 * kafelka (minutowy tik) albo z przycisku. Pociągi z kafelka sprawdzamy zawsze, niezależnie
 * od odległości odjazdu — rzadziej, im dalej (żeby odwołanie wcześniej w dniu było widać). Zwrócone kursy są od razu liczone jako pobrane
 * (także w limicie godzinowym) — wywołujący musi o nie zapytać.
 *
 * Czas z zegara ścianowego bywa cofany po starcie Karoo (docs/device-findings.md) — wpisy
 * „z przyszłości" traktujemy jak świeże, a w limicie godzinowym jak bieżące.
 */
class DelayPolicy(
    /** Progresywnie: im bliżej odjazdu, tym częściej. Pierwszy pasujący próg wygrywa. */
    private val steps: List<Step> = DEFAULT_STEPS,
    /** Odjazd dalej niż ostatni próg — co tyle (odwołanie rano też ma być widać z daleka). */
    private val farInterval: Duration = Duration.ofMinutes(30),
    /** Pociąg, który właśnie odjechał (wg oczekiwanego odjazdu), jeszcze nas interesuje. */
    private val recentPast: Duration = Duration.ofMinutes(5),
    /**
     * Kafel: do 3 pociągów z mety + plan B + 2 stacje po drodze. Typowo 1–2 pociągi co 5 min,
     * 2 co 10 min, reszta co 30 min ≈ 40/h; zapas na zmiany trasy i przycisk (pakiet Basic: 100/h).
     */
    private val maxPerHour: Int = 60,
    private val manualCooldown: Duration = Duration.ofMinutes(1),
    private val manualLimit: Int = 6,
) {
    private val lastFetch = HashMap<TrainRef, Instant>()
    private val requests = ArrayDeque<Instant>()
    private var lastTrip: Any? = null

    /** Próg: odjazd w ciągu [within] → sprawdzaj co [every]. */
    data class Step(val within: Duration, val every: Duration)

    /** Automatycznie: wszystkie pociągi z kafelka, każdy z interwałem zależnym od czasu do odjazdu. */
    @Synchronized
    fun dueAutomatic(trains: List<Train>, now: Instant): List<TrainRef> =
        take(candidates(trains, now).filter { (ref, dep) -> isOlderThan(ref, intervalFor(dep, now), now) }.map { it.first }, now)

    /** Przycisk: do [manualLimit] najbliższych pociągów, nie częściej niż raz na [manualCooldown] na pociąg. */
    @Synchronized
    fun dueManual(trains: List<Train>, now: Instant): List<TrainRef> =
        take(candidates(trains, now).map { it.first }.take(manualLimit).filter { isOlderThan(it, manualCooldown, now) }, now)

    /** Czy przycisk ma w ogóle o co pytać (nadchodzący pociąg z ID kursu) — do rozróżnienia komunikatów. */
    @Synchronized
    fun hasManualCandidates(trains: List<Train>, now: Instant): Boolean = candidates(trains, now).isNotEmpty()

    /**
     * Wybrana trasa ([trip] = `RouteKey.trip()`). Gdy inna niż poprzednio (także pierwsza po
     * starcie) — przy najbliższym przeliczeniu sprawdź od razu wszystkie pokazane pociągi.
     * Ta sama trasa (reroute, ponowne podłączenie kafelka, powrót po zakończeniu nawigacji) —
     * nic. Pomija pobrane w ostatniej [manualCooldown], żeby przełączanie tras tam i z powrotem
     * nie zjadło limitu. Zwraca, czy wymuszono.
     */
    @Synchronized
    fun onRoute(trip: Any, now: Instant): Boolean {
        if (trip == lastTrip) return false
        lastTrip = trip
        lastFetch.entries.removeIf { Duration.between(it.value, now) >= manualCooldown }
        return true
    }

    private fun intervalFor(departure: Instant, now: Instant): Duration {
        val ahead = Duration.between(now, departure)
        return steps.firstOrNull { ahead < it.within }?.every ?: farInterval
    }

    /** Ile zapytań zostało w bieżącej godzinie. */
    @Synchronized
    fun budget(now: Instant): Int {
        while (requests.isNotEmpty() && Duration.between(requests.first(), now) > HOUR) requests.removeFirst()
        return (maxPerHour - requests.size).coerceAtLeast(0)
    }

    /** Nadchodzące pociągi z ID kursu, od najbliższego (limit godzinowy obcina najdalsze). */
    private fun candidates(trains: List<Train>, now: Instant): List<Pair<TrainRef, Instant>> =
        trains.asSequence()
            .filter { it.ref != null && !it.expectedDeparture.isBefore(now.minus(recentPast)) }
            .sortedBy { it.expectedDeparture }
            .distinctBy { it.ref }
            .map { it.ref!! to it.expectedDeparture }
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

    companion object {
        private val HOUR: Duration = Duration.ofHours(1)

        /** < 30 min do odjazdu → co 5 min, < 90 min → co 10 min (dalej [farInterval]). */
        val DEFAULT_STEPS = listOf(
            Step(Duration.ofMinutes(30), Duration.ofMinutes(5)),
            Step(Duration.ofMinutes(90), Duration.ofMinutes(10)),
        )
    }
}
