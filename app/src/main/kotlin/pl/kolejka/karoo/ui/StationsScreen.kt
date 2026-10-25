package pl.kolejka.karoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.R
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.TrainPlanner
import pl.kolejka.karoo.domain.text.StationSearch
import java.time.Instant
import java.time.ZonedDateTime

/** Lista wszystkich stacji z wyszukiwarką (SPEC 8.3); także wybór stacji docelowej w ustawieniach. */
@Composable
fun StationList(data: AppData, onPick: (Station) -> Unit, header: (@Composable () -> Unit)? = null) {
    val all = remember(data.timetable) { StationSearch.sorted(data.timetable?.stations.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember(all) { mutableStateOf(all) }
    LaunchedEffect(query, all) {
        delay(150) // debounce (SPEC 8.3)
        results = withContext(Dispatchers.Default) { StationSearch.search(query, all) }
    }
    Column(Modifier.fillMaxSize()) {
        header?.invoke()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint), fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { st ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(st) }.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(st.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(st.agencies.replace(",", " · "), fontSize = 12.sp, color = DIM)
                    }
                }
            }
        }
    }
}

/** Rozkład stacji do celów: dziś / jutro, przewinięty do „teraz", „Prowadź do stacji" (SPEC 8.3). */
@Composable
fun StationScreen(station: Station, data: AppData, onBack: () -> Unit) {
    val planner = data.planner ?: return
    val targets = data.ctx?.targets ?: data.timetable?.defaultTargets ?: return
    var tomorrow by rememberSaveable(station.id) { mutableStateOf(false) }
    val now = remember { Instant.now() }
    val trains by produceState<List<Train>?>(null, station, tomorrow, targets) {
        value = withContext(Dispatchers.IO) { dayTrains(planner, station, targets, now, tomorrow) }
    }
    val listState = rememberLazyListState()
    val list = trains
    LaunchedEffect(list) {
        if (list != null && !tomorrow) {
            val idx = list.indexOfFirst { !it.departure.isBefore(now) }.coerceAtLeast(0)
            listState.scrollToItem(idx + HEADER_ITEMS)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SecondaryButton(stringResource(R.string.back), onClick = onBack) }
        item { Text(station.name, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        item { ActionButton(stringResource(R.string.navigate_to_station)) { AppContainer.source.navigateTo(station) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.today), Modifier.weight(1f), enabled = tomorrow) { tomorrow = false }
                SecondaryButton(stringResource(R.string.tomorrow), Modifier.weight(1f), enabled = !tomorrow) { tomorrow = true }
            }
        }
        when {
            list == null -> item { Hint(stringResource(R.string.computing)) }
            list.isEmpty() -> item { Hint(stringResource(R.string.no_departures)) }
            else -> items(list) { t ->
                val past = !tomorrow && t.departure.isBefore(now)
                Column(Modifier.fillMaxWidth().background(if (past) Color(0xFF151515) else Color.Transparent)) {
                    TrainRow(t, data)
                }
            }
        }
    }
}

private const val HEADER_ITEMS = 4

private fun dayTrains(planner: TrainPlanner, station: Station, targets: TargetGroups, now: Instant, tomorrow: Boolean): List<Train> {
    val start = TrainPlanner.serviceDayStart(now).let { if (tomorrow) ZonedDateTime.ofInstant(it, pl.kolejka.karoo.domain.time.ServiceTime.ZONE).plusDays(1).toInstant() else it }
    val end = TrainPlanner.serviceDayEnd(start.plusSeconds(3600))
    return planner.trainsFrom(station.id, start, end, targets)
}
