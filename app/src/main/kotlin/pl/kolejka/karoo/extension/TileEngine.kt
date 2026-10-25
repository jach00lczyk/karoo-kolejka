package pl.kolejka.karoo.extension

import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import pl.kolejka.karoo.data.SqliteTimetable
import pl.kolejka.karoo.domain.geo.Polyline
import pl.kolejka.karoo.domain.geo.RouteAnalyzer
import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.geo.SpatialIndex
import pl.kolejka.karoo.domain.karoo.KarooUnits
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.plan.EtaEstimator
import pl.kolejka.karoo.domain.plan.KarooEtaSample
import pl.kolejka.karoo.domain.plan.RideProgress
import pl.kolejka.karoo.domain.plan.TileStabilizer
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainPlanner
import pl.kolejka.karoo.domain.plan.UpcomingStations
import pl.kolejka.karoo.domain.route.GeometryConfig
import pl.kolejka.karoo.domain.route.NavInput
import pl.kolejka.karoo.domain.route.RouteState
import pl.kolejka.karoo.domain.route.RouteTracker
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.log.FileLog
import java.time.Clock
import kotlin.math.roundToLong

/**
 * Łączy nawigację, strumienie Karoo, rozkład i planer w model kafelka (SPEC 7.3).
 * Strumienie ETA/dystansu są subskrybowane tylko przy trasie i aktywnym kafelku
 * (`WhileSubscribed`, SPEC 11.2). Jedyny timer: tik minutowy przy trasie.
 */
class TileEngine(
    private val source: KarooSource,
    private val timetable: SqliteTimetable?,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val settings = Settings()
    private val index = timetable?.let { SpatialIndex(it.stations) }
    private val names: Map<Int, String> = timetable?.stations?.associate { it.id to it.name }.orEmpty()
    private val nameOf: (Int) -> String = { names[it] ?: "#$it" }
    private val planner = timetable?.let { TrainPlanner(it, clock) }
    private val upcoming = planner?.let { UpcomingStations(it, clock) }
    private val tracker = RouteTracker(::analyze, Dispatchers.Default)
    private val stabilizer = TileStabilizer()
    private var lastKarooEta: KarooEtaSample? = null
    private var lastLoggedState: String? = null
    private var lastLogAt = 0L

    val model: StateFlow<TileModel> = build()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), TileModel(TileState.Computing, stationName = nameOf))

    private fun analyze(input: NavInput, cfg: GeometryConfig): RouteGeometry? {
        val idx = index ?: return null
        val g = when (input) {
            NavInput.Idle -> null
            is NavInput.Route -> {
                // Faza 0 (25.10): przy reversed=true Karoo podaje polyline już w kierunku jazdy —
                // ostatni punkt to meta. Nie odwracamy.
                RouteAnalyzer.analyze(Polyline.decode(input.polyline, PRECISION), idx, cfg.finishRadiusM, cfg.routeRadiusM)
            }
            is NavInput.Destination ->
                RouteAnalyzer.analyze(Polyline.decode(input.polyline, PRECISION), idx, cfg.finishRadiusM, cfg.routeRadiusM, destination = input.destination)
        }
        FileLog.i(
            "Geometria: meta=${g?.finish}, kandydaci=${g?.finishCandidates?.map { "${it.station.name}@${it.distanceM.toInt()}m" }}, " +
                "po drodze=${g?.stationsOnRoute?.map { "${it.station.name}@km${(it.occurrencesM.first() / 1000).toInt()}" }}",
        )
        return g
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun build(): Flow<TileModel> {
        if (timetable == null) return flowOf(TileModel(TileState.DataMissing))
        val config = flowOf(GeometryConfig(settings.finishRadiusM, settings.routeRadiusM))
        return tracker.states(source.navigation, config)
            .flatMapLatest { rs ->
                when (rs) {
                    RouteState.NoRoute, is RouteState.Invalid -> {
                        stabilizer.reset()
                        lastKarooEta = null
                        flowOf(TileModel(TileState.NoRoute, stationName = nameOf))
                    }
                    is RouteState.Computing -> flowOf(TileModel(TileState.Computing, stationName = nameOf))
                    is RouteState.Ready -> combine(rawStreams(), minuteTicker()) { raw, _ -> raw }
                        // Przeliczaj tylko przy istotnej zmianie wejścia (SPEC 7.3).
                        .map { it to inputKey(it) }
                        .distinctUntilChanged { a, b -> a.second == b.second }
                        .conflate()
                        .map { (raw, _) -> compute(rs, raw) }
                        .flowOn(Dispatchers.Default)
                }
            }
            .distinctUntilChanged()
    }

    private fun rawStreams(): Flow<RawStreams> = combine(
        source.stream(DataType.Type.DISTANCE_TO_DESTINATION),
        source.stream(DataType.Type.TIME_OF_ARRIVAL),
        source.stream(DataType.Type.TIME_TO_DESTINATION),
        source.stream(DataType.Type.AVERAGE_SPEED),
    ) { d, toa, ttd, avg ->
        RawStreams(d.value(DataType.Field.DISTANCE_TO_DESTINATION), toa.value(), ttd.value(), avg.value())
    }

    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(clock.millis() / 60_000)
            delay(60_000 - clock.millis() % 60_000)
        }
    }

    private fun inputKey(r: RawStreams) = listOf(
        clock.millis() / 60_000,
        r.distanceToDestination?.let { (it / 250).roundToLong() },
        r.timeOfArrival?.let { (it / 60_000).roundToLong() },
        r.timeToDestination?.let { (it / 30).roundToLong() },
        r.averageSpeed?.let { (it * 2).roundToLong() },
    )

    private fun compute(rs: RouteState.Ready, raw: RawStreams): TileModel = try {
        val now = clock.instant()
        val routeDistance = (rs.input as? NavInput.Route)?.routeDistanceM ?: rs.geometry.lengthM
        val remaining = KarooUnits.distanceM(raw.distanceToDestination)
        val karooEta = KarooUnits.etaFromTimeOfArrival(raw.timeOfArrival)
            ?: KarooUnits.etaFromTimeToDestination(raw.timeToDestination, now)
        if (karooEta != null) lastKarooEta = KarooEtaSample(karooEta, now)
        val progress = RideProgress(remaining, karooEta, lastKarooEta, KarooUnits.speedMps(raw.averageSpeed))
        val targets = timetable!!.defaultTargets
        val state = stabilizer.apply(
            planner!!.plan(rs.geometry, routeDistance, progress, settings, targets, timetable.dataStatus),
            now,
        )
        val finishEta = EtaEstimator.finishEta(
            now, progress.karooEta, progress.lastKarooEta, remaining, routeDistance, progress.movingAvgSpeedMps, settings.fallbackSpeedKmh,
        )
        val next = upcoming!!.compute(rs.geometry, routeDistance, progress, finishEta, settings, targets, limit = 2)
        // Log przy zmianie typu stanu, a poza tym co 5 min (surowe wartości do ustalenia jednostek).
        val kind = state.javaClass.simpleName
        if (kind != lastLoggedState || clock.millis() - lastLogAt > 300_000) {
            FileLog.i("Kafelek: $kind eta=$finishEta remaining=$remaining raw=$raw upcoming=${next.map { it.station.name }}")
            lastLoggedState = kind
            lastLogAt = clock.millis()
        }
        // Bez DISTANCE_TO_DESTINATION (np. przed ruszeniem) dystans z długości trasy, jak przy stacjach po drodze.
        TileModel(state, next, nameOf, finishEta, remaining ?: routeDistance)
    } catch (e: Exception) {
        FileLog.e("Błąd obliczeń kafelka", e)
        TileModel(TileState.Computing, stationName = nameOf)
    }

    /** Autotest (debug): czy rozkład otwiera się na urządzeniu i zapytanie zwraca pociągi. */
    fun selfCheck(stationName: String): String {
        val tt = timetable ?: return "Autotest: brak rozkładu"
        val st = tt.stations.firstOrNull { it.name == stationName } ?: return "Autotest: brak stacji $stationName"
        val now = clock.instant()
        val trains = planner!!.trainsFrom(st.id, now, now.plusSeconds(4 * 3600), tt.defaultTargets)
        return "Autotest: ${trains.size} pociągów z $stationName w 4 h: " +
            trains.take(4).joinToString { "${pl.kolejka.karoo.domain.text.TileText.hm(it.departure)}→${nameOf(it.labelStationId)} ${it.labelArrival.let(pl.kolejka.karoo.domain.text.TileText::hm)}" }
    }

    companion object {
        /** Ustalone w fazie 0 (docs/device-findings.md). */
        const val PRECISION = 5
    }
}
