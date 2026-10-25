package pl.kolejka.karoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.R
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.UpcomingStation
import pl.kolejka.karoo.domain.plan.UpcomingStations
import pl.kolejka.karoo.domain.text.NameShortener
import pl.kolejka.karoo.domain.text.TileText
import java.time.Clock
import kotlin.math.roundToInt

/** Zakładka „Po drodze" (SPEC 8.2). */
@Composable
fun OnRouteScreen(data: AppData, onOpenTab: (Tab) -> Unit) {
    val ctx = data.ctx
    val planner = data.planner
    if (ctx == null || planner == null) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(stringResource(R.string.no_route)) }
            item { ActionButton(stringResource(R.string.go_all_stations)) { onOpenTab(Tab.ALL) } }
        }
        return
    }
    val stations by produceState<List<UpcomingStation>?>(null, ctx) {
        value = withContext(Dispatchers.IO) {
            UpcomingStations(planner, Clock.systemUTC()).compute(
                ctx.geometry, ctx.routeDistanceM, ctx.progress, ctx.finishEta, ctx.settings, ctx.targets,
                limit = 50, trainsPerStation = 4, includePassed = true,
            )
        }
    }
    // Stacja polecana przez SPEC 6.5 (zjazd, gdy ostatni z mety odjedzie / plan B).
    val recommended = when (val s = ctx.state) {
        is TileState.LastTrainMissed -> s.alternative?.station?.id
        is TileState.Ok -> s.safePlan?.station?.id
        else -> null
    }
    var expanded by remember(ctx.geometry) { mutableStateOf(recommended) }
    val list = stations
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            list == null -> item { Hint(stringResource(R.string.computing)) }
            list.isEmpty() -> item { SectionTitle(stringResource(R.string.no_stations_on_route, TileText.km(ctx.settings.routeRadiusM))) }
            else -> items(list, key = { it.station.id }) { u ->
                StationOnRouteItem(
                    u, data,
                    isRecommended = u.station.id == recommended,
                    isExpanded = expanded == u.station.id,
                    onToggle = { expanded = if (expanded == u.station.id) null else u.station.id },
                )
            }
        }
    }
}

@Composable
private fun StationOnRouteItem(u: UpcomingStation, data: AppData, isRecommended: Boolean, isExpanded: Boolean, onToggle: () -> Unit) {
    val color = if (u.passed) DIM else Color.White
    Column(
        Modifier.fillMaxWidth()
            .background(if (isRecommended) Color(0xFF3A3000) else Color(0xFF1A1A1A))
            .clickable(enabled = !u.passed, onClick = onToggle)
            .heightIn(min = 52.dp)
            .padding(8.dp),
    ) {
        val target = u.train?.let { " → ${NameShortener.short(data.name(it.labelStationId), 14)}" }.orEmpty()
        Text(u.station.name + target, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
        Text(
            stringResource(R.string.station_on_route_info, (u.alongM / 1000).roundToInt().toString(), TileText.km(u.distanceFromRouteM)),
            fontSize = 13.sp, color = DIM,
        )
        if (u.passed) {
            Text(stringResource(R.string.passed), fontSize = 14.sp, color = DIM)
            return@Column
        }
        Text(stringResource(R.string.station_eta, TileText.hm(u.eta), TileText.km(u.distanceM)), fontSize = 15.sp)
        if (isRecommended) Text(stringResource(R.string.recommended), fontSize = 14.sp, color = WARN)
        val first = u.train
        if (first == null) {
            Hint(stringResource(R.string.no_reachable_train))
        } else if (!isExpanded) {
            TrainRow(first, data)
        } else {
            u.trains.forEach { TrainRow(it, data) }
            ActionButton(stringResource(R.string.navigate_to_station)) { AppContainer.source.navigateTo(u.station) }
        }
    }
}
