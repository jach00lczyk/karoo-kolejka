package pl.kolejka.karoo

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pl.kolejka.karoo.data.AndroidUpdateEnvironment
import pl.kolejka.karoo.data.AndroidUpdateHttp
import pl.kolejka.karoo.data.BundledTimetable
import pl.kolejka.karoo.data.SettingsRepository
import pl.kolejka.karoo.data.SqliteTimetable
import pl.kolejka.karoo.data.SqliteValidator
import pl.kolejka.karoo.domain.update.TimetableFiles
import pl.kolejka.karoo.extension.KarooSource
import pl.kolejka.karoo.extension.RealKarooSource
import pl.kolejka.karoo.extension.TileEngine
import pl.kolejka.karoo.domain.update.TimetableUpdater
import pl.kolejka.karoo.domain.update.UpdateResult
import pl.kolejka.karoo.log.FileLog
import java.io.File
import java.time.Clock

/**
 * Ręczne DI (SPEC 1.6): jedna instancja rozkładu, aktualizatora i ustawień na proces,
 * współdzielona przez serwis rozszerzenia (kafelek) i aplikację.
 */
object AppContainer {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _timetable = MutableStateFlow<SqliteTimetable?>(null)

    /** Aktualny rozkład; zmienia się po udanej aktualizacji. */
    val timetable: StateFlow<SqliteTimetable?> get() = _timetable

    /**
     * Jedno połączenie z Karoo na proces (kafelek, aplikacja, aktualizator). Konsumenci
     * dodani przed połączeniem są rejestrowani po nim (karoo-ext).
     */
    val karoo: KarooSystemService by lazy {
        KarooSystemService(app).also { k ->
            k.connect { FileLog.i("KarooSystem connected=$it libVersion=${k.libVersion}") }
        }
    }

    val source: KarooSource by lazy { RealKarooSource(karoo) }

    /** Silnik kafla współdzielony z ekranami aplikacji (SPEC 8: „jedna instancja w procesie"). */
    val engine: TileEngine by lazy { TileEngine(source, timetable, settings.settings, Clock.systemUTC(), scope) }

    val updateEnv: AndroidUpdateEnvironment by lazy { AndroidUpdateEnvironment(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    private val files by lazy { TimetableFiles(File(app.filesDir, "timetable")) }

    val updater: TimetableUpdater by lazy {
        TimetableUpdater(
            files = files,
            manifestUrl = BuildConfig.MANIFEST_URL,
            http = AndroidUpdateHttp { karoo.takeIf { it.connected } },
            validator = SqliteValidator,
            env = updateEnv,
            clock = Clock.systemUTC(),
            log = FileLog::i,
        )
    }

    @Volatile private var initialized = false

    /** Otwiera dane z dysku (lub zasiewa z assets). Idempotentne, wołać poza wątkiem UI. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = context.applicationContext
            File(files.dir, "bundled.sqlite").delete() // pozostałość wersji testowej z 25.10
            if (files.active() == null) {
                files.dir.mkdirs()
                if (BundledTimetable.seed(app, files.current)) {
                    SqliteTimetable.open(files.current)?.let { tt ->
                        updater.recordSeed(tt.dataVersion)
                        tt.close()
                    }
                }
            }
            reload()
            initialized = true
        }
    }

    /** Sprawdza aktualizację i przeładowuje rozkład, jeśli przyszła nowa wersja. */
    suspend fun checkForUpdate(force: Boolean = false): UpdateResult {
        val result = updater.checkOnce(force)
        FileLog.i("Sprawdzenie rozkładu: $result")
        if (result is UpdateResult.Updated) reload()
        return result
    }

    private fun reload() {
        val file = files.active()
        val opened = file?.let { SqliteTimetable.open(it) }
        if (file != null && opened == null && file == files.current && files.previous.exists()) {
            FileLog.w("Bieżący rozkład nie otwiera się — wracam do poprzedniego")
        }
        val tt = opened ?: files.previous.takeIf { it.exists() && it != file }?.let { SqliteTimetable.open(it) }
        val old = _timetable.value
        _timetable.value = tt
        tt?.let { FileLog.i("Rozkład: ${it.dataVersion}, ważny do ${it.dataStatus.validTo}, stacji ${it.stations.size}") }
            ?: FileLog.w("Brak rozkładu")
        // Stara instancja może jeszcze obsługiwać zapytanie — zamykamy z opóźnieniem.
        if (old != null && old !== tt) scope.launch { delay(60_000); old.close() }
    }
}
