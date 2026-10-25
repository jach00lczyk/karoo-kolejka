package pl.kolejka.karoo.domain.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sekwencja „zdarzenie nawigacji gubione przy starcie" (P1, SPEC 5.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class NavResubscribeWatchdogTest {

    private fun scenario(block: suspend kotlinx.coroutines.test.TestScope.(MutableSharedFlow<Double?>, MutableSharedFlow<Any>, List<Unit>) -> Unit) = runTest {
        val field = MutableSharedFlow<Double?>(replay = 1)
        val events = MutableSharedFlow<Any>(extraBufferCapacity = 8)
        val triggers = mutableListOf<Unit>()
        backgroundScope.launch { NavResubscribeWatchdog(5_000).triggers(field, events).toList(triggers) }
        runCurrent()
        block(field, events, triggers)
    }

    @Test
    fun `lost navigation event triggers resubscribe after timeout`() = scenario { field, _, triggers ->
        field.emit(0.0); runCurrent() // stan zastany
        field.emit(1.0); runCurrent() // start nawigacji, brak OnNavigationState
        advanceTimeBy(4_999); runCurrent()
        assertEquals(0, triggers.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, triggers.size)
    }

    @Test
    fun `navigation event in time means no resubscribe`() = scenario { field, events, triggers ->
        field.emit(0.0); runCurrent()
        field.emit(1.0); runCurrent()
        advanceTimeBy(1_000)
        events.emit(Unit); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, triggers.size)
    }

    @Test
    fun `initial value and repeated values are not changes`() = scenario { field, _, triggers ->
        field.emit(1.0); runCurrent()
        field.emit(1.0); runCurrent()
        field.emit(null); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, triggers.size)
    }
}
