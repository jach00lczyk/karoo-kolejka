package pl.kolejka.karoo.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.R
import pl.kolejka.karoo.domain.time.ServiceTime
import pl.kolejka.karoo.domain.update.UpdateResult
import pl.kolejka.karoo.log.FileLog
import java.time.format.DateTimeFormatter

private val DATE_TIME = DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm").withZone(ServiceTime.ZONE)
private val DATE = DateTimeFormatter.ofPattern("d.MM.yyyy")

/** „O danych" (SPEC 8.4): wersja, ważność, „Sprawdź aktualizację", źródła, ostatnie 200 linii logu. */
fun LazyListScope.aboutItems(data: AppData) {
    item { SectionTitle(stringResource(R.string.about_timetable)) }
    item { AboutDetails(data) }
}

@Composable
private fun AboutDetails(data: AppData) {
    val context = LocalContext.current
    val tt = data.timetable
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var version by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope { CoroutineExceptionHandler { _, e -> FileLog.e("Błąd w O danych", e) } }
    // Jednorazowy odczyt przy otwarciu i po sprawdzeniu — bez pollingu (SPEC 11.3).
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            version = AppContainer.updater.installedVersion()
            log = FileLog.tail(200).reversed()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tt == null) {
            Text(stringResource(R.string.about_missing))
        } else {
            Text(stringResource(R.string.about_version, version ?: "—"))
            Text(stringResource(R.string.about_generated, DATE_TIME.format(tt.dataStatus.generatedAt)))
            Text(stringResource(R.string.about_valid_to, DATE.format(tt.dataStatus.validTo)))
            Text(stringResource(R.string.about_stations, tt.stations.size))
        }
        SecondaryButton(stringResource(if (checking) R.string.about_checking else R.string.about_check), enabled = !checking) {
            checking = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { AppContainer.checkForUpdate(force = true) }
                result = describe(context, r)
                checking = false
                refresh++
            }
        }
        result?.let { Text(it) }
        if (tt != null) {
            SectionTitle(stringResource(R.string.about_sources))
            Text(tt.attribution, fontSize = 12.sp)
        }
        SectionTitle(stringResource(R.string.about_log))
        log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
    }
}

private fun describe(context: Context, r: UpdateResult): String = when (r) {
    is UpdateResult.Updated -> context.getString(R.string.update_updated, r.version)
    UpdateResult.UpToDate, UpdateResult.SkippedRecent -> context.getString(R.string.update_up_to_date)
    UpdateResult.Deferred -> context.getString(R.string.update_deferred)
    is UpdateResult.Failed -> context.getString(R.string.update_failed, r.reason)
}
