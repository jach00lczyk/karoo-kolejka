package pl.kolejka.karoo.domain.route

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.RouteGeometry

/** Stan nawigacji Karoo przetłumaczony na model domeny (bez typów karoo-ext). */
sealed interface NavInput {
    data object Idle : NavInput

    data class Route(
        val polyline: String,
        val routeDistanceM: Double,
        val reversed: Boolean,
        val name: String = "",
        /** Chwilowy objazd do tej samej trasy — nie zmienia mety, nie wchodzi do klucza. */
        val rejoinPolyline: String? = null,
    ) : NavInput

    data class Destination(val destination: LatLon, val polyline: String) : NavInput
}

/**
 * Klucz trasy (SPEC 5.1): polyline + reversed + typ nawigacji + cel pinezki. Porównanie dokładne
 * zamiast skrótu — LRU trzyma tylko 4 klucze, a ciągi są współdzielone, nie kopiowane.
 */
data class RouteKey(val type: Char, val polyline: String, val reversed: Boolean, val destination: LatLon?) {
    override fun toString() = "RouteKey($type, len=${polyline.length}, hash=${polyline.hashCode()}, reversed=$reversed, dest=$destination)"
}

fun NavInput.routeKey(): RouteKey? = when (this) {
    NavInput.Idle -> null
    is NavInput.Route -> RouteKey('R', polyline, reversed, null)
    is NavInput.Destination -> RouteKey('D', polyline, false, destination)
}

/** Parametry wpływające na geometrię; ich zmiana unieważnia wynik dla tej samej trasy. */
data class GeometryConfig(val finishRadiusM: Double, val routeRadiusM: Double)

sealed interface RouteState {
    data object NoRoute : RouteState
    data class Computing(val key: RouteKey) : RouteState
    data class Ready(val key: RouteKey, val input: NavInput, val geometry: RouteGeometry) : RouteState
    /** Polyline nie dała się zdekodować — traktowane jak brak trasy, ale logowane. */
    data class Invalid(val key: RouteKey) : RouteState
}

/**
 * Maszyna stanów trasy (SPEC 5.1): debounce 500 ms, anulowanie obliczeń przy zmianie klucza
 * (flatMapLatest), LRU 4 wyników geometrii, Idle → NoRoute natychmiast.
 */
class RouteTracker(
    private val analyze: (NavInput, GeometryConfig) -> RouteGeometry?,
    private val computeDispatcher: CoroutineDispatcher,
    private val debounceMs: Long = 500,
    cacheSize: Int = 4,
) {
    private val cache = LruCache<Pair<RouteKey, GeometryConfig>, RouteGeometry>(cacheSize)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun states(nav: Flow<NavInput>, config: Flow<GeometryConfig>): Flow<RouteState> =
        combine(nav, config.distinctUntilChanged()) { n, c -> Request(n, c) }
            .distinctUntilChanged { a, b -> a.key == b.key && a.config == b.config }
            .debounce { if (it.input is NavInput.Idle) 0L else debounceMs }
            .distinctUntilChanged { a, b -> a.key == b.key && a.config == b.config }
            .flatMapLatest { req -> stateFor(req) }

    private fun stateFor(req: Request): Flow<RouteState> = flow {
        val key = req.key
        if (key == null) {
            emit(RouteState.NoRoute)
            return@flow
        }
        val cached = synchronized(cache) { cache[key to req.config] }
        if (cached != null) {
            emit(RouteState.Ready(key, req.input, cached))
            return@flow
        }
        emit(RouteState.Computing(key))
        val geometry = withContext(computeDispatcher) { analyze(req.input, req.config) }
        if (geometry == null) {
            emit(RouteState.Invalid(key))
        } else {
            synchronized(cache) { cache[key to req.config] = geometry }
            emit(RouteState.Ready(key, req.input, geometry))
        }
    }

    private data class Request(val input: NavInput, val config: GeometryConfig) {
        val key: RouteKey? = input.routeKey()
    }
}

class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    val size: Int get() = map.size
}
