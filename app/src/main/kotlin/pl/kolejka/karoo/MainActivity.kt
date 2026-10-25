package pl.kolejka.karoo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.log.FileLog
import pl.kolejka.karoo.probe.ProbeActions

/** Na razie: ekran prób fazy 0. Docelowe zakładki (SPEC 8) przyjdą w fazie 6. */
class MainActivity : ComponentActivity() {
    private val karoo by lazy { KarooSystemService(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logIntent("onCreate", intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProbeScreen(karoo)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logIntent("onNewIntent", intent)
    }

    override fun onStart() {
        super.onStart()
        karoo.connect { FileLog.i("MainActivity KarooSystem connected=$it") }
    }

    override fun onStop() {
        karoo.disconnect()
        super.onStop()
    }

    private fun logIntent(where: String, intent: Intent?) {
        FileLog.i("MainActivity.$where action=${intent?.action} extras=${intent?.extras?.keySet()} flags=0x${Integer.toHexString(intent?.flags ?: 0)}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProbeScreen(karoo: KarooSystemService) {
    // Aplikacja i rozszerzenie dzielą proces: wyjątek w UI zabiłby też serwis. Nigdy go nie przepuszczaj.
    val scope = rememberCoroutineScope { CoroutineExceptionHandler { _, e -> FileLog.e("Błąd w akcji ekranu", e) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    var log by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        while (true) { // odświeżanie podglądu logu tylko na ekranie prób fazy 0
            log = withContext(Dispatchers.IO) { FileLog.tail(60) }.reversed()
            delay(2_000)
        }
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.probe_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { ProbeActions.notification(karoo) }) { Text(stringResource(R.string.probe_notification)) }
            Button(onClick = { ProbeActions.pinDrop(karoo) }) { Text(stringResource(R.string.probe_pin)) }
            Button(onClick = { scope.launch { ProbeActions.httpViaKaroo(karoo, ProbeActions.SMALL_URL) } }) { Text(stringResource(R.string.probe_http_small)) }
            Button(onClick = { scope.launch { ProbeActions.httpViaKaroo(karoo, ProbeActions.LARGE_URL) } }) { Text(stringResource(R.string.probe_http_large)) }
            Button(onClick = { scope.launch { ProbeActions.directDownload(context, ProbeActions.LARGE_URL) } }) { Text(stringResource(R.string.probe_direct)) }
            Button(onClick = { scope.launch { ProbeActions.location(karoo) } }) { Text(stringResource(R.string.probe_location)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }
}
