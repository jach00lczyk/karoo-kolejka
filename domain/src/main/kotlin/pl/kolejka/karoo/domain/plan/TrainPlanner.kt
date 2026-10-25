package pl.kolejka.karoo.domain.plan

import pl.kolejka.karoo.domain.geo.FinishCandidate
import pl.kolejka.karoo.domain.geo.RouteGeometry
import pl.kolejka.karoo.domain.model.Connection
import pl.kolejka.karoo.domain.model.PreferenceMode
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.time.ServiceTime
import pl.kolejka.karoo.domain.timetable.Timetable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Dynamiczny stan jazdy w chwili obliczeń. */
data class RideProgress(
    /** DISTANCE_TO_DESTINATION [m]; null przed startem nawigacji. */
    val remainingM: Double?,
    val karooEta: Instant?,
    val lastKarooEta: KarooEtaSample? = null,
    /** Średnia w ruchu (bez pauz) [m/s]. */
    val movingAvgSpeedMps: Double? = null,
)

data class DataStatus(val validTo: LocalDate, val generatedAt: Instant)

/**
 * Wybór stacji i pociągów na mecie (SPEC 6.3–6.5). Bezstanowy — histerezę nakłada
 * [TileStabilizer]. Wywoływać na Dispatchers.Default (zapytania do bazy są krótkie).
 */
class TrainPlanner(private val timetable: Timetable, private val clock: Clock) {

    fun plan(
        geometry: RouteGeometry,
        routeDistanceM: Double,
        progress: RideProgress,
        settings: Settings,
        targets: TargetGroups,
        data: DataStatus?,
    ): TileState {
        val now = clock.instant()
        if (data == null) return TileState.DataMissing
        val today = ServiceTime.localDate(now)
        if (data.validTo.isBefore(today)) {
            return TileState.DataStale(Duration.between(data.generatedAt, now).toDays())
        }
        val dataStale = data.validTo.isBefore(today.plusDays(1)) || Duration.between(data.generatedAt, now) > Duration.ofDays(3)

        val candidates = geometry.finishCandidates
        if (candidates.isEmpty()) {
            return TileState.NoStationNearFinish(geometry.nearestToFinish?.distanceM, geometry.stationsOnRoute.size)
        }
        candidates.firstOrNull { targets.groupOf(it.station.id) != null }?.let { return TileState.AlreadyAtTarget(it.station) }

        val eta = EtaEstimator.finishEta(
            now, progress.karooEta, progress.lastKarooEta, progress.remainingM, routeDistanceM,
            progress.movingAvgSpeedMps, settings.fallbackSpeedKmh,
        )
        val remaining = progress.remainingM?.takeIf { it.isFinite() && it >= 0 } ?: routeDistanceM
        val ctx = Ctx(now, eta, remaining, settings, targets)

        val perCandidate = candidates.map { c -> c to evaluate(c, ctx, WINDOW_AFTER) }
        val withSure = perCandidate.filter { (_, opts) -> opts.any { it.cls == TrainClass.SURE } }
        val chosen = choose(withSure, ctx)
            ?: choose(perCandidate.map { (c, _) -> c to evaluate(c, ctx, untilEndOfDay(c, ctx)) }.filter { (_, o) -> o.any { it.cls == TrainClass.SURE } }, ctx)

        if (chosen == null) {
            return noSureTrain(candidates, geometry, routeDistanceM, ctx)
        }
        val (cand, options, mainIdx) = chosen
        // OSTATNI: najpóźniejszy pociąg okna, jeśli po nim nic już tego dnia nie jedzie.
        val withLast = options.toMutableList()
        if (isLastOfDay(cand.station, options.last().train, targets)) {
            withLast[withLast.lastIndex] = options.last().copy(isLast = true)
        }
        val main = withLast[mainIdx]
        val safePlan = if (main.isLast && main.slack < SAFE_PLAN_SLACK) {
            alternativeOnRoute(geometry, routeDistanceM, ctx)
        } else {
            null
        }
        return TileState.Ok(
            station = cand.station,
            stationDistanceM = cand.distanceM,
            options = withLast,
            mainIndex = mainIdx,
            etaApproximate = eta.approximate,
            safePlan = safePlan,
            dataStale = dataStale,
        )
    }

    /** Pociągi z okna dla jednego kandydata, sklasyfikowane względem t_ready. */
    private fun evaluate(c: FinishCandidate, ctx: Ctx, windowEnd: Duration): List<TrainOption> {
        val tReady = tReady(c, ctx)
        return trainsFrom(c.station.id, tReady.minus(WINDOW_BEFORE), tReady.plus(windowEnd), ctx.targets)
            .map { classify(it, tReady, ctx.remainingM + c.distanceM, ctx) }
    }

    private fun tReady(c: FinishCandidate, ctx: Ctx): Instant =
        ctx.eta.instant
            .plusSeconds((c.distanceM / (ctx.settings.accessSpeedKmh / 3.6)).toLong())
            .plus(Duration.ofMinutes(ctx.settings.bufferMin.toLong()))

    private fun classify(t: Train, tReady: Instant, distanceM: Double, ctx: Ctx): TrainOption {
        val slack = Duration.between(tReady, t.departure)
        val available = Duration.between(ctx.now, t.departure).minusMinutes(ctx.settings.bufferMin.toLong()).seconds
        val vReq = if (available > 0) distanceM / available * 3.6 else null
        val cls = when {
            !slack.isNegative -> TrainClass.SURE
            vReq != null && vReq <= ctx.settings.vMaxKmh -> TrainClass.CATCHABLE
            else -> TrainClass.MISSED
        }
        return TrainOption(t, cls, slack, vReq)
    }

    private data class Choice(val cand: FinishCandidate, val options: List<TrainOption>, val mainIndex: Int) {
        val main: TrainOption get() = options[mainIndex]
    }

    /**
     * Wybór kandydata i głównego pociągu wg trybu preferencji (SPEC 6.3.5–6).
     * NAJSZYBCIEJ: główny = pierwszy PEWNY u kandydata; wygrywa kandydat z najwcześniejszym
     * przyjazdem do celu, remis → bliższa stacja.
     * ŚCISŁA: pierwszy PEWNY pociąg do najwyższej grupy, chyba że przyjeżdża > X min później
     * niż opcja najszybsza — wtedy kolejna grupa, a w ostateczności najszybsza.
     */
    private fun choose(perCandidate: List<Pair<FinishCandidate, List<TrainOption>>>, ctx: Ctx): Choice? {
        val firstSure = perCandidate.mapNotNull { (c, opts) ->
            opts.indexOfFirst { it.cls == TrainClass.SURE }.takeIf { it >= 0 }?.let { Choice(c, opts, it) }
        }
        val fastest = firstSure.minWithOrNull(
            compareBy<Choice>({ it.main.train.earliestArrival }, { it.cand.distanceM }),
        ) ?: return null
        if (ctx.settings.mode == PreferenceMode.FASTEST) return fastest

        val limit = fastest.main.train.earliestArrival.plus(Duration.ofMinutes(ctx.settings.strictToleranceMin.toLong()))
        return ctx.targets.groups.indices.firstNotNullOfOrNull { g ->
            perCandidate.mapNotNull { (c, opts) ->
                opts.indexOfFirst { it.cls == TrainClass.SURE && it.train.labelGroup == g }
                    .takeIf { it >= 0 }?.let { Choice(c, opts, it) }
            }
                .filter { !it.main.train.labelArrival.isAfter(limit) }
                .minWithOrNull(compareBy<Choice>({ it.main.train.labelArrival }, { it.cand.distanceM }))
        } ?: fastest
    }

    /** Brak PEWNEGO pociągu do końca dnia: NoTrainsToday albo LastTrainMissed z alternatywą. */
    private fun noSureTrain(candidates: List<FinishCandidate>, geometry: RouteGeometry, routeDistanceM: Double, ctx: Ctx): TileState {
        val best = candidates.first()
        val tReady = tReady(best, ctx)
        val dayStart = serviceDayStart(tReady)
        val earlier = trainsFrom(best.station.id, dayStart, tReady, ctx.targets)
        if (earlier.isEmpty()) return TileState.NoTrainsToday(best.station)
        val last = classify(earlier.last(), tReady, ctx.remainingM + best.distanceM, ctx).copy(isLast = true)
        return TileState.LastTrainMissed(
            station = best.station,
            lastFromFinish = last,
            alternative = alternativeOnRoute(geometry, routeDistanceM, ctx),
            etaApproximate = ctx.eta.approximate,
        )
    }

    /**
     * Najdalsza stacja po drodze, z której da się jeszcze złapać pociąg do celu w tym dniu
     * (SPEC 6.5.2). ETA stacji z prędkości spójnej z Karoo: pozostały dystans / czas do mety.
     */
    fun alternativeOnRoute(geometry: RouteGeometry, routeDistanceM: Double, ctx: Ctx): Alternative? {
        val remaining = ctx.remainingM
        val current = (routeDistanceM - remaining).coerceAtLeast(0.0)
        val secondsToFinish = Duration.between(ctx.now, ctx.eta.instant).seconds
        val v = if (secondsToFinish > 0 && remaining > 0) remaining / secondsToFinish else ctx.settings.fallbackSpeedKmh / 3.6
        return geometry.stationsOnRoute
            .filter { ctx.targets.groupOf(it.station.id) == null && !it.isPassed(current) }
            .sortedByDescending { it.positionAhead(current) }
            .firstNotNullOfOrNull { s ->
                val pos = s.positionAhead(current)
                val etaStation = ctx.now.plusSeconds(((pos - current) / v).toLong())
                val ready = etaStation
                    .plusSeconds((s.distanceFromRouteM / (ctx.settings.accessSpeedKmh / 3.6)).toLong())
                    .plus(Duration.ofMinutes(ctx.settings.bufferMin.toLong()))
                trainsFrom(s.station.id, ready, serviceDayEnd(ready), ctx.targets).firstOrNull()
                    ?.let { Alternative(s.station, pos, etaStation, it) }
            }
    }

    private fun isLastOfDay(station: Station, train: Train, targets: TargetGroups): Boolean =
        trainsFrom(station.id, train.departure.plusSeconds(1), serviceDayEnd(train.departure), targets).isEmpty()

    private fun untilEndOfDay(c: FinishCandidate, ctx: Ctx): Duration =
        Duration.between(tReady(c, ctx), serviceDayEnd(tReady(c, ctx))).coerceAtLeast(WINDOW_AFTER)

    /** Pociągi z danej stacji odjeżdżające w [from, to], z etykietą celu. */
    fun trainsFrom(stationId: Int, from: Instant, to: Instant, targets: TargetGroups): List<Train> {
        if (to.isBefore(from)) return emptyList()
        val rows = ServiceTime.windows(from, to).flatMap { (date, range) ->
            timetable.connections(setOf(stationId), targets.allIds, date, range)
        }
        return rows.groupBy { Triple(it.tripId, it.serviceDate, it.fromStationId) }
            .values
            .mapNotNull { toTrain(it, targets) }
            .filter { !it.departure.isBefore(from) && !it.departure.isAfter(to) }
            .sortedWith(compareBy({ it.departure }, { it.earliestArrival }))
    }

    private fun toTrain(rows: List<Connection>, targets: TargetGroups): Train? {
        // Kurs może mijać stację odjazdu dwa razy (pętla) — bierzemy pierwszy odjazd.
        val depS = rows.minOf { it.depS }
        val relevant = rows.filter { it.depS == depS && it.arrS >= depS }
        if (relevant.isEmpty()) return null
        val first = relevant.first()
        val byGroup = relevant.mapNotNull { r -> targets.groupOf(r.toStationId)?.let { it to r } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rs) -> rs.minBy { it.arrS } }
        val label = byGroup.minByOrNull { it.key } ?: return null
        val earliest = byGroup.values.minBy { it.arrS }
        return Train(
            tripId = first.tripId,
            serviceDate = first.serviceDate,
            fromStationId = first.fromStationId,
            departure = ServiceTime.toInstant(first.serviceDate, depS),
            platform = first.platform,
            track = first.track,
            agency = first.agency,
            routeShortName = first.routeShortName,
            isBus = first.isBus,
            labelGroup = label.key,
            labelStationId = label.value.toStationId,
            labelArrival = ServiceTime.toInstant(first.serviceDate, label.value.arrS),
            earliestArrival = ServiceTime.toInstant(first.serviceDate, earliest.arrS),
        )
    }

    data class Ctx(val now: Instant, val eta: Eta, val remainingM: Double, val settings: Settings, val targets: TargetGroups)

    companion object {
        val WINDOW_BEFORE: Duration = Duration.ofMinutes(30)
        val WINDOW_AFTER: Duration = Duration.ofHours(3)
        val SAFE_PLAN_SLACK: Duration = Duration.ofMinutes(10)

        /** Koniec „dnia" dla ostatniego pociągu: najbliższa 03:00 lokalnego czasu po [t]. */
        fun serviceDayEnd(t: Instant): Instant {
            val z = t.atZone(ServiceTime.ZONE)
            val end = ZonedDateTime.of(z.toLocalDate(), DAY_BREAK, ServiceTime.ZONE)
            return (if (z.toLocalTime().isBefore(DAY_BREAK)) end else end.plusDays(1)).toInstant()
        }

        fun serviceDayStart(t: Instant): Instant = serviceDayEnd(t).atZone(ServiceTime.ZONE).minusDays(1).toInstant()

        private val DAY_BREAK: LocalTime = LocalTime.of(3, 0)
    }
}
