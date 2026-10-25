package pl.kolejka.karoo.probe

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.geo.Polyline
import pl.kolejka.karoo.domain.geo.polylineLengthM
import pl.kolejka.karoo.extension.consumerFlow
import pl.kolejka.karoo.extension.streamDataFlow
import pl.kolejka.karoo.log.FileLog

/**
 * FAZA 0 — spike. Loguje wszystko, czego SPEC 4.1 nie pozwala zgadywać: jednostki ETA/dystansu,
 * format polyline, wpływ `reversed`, kiedy ETA jest dostępne, zachowanie przy rejoin, problem P1.
 * Celowo nie oszczędza energii; zostanie usunięty po wypełnieniu docs/device-findings.md.
 */
class DeviceProbe(private val karoo: KarooSystemService, private val scope: CoroutineScope) {

    data class Snapshot(
        val nav: String = "—",
        val streams: Map<String, String> = emptyMap(),
        val ride: String = "—",
        val navEvents: Int = 0,
    )

    private var navJob: Job? = null
    private var lastNavEventAt = 0L
    private var lastNavField: Double? = null
    private val lastLogged = mutableMapOf<String, Pair<String, Long>>()

    fun start() {
        FileLog.i("PROBE start")
        subscribeNavigation("start")
        for (type in STREAMS) {
            scope.launch { karoo.streamDataFlow(type).collect { onStream(type, it) } }
        }
        scope.launch {
            karoo.consumerFlow<RideState>().collect {
                FileLog.i("PROBE RideState=$it")
                _state.update { s -> s.copy(ride = it.toString()) }
            }
        }
    }

    private fun subscribeNavigation(reason: String) {
        navJob?.cancel()
        FileLog.i("PROBE subskrypcja OnNavigationState ($reason)")
        navJob = scope.launch {
            karoo.consumerFlow<OnNavigationState>().collect { onNavigation(it.state) }
        }
    }

    private fun onNavigation(state: NavigationState) {
        lastNavEventAt = System.currentTimeMillis()
        _state.update { it.copy(navEvents = it.navEvents + 1, nav = state.javaClass.simpleName) }
        FileLog.i("PROBE OnNavigationState: $state")
        when (state) {
            is NavigationState.NavigatingRoute -> {
                describePolyline("routePolyline", state.routePolyline, state.routeDistance)
                FileLog.i("PROBE route name='${state.name}' reversed=${state.reversed} breadcrumb=${state.breadcrumb} rejoinDistance=${state.rejoinDistance}")
                state.rejoinPolyline?.let { describePolyline("rejoinPolyline", it, state.rejoinDistance ?: 0.0) }
            }
            is NavigationState.NavigatingToDestination -> {
                val d = state.destination
                FileLog.i("PROBE destination id=${d.id} lat=${d.lat} lng=${d.lng} name=${d.name} type=${d.type}")
                describePolyline("destPolyline", state.polyline, 0.0)
            }
            NavigationState.Idle -> Unit
        }
    }

    private fun describePolyline(label: String, encoded: String, expectedM: Double) {
        val detected = Polyline.detectPrecision(encoded, expectedM)
        FileLog.i("PROBE $label: len=${encoded.length} expected=${expectedM}m detectedPrecision=$detected")
        for (prec in listOf(5, 6)) {
            val pts = Polyline.decode(encoded, prec)
            if (pts.isEmpty()) {
                FileLog.i("PROBE   p$prec: nie dekoduje się")
                continue
            }
            FileLog.i(
                "PROBE   p$prec: n=${pts.size} first=${fmt(pts.first())} last=${fmt(pts.last())} " +
                    "length=${polylineLengthM(pts).toInt()}m valid=${pts.all { it.isValid }}",
            )
        }
    }

    private fun onStream(type: String, state: StreamState) {
        val now = System.currentTimeMillis()
        val text = when (state) {
            is StreamState.Streaming -> state.dataPoint.values.entries.joinToString { "${it.key.removePrefix("FIELD_").removeSuffix("_ID")}=${it.value}" }
            else -> state.javaClass.simpleName
        }
        _state.update { it.copy(streams = it.streams + (short(type) to text)) }

        // Wykrywanie P1: zmiana NAVIGATION_STATE bez zdarzenia OnNavigationState → ponowna subskrypcja.
        if (type == DataType.Type.DISTANCE_TO_DESTINATION && state is StreamState.Streaming) {
            val nav = state.dataPoint.values[DataType.Field.NAVIGATION_STATE]
            if (nav != null && nav != lastNavField) {
                FileLog.i("PROBE NAVIGATION_STATE $lastNavField → $nav (ON_ROUTE=${state.dataPoint.values[DataType.Field.ON_ROUTE]})")
                lastNavField = nav
                val changedAt = now
                scope.launch {
                    delay(5_000)
                    // Zdarzenie potrafi przyjść ~1,5 s PRZED zmianą pola (log z 24.10) — liczy się okno ±5 s.
                    if (lastNavEventAt < changedAt - 5_000) {
                        FileLog.w("PROBE P1: brak OnNavigationState 5 s po zmianie NAVIGATION_STATE — ponowna subskrypcja")
                        subscribeNavigation("P1")
                    }
                }
            }
        }

        // Log przy zmianie typu stanu, a dla Streaming najwyżej co 30 s (wystarcza do ustalenia jednostek).
        val prev = lastLogged[type]
        val kindChanged = prev == null || prev.first.substringBefore('=') != text.substringBefore('=')
        if (kindChanged || now - prev!!.second > 30_000) {
            FileLog.i("PROBE ${short(type)} @${now}: $text")
            lastLogged[type] = text to now
        }
    }

    companion object {
        val STREAMS = listOf(
            DataType.Type.TIME_OF_ARRIVAL,
            DataType.Type.TIME_TO_DESTINATION,
            DataType.Type.DISTANCE_TO_DESTINATION,
            DataType.Type.SPEED,
            DataType.Type.AVERAGE_SPEED,
        )

        private val _state = MutableStateFlow(Snapshot())
        val state: StateFlow<Snapshot> = _state.asStateFlow()

        fun short(type: String) = type.removePrefix("TYPE_").removeSuffix("_ID")
        private fun fmt(p: LatLon) = "%.6f,%.6f".format(p.lat, p.lon)
    }
}
