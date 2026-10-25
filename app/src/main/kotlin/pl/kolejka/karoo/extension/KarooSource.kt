package pl.kolejka.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.route.NavInput
import pl.kolejka.karoo.domain.route.NavResubscribeWatchdog
import pl.kolejka.karoo.log.FileLog

/** Surowe wartości strumieni; null = brak (stan inny niż Streaming albo brak pola). */
data class RawStreams(
    val distanceToDestination: Double? = null,
    val timeOfArrival: Double? = null,
    val timeToDestination: Double? = null,
    val averageSpeed: Double? = null,
)

/**
 * Jedyny punkt dostępu do Karoo (SPEC 2, reguła z CLAUDE.md). Implementacja symulowana
 * przyjdzie w fazie 4.
 */
interface KarooSource {
    /** Stan nawigacji z obejściem P1 (ponowna subskrypcja). */
    val navigation: Flow<NavInput>
    fun stream(dataTypeId: String): Flow<StreamState>
}

class RealKarooSource(private val karoo: KarooSystemService) : KarooSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val navigation: Flow<NavInput> = channelFlow {
        val events = MutableSharedFlow<Any>(extraBufferCapacity = 8)
        val resubscribe = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val navField = stream(DataType.Type.DISTANCE_TO_DESTINATION).map {
            (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.NAVIGATION_STATE)
        }
        launch {
            NavResubscribeWatchdog().triggers(navField, events).collect {
                FileLog.w("P1: brak OnNavigationState po zmianie NAVIGATION_STATE — ponowna subskrypcja")
                resubscribe.tryEmit(Unit)
            }
        }
        resubscribe.onStart { emit(Unit) }.collectLatest {
            karoo.consumerFlow<OnNavigationState>().collect {
                events.tryEmit(Unit)
                send(it.state.toNavInput())
            }
        }
    }

    override fun stream(dataTypeId: String): Flow<StreamState> = karoo.streamDataFlow(dataTypeId)

    private fun NavigationState.toNavInput(): NavInput = when (this) {
        NavigationState.Idle -> NavInput.Idle
        is NavigationState.NavigatingRoute -> NavInput.Route(routePolyline, routeDistance, reversed, name, rejoinPolyline)
        is NavigationState.NavigatingToDestination -> NavInput.Destination(LatLon(destination.lat, destination.lng), polyline)
    }
}

/** Pierwsze pole strumienia (dla typów z jednym polem) albo wskazane pole. */
fun StreamState.value(field: String? = null): Double? {
    val values = (this as? StreamState.Streaming)?.dataPoint?.values ?: return null
    return if (field != null) values[field] else values.values.firstOrNull()
}
