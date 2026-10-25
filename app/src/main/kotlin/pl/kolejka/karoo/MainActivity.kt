package pl.kolejka.karoo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.log.FileLog
import pl.kolejka.karoo.ui.AppData
import pl.kolejka.karoo.ui.FinishScreen
import pl.kolejka.karoo.ui.OnRouteScreen
import pl.kolejka.karoo.ui.SectionTitle
import pl.kolejka.karoo.ui.SettingsScreen
import pl.kolejka.karoo.ui.StationList
import pl.kolejka.karoo.ui.StationScreen
import pl.kolejka.karoo.ui.Tab
import pl.kolejka.karoo.ui.TabBar
import pl.kolejka.karoo.ui.addTarget
import pl.kolejka.karoo.ui.rememberAppData

/** Aplikacja: zakładki Meta / Po drodze / Stacje / Ustawienia (SPEC 8). */
class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = Tab.from(intent?.getStringExtra(Tab.EXTRA_TAB))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black, onSurface = Color.White, onBackground = Color.White)) {
                // Surface ustawia kolor treści (biały) dla wszystkich tekstów bez jawnego koloru.
                Surface(color = Color.Black, contentColor = Color.White) {
                    App(initialTab = tab) { tab = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kliknięcie kafelka przy otwartej aplikacji (singleTop) przełącza zakładkę (SPEC 7.4).
        Tab.from(intent.getStringExtra(Tab.EXTRA_TAB))?.let { tab = it }
    }
}

@Composable
private fun App(initialTab: Tab?, consumeInitialTab: () -> Unit) {
    val data = rememberAppData() ?: return
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(defaultTab(data)) }
    var station by androidx.compose.runtime.remember { mutableStateOf<Station?>(null) }
    var pickingTarget by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (initialTab != null) {
        tab = initialTab
        station = null
        pickingTarget = false
        consumeInitialTab()
    }
    val scope = rememberCoroutineScope { CoroutineExceptionHandler { _, e -> FileLog.e("Błąd aplikacji", e) } }

    BackHandler(enabled = station != null || pickingTarget) {
        station = null
        pickingTarget = false
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TabBar(tab) {
            tab = it
            station = null
            pickingTarget = false
        }
        Column(Modifier.weight(1f)) {
            val st = station
            when {
                st != null -> StationScreen(st, data) { station = null }
                pickingTarget -> StationList(data, onPick = { picked ->
                    scope.launch { addTarget(data, picked) }
                    pickingTarget = false
                }) { SectionTitle(stringResource(R.string.settings_pick_target)) }
                else -> when (tab) {
                    Tab.FINISH -> FinishScreen(data) { tab = it }
                    Tab.ON_ROUTE -> OnRouteScreen(data) { tab = it }
                    Tab.ALL -> StationList(data, onPick = { station = it })
                    Tab.SETTINGS -> SettingsScreen(data) { pickingTarget = true }
                }
            }
        }
    }
}

/** Bez trasy — Wszystkie stacje; z trasą — Meta (SPEC 8.3). */
private fun defaultTab(data: AppData): Tab = if (data.ctx != null) Tab.FINISH else Tab.ALL
