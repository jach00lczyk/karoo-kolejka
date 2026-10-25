package pl.kolejka.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.domain.update.UpdateResult
import pl.kolejka.karoo.log.FileLog
import java.time.Clock

class PociagExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private val karoo by lazy { KarooSystemService(this) }
    private val source by lazy { RealKarooSource(karoo) }

    // Wyjątek w zadaniu nigdy nie zabija serwisu (SPEC 9).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> FileLog.e("Nieobsłużony wyjątek w serwisie", e) },
    )

    private val engine by lazy {
        AppContainer.init(this)
        TileEngine(source, AppContainer.timetable, AppContainer.settings.settings, Clock.systemUTC(), scope)
    }

    override val types by lazy { listOf(TrainDataType(extension) { engine }) }

    @Volatile private var updateStarted = false

    override fun onCreate() {
        super.onCreate()
        FileLog.i("PociagExtension.onCreate")
        scope.launch { AppContainer.init(this@PociagExtension) }
        karoo.connect { connected ->
            FileLog.i("KarooSystem connected=$connected libVersion=${karoo.libVersion}")
            if (connected) {
                AppContainer.karoo = karoo
                // Jednorazowe sprawdzenie przy starcie (SPEC 9.1); serwis bywa tworzony wielokrotnie (P6).
                if (!updateStarted) {
                    updateStarted = true
                    scope.launch {
                        AppContainer.init(this@PociagExtension)
                        AppContainer.checkForUpdate()
                    }
                }
            }
        }
        // Stan jazdy: odkładanie pobierania w trakcie jazdy bez Wi-Fi i ponowienie po jej końcu.
        scope.launch {
            var deferredWhileRiding = false
            source.recording.distinctUntilChanged().collect { recording ->
                AppContainer.updateEnv.recording = recording
                if (recording) {
                    deferredWhileRiding = true
                } else if (deferredWhileRiding) {
                    deferredWhileRiding = false
                    AppContainer.init(this@PociagExtension)
                    val r = AppContainer.checkForUpdate()
                    if (r is UpdateResult.SkippedRecent) FileLog.i("Po jeździe: rozkład sprawdzony niedawno")
                }
            }
        }
    }

    override fun onDestroy() {
        FileLog.i("PociagExtension.onDestroy")
        AppContainer.karoo = null
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "pociag"
    }
}
