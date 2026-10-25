package pl.kolejka.karoo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.R
import pl.kolejka.karoo.domain.model.PreferenceMode
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.log.FileLog
import kotlin.math.roundToInt

/** Ustawienia (SPEC 8.4); zapis natychmiast przy zmianie. Na dole „O danych". */
@Composable
fun SettingsScreen(data: AppData, onAddTarget: () -> Unit) {
    val settings by AppContainer.settings.settings.collectAsStateWithLifecycle(Settings())
    val scope = rememberCoroutineScope { CoroutineExceptionHandler { _, e -> FileLog.e("Błąd zapisu ustawień", e) } }
    fun update(f: (Settings) -> Settings) = scope.launch { AppContainer.settings.update(f) }

    val defaults = data.timetable?.defaultTargets?.groups.orEmpty()
    val groups = settings.targets ?: defaults

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            SectionTitle(stringResource(R.string.settings_targets))
            if (settings.targets == null) Hint(stringResource(R.string.settings_targets_default))
        }
        items(groups.withIndex().toList()) { (i, g) ->
            TargetGroupRow(
                index = i,
                names = g.map { data.name(it) },
                canUp = i > 0,
                canDown = i < groups.lastIndex,
                canRemove = groups.size > 1,
                onUp = { update { it.copy(targets = groups.swap(i, i - 1)) } },
                onDown = { update { it.copy(targets = groups.swap(i, i + 1)) } },
                onRemove = { update { it.copy(targets = groups.filterIndexed { j, _ -> j != i }) } },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.settings_add_target), Modifier.weight(1f), onClick = onAddTarget)
                SecondaryButton(stringResource(R.string.settings_restore_defaults), Modifier.weight(1f), enabled = settings.targets != null) {
                    update { it.copy(targets = null) }
                }
            }
        }

        stepper(R.string.settings_finish_radius, settings.finishRadiusM.roundToInt(), 250, 3000, 250, R.string.unit_m) { v -> update { it.copy(finishRadiusM = v.toDouble()) } }
        stepper(R.string.settings_route_radius, settings.routeRadiusM.roundToInt(), 250, 3000, 250, R.string.unit_m) { v -> update { it.copy(routeRadiusM = v.toDouble()) } }
        stepper(R.string.settings_access_speed, settings.accessSpeedKmh.roundToInt(), 5, 30, 1, R.string.unit_kmh) { v -> update { it.copy(accessSpeedKmh = v.toDouble()) } }
        stepper(R.string.settings_buffer, settings.bufferMin, 0, 20, 1, R.string.unit_min) { v -> update { it.copy(bufferMin = v) } }
        stepper(R.string.settings_fallback_speed, settings.fallbackSpeedKmh.roundToInt(), 10, 40, 1, R.string.unit_kmh) { v -> update { it.copy(fallbackSpeedKmh = v.toDouble()) } }
        stepper(R.string.settings_vmax, settings.vMaxKmh.roundToInt(), 15, 50, 1, R.string.unit_kmh) { v -> update { it.copy(vMaxKmh = v.toDouble()) } }

        item {
            SectionTitle(stringResource(R.string.settings_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.settings_mode_fastest), Modifier.weight(1f), enabled = settings.mode != PreferenceMode.FASTEST) {
                    update { it.copy(mode = PreferenceMode.FASTEST) }
                }
                SecondaryButton(stringResource(R.string.settings_mode_strict), Modifier.weight(1f), enabled = settings.mode != PreferenceMode.STRICT) {
                    update { it.copy(mode = PreferenceMode.STRICT) }
                }
            }
        }
        if (settings.mode == PreferenceMode.STRICT) {
            stepper(R.string.settings_strict_tolerance, settings.strictToleranceMin, 5, 90, 5, R.string.unit_min) { v -> update { it.copy(strictToleranceMin = v) } }
        }
        item { SwitchRow(stringResource(R.string.settings_tile_clickable), settings.tileClickable) { v -> update { it.copy(tileClickable = v) } } }
        item { SwitchRow(stringResource(R.string.settings_notifications), settings.notifications) { v -> update { it.copy(notifications = v) } } }

        aboutItems(data)
    }
}

/** Dodaje stację jako nową grupę na końcu; stacje-bliźniaki (np. Śródmieście + WKD) trafiają razem. */
suspend fun addTarget(data: AppData, station: Station) {
    val twins = station.groupId?.let { g -> data.timetable?.stations?.filter { it.groupId == g }?.map { it.id } }.orEmpty()
    val group = (twins + station.id).toSet()
    AppContainer.settings.update { s ->
        val current = s.targets ?: data.timetable?.defaultTargets?.groups.orEmpty()
        if (current.any { station.id in it }) s else s.copy(targets = current + listOf(group))
    }
}

private fun <T> List<T>.swap(i: Int, j: Int): List<T> = toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }

@Composable
private fun TargetGroupRow(
    index: Int,
    names: List<String>,
    canUp: Boolean,
    canDown: Boolean,
    canRemove: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(8.dp)) {
        Text("${index + 1}. ${names.joinToString(" + ")}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("↑", Modifier.weight(1f), enabled = canUp, onClick = onUp)
            SecondaryButton("↓", Modifier.weight(1f), enabled = canDown, onClick = onDown)
            SecondaryButton("✕", Modifier.weight(1f), enabled = canRemove, onClick = onRemove)
        }
    }
}

private fun LazyListScope.stepper(label: Int, value: Int, min: Int, max: Int, step: Int, unit: Int, onChange: (Int) -> Unit) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(label), fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("−", Modifier.width(64.dp), enabled = value > min) { onChange((value - step).coerceAtLeast(min)) }
                Text(stringResource(unit, value), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                SecondaryButton("+", Modifier.width(64.dp), enabled = value < max) { onChange((value + step).coerceAtMost(max)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
