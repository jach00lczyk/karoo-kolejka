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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import pl.kolejka.karoo.data.DelayRepository
import pl.kolejka.karoo.data.SqliteTimetable
import pl.kolejka.karoo.domain.delay.TrainRun
import pl.kolejka.karoo.domain.geo.Polyline
import pl.kolejka.karoo.domain.geo.RouteAnalyzer
import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.geo.SpatialIndex
import pl.kolejka.karoo.domain.karoo.KarooUnits
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import pl.kolejka.karoo.domain.plan.EtaEstimator
import pl.kolejka.karoo.domain.plan.KarooEtaSample
import pl.kolejka.karoo.domain.plan.RideProgress
import pl.kolejka.karoo.domain.plan.RouteContext
import pl.kolejka.karoo.domain.plan.TileStabilizer
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainPlanner
import pl.kolejka.karoo.domain.plan.UpcomingStations
import pl.kolejka.karoo.domain.route.GeometryConfig
import pl.kolejka.karoo.domain.route.NavInput
import pl.kolejka.karoo.domain.route.RouteState
import pl.kolejka.karoo.domain.route.RouteTracker
import pl.kolejka.karoo.domain.route.ridePoints
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.log.FileLog
import java.time.Clock
import kotlin.math.roundToLong

/**
 * Łączy nawigację, strumienie Karoo, rozkład, ustawienia i planer w model kafelka (SPEC 7.3).
 * Nowy rozkład (po aktualizacji) lub zmiana ustawień budują potok od nowa. Strumienie
 * ETA/dystansu są subskrybowane tylko przy trasie i aktywnym kafelku (`WhileSubscribed`,
 * SPEC 11.2). Jedyny timer: tik minutowy przy trasie — on też wyzwala sprawdzanie opóźnień
 * pociągów z kafla ([DelayRepository.onShown], SPEC 7.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileEngine(
    private val source: KarooSource,
    timetables: Flow<SqliteTimetable?>,
    settings: Flow<Settings>,
    private val delays: DelayRepository,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val _routeContext = kotlinx.coroutines.flow.MutableStateFlow<RouteContext?>(null)

    /** Kontekst trasy z ostatniego obliczenia — dla ekranów aplikacji; null bez trasy. */
    val routeContext: StateFlow<RouteContext?> get() = _routeContext

    private val real: Flow<TileModel> = combine(timetables, settings) { tt, s -> tt to s }
        .distinctUntilChanged()
        .flatMapLatest { (tt, s) -> if (tt == null) flowOf(TileModel(TileState.DataMissing)) else Pipeline(tt, s).models() }

    /** Prawdziwy model albo — w debug, po włączeniu z adb/Opcji — scenariusz demo ([DemoScenarios]). */
    val model: StateFlow<TileModel> = DemoScenarios.current
        .flatMapLatest { demo -> if (demo != null) flowOf(DemoScenarios.model(demo, clock.instant())) else real }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), TileModel(TileState.Computing))

    /** Wszystko, co zależy od konkretnej wersji rozkładu i ustawień. */
    private inner class Pipeline(private val tt: SqliteTimetable, private val settings: Settings) {
        private val index = SpatialIndex(tt.stations)
        private val names: Map<Int, String> = tt.stations.associate { it.id to it.name }
        private val nameOf: (Int) -> String = { names[it] ?: "#$it" }
        private val targets = settings.targets?.let { TargetGroups(it) } ?: tt.defaultTargets
        private val planner = TrainPlanner(tt, clock)
        private val upcoming = UpcomingStations(planner, clock)
        private val tracker = RouteTracker(::analyze, Dispatchers.Default)
        private val stabilizer = TileStabilizer()
        private var lastKarooEta: KarooEtaSample? = null
        private var lastRemaining: Pair<Double, java.time.Instant>? = null
        private var lastLoggedState: String? = null
        private var lastLogAt = 0L

        fun models(): Flow<TileModel> =
            tracker.states(source.navigation, flowOf(GeometryConfig(settings.finishRadiusM, settings.routeRadiusM)))
                .flatMapLatest { rs ->
                    when (rs) {
                        RouteState.NoRoute, is RouteState.Invalid -> {
                            stabilizer.reset()
                            lastKarooEta = null
                            lastRemaining = null
                            _routeContext.value = null
                            flowOf(TileModel(TileState.NoRoute, stationName = nameOf))
                        }
                        is RouteState.Computing -> flowOf(TileModel(TileState.Computing, stationName = nameOf))
                        is RouteState.Ready -> combine(rawStreams(), minuteTicker(), delays.runs, delays.status) { raw, _, runs, st -> Input(raw, runs, st, inputKey(raw)) }
                            // Nowa trasa (nie reroute) → opóźnienia pokazanych pociągów od razu (SPEC 7.3).
                            .onStart { delays.onRoute(rs.key) }
                            // Przeliczaj tylko przy istotnej zmianie wejścia lub opóźnień (SPEC 7.3).
                            .distinctUntilChanged { a, b -> a.key == b.key && a.runs === b.runs && a.status == b.status }
                            .conflate()
                            .map { compute(rs, it.raw, it.runs) }
                            .flowOn(Dispatchers.Default)
                    }
                }

        private fun analyze(input: NavInput, cfg: GeometryConfig): RouteGeometry? {
            val g = when (input) {
                NavInput.Idle -> null
                // Przy reversed=true Karoo NIE odwraca polyline — robi to ridePoints (docs/device-findings.md).
                is NavInput.Route ->
                    RouteAnalyzer.analyze(input.ridePoints(PRECISION), index, cfg.finishRadiusM, cfg.routeRadiusM)
                is NavInput.Destination ->
                    RouteAnalyzer.analyze(Polyline.decode(input.polyline, PRECISION), index, cfg.finishRadiusM, cfg.routeRadiusM, destination = input.destination)
            }
            FileLog.i(
                "Geometria: reversed=${(input as? NavInput.Route)?.reversed}, meta=${g?.finish}, kandydaci=${g?.finishCandidates?.map { "${it.station.name}@${it.distanceM.toInt()}m" }}, " +
                    "po drodze=${g?.stationsOnRoute?.map { "${it.station.name}@km${(it.occurrencesM.first() / 1000).toInt()}" }}",
            )
            return g
        }

        private fun compute(rs: RouteState.Ready, raw: RawStreams, runs: Map<TrainRef, TrainRun>): TileModel = try {
            val now = clock.instant()
            val routeDistance = (rs.input as? NavInput.Route)?.routeDistanceM ?: rs.geometry.lengthM
            // Karoo potrafi na chwilę zgubić pole dystansu (log 25.10) — przytrzymaj ostatnią wartość
            // jak ETA (SPEC 5.1), żeby kafel nie przeskakiwał między dystansem z Karoo a długością trasy.
            val remaining = KarooUnits.distanceM(raw.distanceToDestination)
                ?.also { lastRemaining = it to now }
                ?: lastRemaining?.takeIf { java.time.Duration.between(it.second, now) <= EtaEstimator.HOLD }?.first
            val karooEta = KarooUnits.etaFromTimeOfArrival(raw.timeOfArrival)
                ?: KarooUnits.etaFromTimeToDestination(raw.timeToDestination, now)
            if (karooEta != null) lastKarooEta = KarooEtaSample(karooEta, now)
            val progress = RideProgress(remaining, karooEta, lastKarooEta, KarooUnits.speedMps(raw.averageSpeed))
            val state = stabilizer.apply(
                planner.plan(rs.geometry, routeDistance, progress, settings, targets, tt.dataStatus, runs),
                now,
            )
            val finishEta = EtaEstimator.finishEta(
                now, progress.karooEta, progress.lastKarooEta, remaining, routeDistance, progress.movingAvgSpeedMps, settings.fallbackSpeedKmh,
            )
            val next = upcoming.compute(rs.geometry, routeDistance, progress, finishEta, settings, targets, limit = 2, runs = runs)
            val shown = shownTrains(state) + next.mapNotNull { it.train }
            lastShown = shown
            delays.onShown(shown)
            // Log przy zmianie typu stanu, a poza tym co 5 min.
            val kind = state.javaClass.simpleName
            if (kind != lastLoggedState || clock.millis() - lastLogAt > 300_000) {
                FileLog.i("Kafelek: $kind eta=$finishEta remaining=$remaining raw=$raw upcoming=${next.map { "${it.station.name}@${(it.distanceM / 100).toInt() / 10.0}km(km${(it.alongM / 1000).toInt()})" }}")
                lastLoggedState = kind
                lastLogAt = clock.millis()
            }
            _routeContext.value = RouteContext(rs.geometry, routeDistance, progress.copy(remainingM = remaining), finishEta, settings, targets, state)
            // Bez DISTANCE_TO_DESTINATION (np. przed ruszeniem) dystans z długości trasy, jak przy stacjach po drodze.
            TileModel(
                state, next, nameOf, finishEta, remaining ?: routeDistance, clickable = settings.tileClickable,
                delayAgeMin = delayAge(shown, now), delayRefreshing = delays.status.value.refreshing,
            )
        } catch (e: Exception) {
            FileLog.e("Błąd obliczeń kafelka", e)
            TileModel(TileState.Computing, stationName = nameOf)
        }
    }

    /**
     * Ile minut temu pobrano opóźnienia — do pastylki ⏱ na kafelku (np. bez zasięgu w telefonie
     * liczba rośnie). Null, gdy opóźnień nie ma o co pytać (brak klucza, stary rozkład bez ID kursów).
     */
    private fun delayAge(shown: List<Train>, now: java.time.Instant): Long? {
        val status = delays.status.value
        if (!status.hasKey || shown.none { it.ref != null }) return null
        val last = status.lastSuccess ?: return TileModel.NEVER
        return java.time.Duration.between(last, now).toMinutes().coerceAtLeast(0)
    }

    @Volatile private var lastShown: List<Train> = emptyList()

    /** Pociągi z ostatniego przeliczenia kafla (z metą i stacjami po drodze) — dla przycisku w aplikacji. */
    val shownTrains: List<Train> get() = lastShown

    private data class Input(val raw: RawStreams, val runs: Map<TrainRef, TrainRun>, val status: pl.kolejka.karoo.data.DelayStatus, val key: List<Long?>)

    /** Pociągi widoczne na kafelku — tylko o nie pytamy API (SPEC 7.3). */
    private fun shownTrains(state: TileState): List<Train> = when (state) {
        is TileState.Ok -> state.display.map { it.train } + listOfNotNull(state.safePlan?.train)
        is TileState.LastTrainMissed -> listOfNotNull(state.lastFromFinish?.train, state.alternative?.train)
        else -> emptyList()
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

    companion object {
        /** Ustalone w fazie 0 (docs/device-findings.md). */
        const val PRECISION = 5
    }
}
