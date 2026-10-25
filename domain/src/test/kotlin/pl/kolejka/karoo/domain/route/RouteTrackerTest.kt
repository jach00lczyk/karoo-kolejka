package pl.kolejka.karoo.domain.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.RouteGeometry

/** Testy sekwencji z SPEC 5.2 (część domenowa; P1 — [NavResubscribeWatchdogTest]). */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteTrackerTest {
    private val routeA = NavInput.Route("aaaa", 10_000.0, reversed = false, name = "A")
    private val routeB = NavInput.Route("bbbb", 20_000.0, reversed = false, name = "B")
    private val config = MutableStateFlow(GeometryConfig(1000.0, 1000.0))

    private class Harness(scope: TestScope, private val config: MutableStateFlow<GeometryConfig>) {
        val computed = mutableListOf<NavInput>()
        val nav = MutableStateFlow<NavInput>(NavInput.Idle)
        val states = mutableListOf<RouteState>()
        val tracker = RouteTracker(
            analyze = { input, _ ->
                computed += input
                RouteGeometry(LatLon(52.0, 21.0), 1.0, emptyList(), emptyList(), null)
            },
            computeDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        init {
            scope.backgroundScope.launch { tracker.states(nav, config).toList(states) }
        }

        fun readyKeys() = states.filterIsInstance<RouteState.Ready>().map { (it.input as? NavInput.Route)?.name ?: "D" }
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun `idle A B idle A uses cache for A`() = runTest {
        val h = Harness(this, config)
        settle()
        h.nav.value = routeA; settle()
        h.nav.value = routeB; settle()
        h.nav.value = NavInput.Idle; settle()
        h.nav.value = routeA; settle()
        assertEquals(listOf("A", "B"), h.computed.map { (it as NavInput.Route).name })
        assertEquals(listOf("A", "B", "A"), h.readyKeys())
        assertEquals(RouteState.NoRoute, h.states.first())
        assertTrue(h.states.count { it == RouteState.NoRoute } == 2)
    }

    @Test
    fun `rapid switching A B five times in two seconds computes once`() = runTest {
        val h = Harness(this, config)
        settle()
        repeat(10) { i ->
            h.nav.value = if (i % 2 == 0) routeA else routeB
            advanceTimeBy(200)
        }
        settle()
        assertEquals(listOf("B"), h.computed.map { (it as NavInput.Route).name })
        assertEquals(listOf("B"), h.readyKeys())
    }

    @Test
    fun `reversed route is a new route`() = runTest {
        val h = Harness(this, config)
        h.nav.value = routeA; settle()
        h.nav.value = routeA.copy(reversed = true); settle()
        assertEquals(2, h.computed.size)
        assertEquals(true, (h.computed[1] as NavInput.Route).reversed)
    }

    @Test
    fun `rejoin does not trigger recomputation`() = runTest {
        val h = Harness(this, config)
        h.nav.value = routeA; settle()
        h.nav.value = routeA.copy(rejoinPolyline = "rrrr"); settle()
        h.nav.value = routeA.copy(rejoinPolyline = null); settle()
        assertEquals(1, h.computed.size)
        assertEquals(1, h.readyKeys().size)
    }

    @Test
    fun `pin destination then route`() = runTest {
        val h = Harness(this, config)
        h.nav.value = NavInput.Destination(LatLon(52.25, 21.0), "dddd"); settle()
        h.nav.value = routeA; settle()
        assertEquals(listOf("D", "A"), h.readyKeys())
    }

    @Test
    fun `idle is reported immediately without debounce`() = runTest {
        val h = Harness(this, config)
        h.nav.value = routeA; settle()
        h.nav.value = NavInput.Idle
        runCurrent()
        assertEquals(RouteState.NoRoute, h.states.last())
    }

    @Test
    fun `computing emitted before ready and changing radius recomputes`() = runTest {
        val h = Harness(this, config)
        h.nav.value = routeA; settle()
        val i = h.states.indexOfFirst { it is RouteState.Computing }
        assertTrue(h.states[i + 1] is RouteState.Ready)
        config.value = GeometryConfig(500.0, 1000.0); settle()
        assertEquals(2, h.computed.size)
    }

    @Test
    fun `lru keeps at most four routes`() {
        val c = LruCache<Int, String>(4)
        (1..5).forEach { c[it] = "v$it" }
        assertEquals(4, c.size)
        assertEquals(null, c[1])
        assertEquals("v5", c[5])
    }
}
