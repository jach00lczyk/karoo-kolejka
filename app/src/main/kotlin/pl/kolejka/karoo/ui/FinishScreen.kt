package pl.kolejka.karoo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.R
import pl.kolejka.karoo.data.RefreshResult
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.FinishBoard
import pl.kolejka.karoo.domain.plan.TrainClass
import pl.kolejka.karoo.domain.text.TileText
import pl.kolejka.karoo.log.FileLog

/** Zakładka „Meta" (SPEC 8.1). */
@Composable
fun FinishScreen(data: AppData, onOpenTab: (Tab) -> Unit) {
    val ctx = data.ctx
    val planner = data.planner
    if (ctx == null || planner == null) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(stringResource(R.string.no_route)) }
            item { Hint(stringResource(R.string.no_route_hint)) }
            item { ActionButton(stringResource(R.string.go_all_stations)) { onOpenTab(Tab.ALL) } }
            // Przycisk zawsze widoczny (uzgodnione 5.11); bez trasy zwykle nie ma czego sprawdzać.
            item { DelayControls { AppContainer.engine.shownTrains } }
        }
        return
    }
    val candidates = ctx.geometry.finishCandidates
    if (candidates.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(stringResource(R.string.no_station_near_finish, TileText.km(ctx.settings.finishRadiusM))) }
            ctx.geometry.nearestToFinish?.let { n ->
                item { Hint(stringResource(R.string.nearest_station, n.station.name, TileText.km(n.distanceM))) }
            }
            if (ctx.geometry.stationsOnRoute.isNotEmpty()) {
                item { ActionButton(stringResource(R.string.go_on_route)) { onOpenTab(Tab.ON_ROUTE) } }
            }
            item { DelayControls { AppContainer.engine.shownTrains } }
        }
        return
    }
    var selected by remember(ctx.geometry) { mutableIntStateOf(0) }
    val candidate = candidates[selected.coerceIn(candidates.indices)]
    val runs by AppContainer.delays.runs.collectAsStateWithLifecycle()
    val board by produceState<FinishBoard?>(null, ctx, candidate, runs) {
        value = withContext(Dispatchers.IO) {
            planner.finishBoard(candidate, ctx.routeDistanceM, ctx.progress, ctx.settings, ctx.targets, count = 8, runs = runs)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(candidate.station.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        item { Hint(stringResource(R.string.from_finish, TileText.km(candidate.distanceM))) }
        board?.let { b ->
            item {
                val source = stringResource(if (b.eta.approximate) R.string.eta_approx else R.string.eta_karoo)
                Text(stringResource(R.string.finish_eta, TileText.hm(b.eta.instant), source), fontSize = 16.sp)
            }
            item { Hint(stringResource(R.string.ready_at, TileText.hm(b.ready))) }
        }
        item { ActionButton(stringResource(R.string.navigate_to_station)) { AppContainer.source.navigateTo(candidate.station) } }
        // Pociągi z tablicy mety i z kafla (także ze stacji po drodze); polityka bierze najbliższe.
        item { DelayControls { board?.options?.filter { it.cls != TrainClass.MISSED }?.map { it.train }.orEmpty() + AppContainer.engine.shownTrains } }
        if (candidates.size > 1) {
            item { SectionTitle(stringResource(R.string.candidates)) }
            items(candidates.withIndex().toList()) { (i, c) ->
                SecondaryButton("${c.station.name} (${TileText.km(c.distanceM)})", Modifier.fillMaxWidth(), enabled = i != selected) { selected = i }
            }
        }
        val b = board
        if (b == null) {
            item { Hint(stringResource(R.string.computing)) }
        } else if (b.options.isEmpty()) {
            item { Hint(stringResource(R.string.no_trains)) }
        } else {
            // Główny = pierwszy PEWNY, jak na kafelku przy wyborze najszybszym.
            val mainIdx = b.options.indexOfFirst { it.cls == TrainClass.SURE }
            items(b.options.withIndex().toList()) { (i, o) -> TrainRow(o.train, data, o, isMain = i == mainIdx) }
        }
    }
}

/** „Sprawdź opóźnienia" (SPEC 7.3) i stan ostatniego sprawdzenia; błąd na czerwono, reszta szaro. */
@Composable
private fun DelayControls(trains: () -> List<Train>) {
    val status by AppContainer.delays.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runCatching { AppContainer.delays.checkKey() } }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RefreshResult?>(null) }
    if (!status.hasKey) {
        Hint(stringResource(R.string.delays_no_key))
        return
    }
    SecondaryButton(
        stringResource(if (checking) R.string.delays_checking else R.string.delays_check),
        Modifier.fillMaxWidth(),
        enabled = !checking,
    ) {
        checking = true
        result = null
        scope.launch {
            // Wyjątek w scope UI zabiłby też serwis (wspólny proces, docs/device-findings.md).
            result = try {
                AppContainer.delays.refreshNow(trains())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLog.e("Sprawdzanie opóźnień", e)
                RefreshResult.Done(checked = 1, ok = 0, error = e.message ?: e.javaClass.simpleName)
            } finally {
                checking = false
            }
        }
    }
    when (val r = result) {
        RefreshResult.NoTrains -> Hint(stringResource(R.string.delays_no_trains))
        RefreshResult.TooSoon -> Hint(stringResource(R.string.delays_too_soon))
        is RefreshResult.Done -> when {
            r.error == null -> Hint(stringResource(R.string.delays_done, r.ok))
            r.ok == 0 -> ErrorText(stringResource(R.string.delays_error, r.error))
            else -> ErrorText(stringResource(R.string.delays_error_partial, r.ok, r.checked, r.error))
        }
        RefreshResult.NoKey, null -> {
            // Bez świeżego wyniku: ostatni błąd automatu (np. brak zasięgu) też na czerwono.
            status.lastError?.let { ErrorText(stringResource(R.string.delays_error, it)) }
        }
    }
    status.lastSuccess?.let { Hint(stringResource(R.string.delays_checked_at, TileText.hm(it))) }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = BADGE, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}
