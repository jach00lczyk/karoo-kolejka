package pl.kolejka.karoo.domain.route

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Obejście P1 (karoo-ext issue #81): gdy nawigacja startuje po subskrypcji, `OnNavigationState`
 * bywa nieemitowane. Obserwujemy pole NAVIGATION_STATE ze strumienia DISTANCE_TO_DESTINATION;
 * jeśli w oknie ±[timeoutMs] wokół jego zmiany nie było zdarzenia nawigacji, emitujemy sygnał
 * ponownej subskrypcji (`removeConsumer` + `addConsumer`).
 *
 * Okno obejmuje też czas *przed* zmianą: na Karoo 3 `NavigatingRoute` przychodzi ~1,5 s przed
 * zmianą pola (docs/device-findings.md, 24.10.2026).
 */
class NavResubscribeWatchdog(
    private val timeoutMs: Long = 5_000,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    fun triggers(navStateField: Flow<Double?>, navEvents: Flow<Any>): Flow<Unit> = channelFlow {
        var lastEvent: TimeMark? = null
        var eventCount = 0L
        launch {
            navEvents.collect {
                eventCount++
                lastEvent = timeSource.markNow()
            }
        }
        var pending: Job? = null
        var first = true
        navStateField.filterNotNull().distinctUntilChanged().collect {
            if (first) {
                // Pierwsza wartość to stan zastany przy subskrypcji, nie zmiana.
                first = false
                return@collect
            }
            pending?.cancel()
            if (lastEvent?.let { it.elapsedNow() <= timeoutMs.milliseconds } == true) return@collect
            val seen = eventCount
            pending = launch {
                delay(timeoutMs)
                if (eventCount == seen) send(Unit)
            }
        }
    }
}
