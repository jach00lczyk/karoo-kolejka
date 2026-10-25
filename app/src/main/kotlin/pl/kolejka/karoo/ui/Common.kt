package pl.kolejka.karoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.R
import pl.kolejka.karoo.data.SqliteTimetable
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.RouteContext
import pl.kolejka.karoo.domain.plan.TrainClass
import pl.kolejka.karoo.domain.plan.TrainOption
import pl.kolejka.karoo.domain.plan.TrainPlanner
import pl.kolejka.karoo.domain.text.NameShortener
import pl.kolejka.karoo.domain.text.TileText
import java.time.Clock

/** Zakładki aplikacji (SPEC 8); kafelek otwiera właściwą przez extra [EXTRA_TAB] (SPEC 7.4). */
enum class Tab(val label: Int) {
    FINISH(R.string.tab_finish),
    ON_ROUTE(R.string.tab_on_route),
    ALL(R.string.tab_all),
    SETTINGS(R.string.tab_settings),
    ;

    companion object {
        const val EXTRA_TAB = "pl.kolejka.karoo.TAB"
        fun from(name: String?): Tab? = entries.firstOrNull { it.name == name }
    }
}

/** Dane wspólne dla ekranów: rozkład, planer i bieżący kontekst trasy z silnika kafla. */
class AppData(val timetable: SqliteTimetable?, val ctx: RouteContext?) {
    val planner: TrainPlanner? = timetable?.let { TrainPlanner(it, Clock.systemUTC()) }
    private val names = timetable?.stations?.associate { it.id to it.name }.orEmpty()
    val stationsById: Map<Int, Station> = timetable?.stations?.associateBy { it.id }.orEmpty()
    fun name(id: Int): String = names[id] ?: "#$id"
}

@Composable
fun rememberAppData(): AppData? {
    val context = LocalContext.current
    val ready by produceState(false) {
        withContext(Dispatchers.IO) { AppContainer.init(context) }
        value = true
    }
    if (!ready) return null
    // Zbieranie modelu kafla utrzymuje potok obliczeń, gdy kafelek nie jest widoczny.
    AppContainer.engine.model.collectAsStateWithLifecycle()
    val tt by AppContainer.timetable.collectAsStateWithLifecycle()
    val ctx by AppContainer.engine.routeContext.collectAsStateWithLifecycle()
    return remember(tt, ctx) { AppData(tt, ctx) }
}

@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF202020)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (t in Tab.entries) {
            val active = t == selected
            Button(
                onClick = { onSelect(t) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.extraSmall,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFFFFD000) else Color(0xFF303030),
                    contentColor = if (active) Color.Black else Color.White,
                ),
            ) {
                Text(stringResource(t.label), fontSize = 12.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, fontSize = 15.sp)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, color = DIM, fontSize = 14.sp)
}

/** Wiersz pociągu: znak klasy, odjazd → przyjazd, cel; pod spodem linia, peron i zapas. */
@Composable
fun TrainRow(train: Train, data: AppData, option: TrainOption? = null, isMain: Boolean = false) {
    val missed = option?.cls == TrainClass.MISSED
    val color = when {
        missed -> DIM
        option != null && (option.isLast || (isMain && option.slack.toMinutes() < 5)) -> WARN
        else -> Color.White
    }
    val mark = option?.let { TileTextMarks.mark(it, isMain) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(mark, "${TileText.hm(train.departure)} → ${TileText.hm(train.labelArrival)}").joinToString(" "),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color,
            )
        }
        val details = listOfNotNull(
            NameShortener.short(data.name(train.labelStationId), 18),
            listOfNotNull(train.agency, train.routeShortName).joinToString(" "),
            "🚌 ZKA".takeIf { train.isBus },
            TileText.platformText(train),
            option?.let { TileTextMarks.detail(it, isMain) },
        )
        Row {
            Text(details.joinToString(" · "), fontSize = 14.sp, color = if (missed) DIM else Color(0xFFCCCCCC))
            if (option?.isLast == true) Text(" · OSTATNI", fontSize = 14.sp, color = BADGE, fontWeight = FontWeight.Bold)
        }
    }
}

/** Te same znaki i opisy co na kafelku (TileText), żeby aplikacja i kafel mówiły jednym językiem. */
object TileTextMarks {
    fun mark(o: TrainOption, isMain: Boolean): String = when (o.cls) {
        TrainClass.SURE -> if (!isMain) TileText.NEXT else if (o.slack.toMinutes() < 5) TileText.WARNING else TileText.SURE
        TrainClass.CATCHABLE -> TileText.CATCHABLE
        TrainClass.MISSED -> TileText.MISSED
    }

    fun detail(o: TrainOption, isMain: Boolean): String = when {
        o.cls == TrainClass.CATCHABLE && !isMain -> "potrzeba ${o.vReqKmh?.let { Math.round(it) } ?: "?"} km/h"
        o.cls == TrainClass.MISSED -> "odpada"
        else -> "zapas ${o.slack.toMinutes()} min"
    }
}

val DIM = Color(0xFF9A9A9A)
val WARN = Color(0xFFFFD000)
val BADGE = Color(0xFFFF4D4D)
