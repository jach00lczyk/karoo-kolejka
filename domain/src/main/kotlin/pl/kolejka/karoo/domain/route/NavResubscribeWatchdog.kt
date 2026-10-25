package pl.kolejka.karoo.domain.route

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Obejście P1 (karoo-ext issue #81): gdy nawigacja startuje po subskrypcji, `OnNavigationState`
 * bywa nieemitowane. Obserwujemy pole NAVIGATION_STATE ze strumienia DISTANCE_TO_DESTINATION;
 * jeśli po jego zmianie w ciągu [timeoutMs] nie przyjdzie zdarzenie nawigacji, emitujemy
 * sygnał ponownej subskrypcji (`removeConsumer` + `addConsumer`).
 */
class NavResubscribeWatchdog(private val timeoutMs: Long = 5_000) {

    fun triggers(navStateField: Flow<Double?>, navEvents: Flow<Any>): Flow<Unit> = channelFlow {
        var eventCount = 0L
        launch { navEvents.collect { eventCount++ } }
        var pending: Job? = null
        var first = true
        navStateField.filterNotNull().distinctUntilChanged().collect {
            if (first) {
                // Pierwsza wartość to stan zastany przy subskrypcji, nie zmiana.
                first = false
                return@collect
            }
            pending?.cancel()
            val seen = eventCount
            pending = launch {
                delay(timeoutMs)
                if (eventCount == seen) send(Unit)
            }
        }
    }
}
