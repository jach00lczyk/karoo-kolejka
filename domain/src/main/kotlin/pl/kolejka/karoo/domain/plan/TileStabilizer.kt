package pl.kolejka.karoo.domain.plan

import pl.kolejka.karoo.domain.model.TrainKey
import java.time.Duration
import java.time.Instant

/**
 * Histereza głównego pociągu i wygładzanie v_req (SPEC 6.4), żeby kafelek nie skakał
 * przy wahaniach ETA. Stanowy: jedna instancja na routeKey; [reset] przy zmianie trasy.
 *
 * - zmiana na późniejszy pociąg dopiero, gdy zapas obecnego < −1 min przez ≥ 2 min,
 * - powrót do wcześniejszego dopiero, gdy jego zapas ≥ +2 min,
 * - v_req jako średnia krocząca z 60 s.
 */
class TileStabilizer {
    private var current: TrainKey? = null
    private var belowSince: Instant? = null
    private val samples = HashMap<TrainKey, ArrayDeque<Pair<Instant, Double>>>()

    fun reset() {
        current = null
        belowSince = null
        samples.clear()
    }

    fun apply(state: TileState, now: Instant): TileState {
        if (state !is TileState.Ok) {
            if (state !is TileState.Computing) reset()
            return state
        }
        val smoothed = state.options.map { it.copy(vReqKmh = smooth(it.train.key, it.vReqKmh, now)) }
        val proposed = state.mainIndex
        val currentIdx = current?.let { key -> smoothed.indexOfFirst { it.train.key == key } }?.takeIf { it >= 0 }

        val mainIdx = when {
            currentIdx == null || currentIdx == proposed -> {
                if (currentIdx == proposed) trackBelow(smoothed[proposed].slack, now)
                else belowSince = null
                proposed
            }
            proposed > currentIdx -> {
                // Proponowany późniejszy: przełącz dopiero po 2 min zapasu < −1 min.
                if (trackBelow(smoothed[currentIdx].slack, now)) proposed else currentIdx
            }
            else -> {
                // Proponowany wcześniejszy: wróć tylko przy zapasie ≥ +2 min.
                if (smoothed[proposed].slack >= RETURN_SLACK) proposed else currentIdx
            }
        }
        if (mainIdx != currentIdx) belowSince = null
        current = smoothed[mainIdx].train.key
        return state.copy(options = smoothed, mainIndex = mainIdx)
    }

    /** Zwraca true, gdy zapas jest < −1 min nieprzerwanie od ≥ 2 min. */
    private fun trackBelow(slack: Duration, now: Instant): Boolean {
        if (slack >= SWITCH_SLACK) {
            belowSince = null
            return false
        }
        val since = belowSince ?: now.also { belowSince = it }
        return Duration.between(since, now) >= SWITCH_AFTER
    }

    private fun smooth(key: TrainKey, value: Double?, now: Instant): Double? {
        val q = samples.getOrPut(key) { ArrayDeque() }
        if (value == null) {
            q.clear()
            return null
        }
        q.addLast(now to value)
        while (q.isNotEmpty() && Duration.between(q.first().first, now) > SMOOTHING) q.removeFirst()
        return q.sumOf { it.second } / q.size
    }

    companion object {
        val SWITCH_SLACK: Duration = Duration.ofMinutes(-1)
        val SWITCH_AFTER: Duration = Duration.ofMinutes(2)
        val RETURN_SLACK: Duration = Duration.ofMinutes(2)
        val SMOOTHING: Duration = Duration.ofSeconds(60)
    }
}
