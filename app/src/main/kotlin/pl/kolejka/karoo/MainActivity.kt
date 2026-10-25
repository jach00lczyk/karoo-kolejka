package pl.kolejka.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.data.SqliteTimetable
import pl.kolejka.karoo.domain.time.ServiceTime
import pl.kolejka.karoo.log.FileLog
import java.time.format.DateTimeFormatter

/**
 * Na razie ekran „O danych" (SPEC 8.4). Zakładki Meta / Po drodze / Wszystkie stacje
 * i ustawienia przyjdą w fazie 6.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AboutScreen()
            }
        }
    }
}

private data class AboutState(val timetable: SqliteTimetable?, val log: List<String>)

private val DATE_TIME = DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm").withZone(ServiceTime.ZONE)
private val DATE = DateTimeFormatter.ofPattern("d.MM.yyyy")

@Composable
private fun AboutScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<AboutState?>(null) }
    // Jednorazowy odczyt przy otwarciu i po „Odśwież" — bez pollingu (SPEC 11.3).
    LaunchedEffect(refresh) {
        state = withContext(Dispatchers.IO) {
            AboutState(AppContainer.timetable(context), FileLog.tail(200).reversed())
        }
    }
    val s = state
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(R.string.about_hint), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(R.string.about_timetable), style = MaterialTheme.typography.titleMedium) }
        val tt = s?.timetable
        if (s != null && tt == null) {
            item { Text(stringResource(R.string.about_missing)) }
        }
        if (tt != null) {
            item { Text(stringResource(R.string.about_generated, DATE_TIME.format(tt.dataStatus.generatedAt))) }
            item { Text(stringResource(R.string.about_valid_to, DATE.format(tt.dataStatus.validTo))) }
            item { Text(stringResource(R.string.about_stations, tt.stations.size)) }
            item { Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.titleMedium) }
            item { Text(tt.attribution, fontSize = 12.sp) }
        }
        item { Text(stringResource(R.string.about_log), style = MaterialTheme.typography.titleMedium) }
        item { Button(onClick = { refresh++ }) { Text(stringResource(R.string.about_refresh)) } }
        items(s?.log.orEmpty()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
    }
}
