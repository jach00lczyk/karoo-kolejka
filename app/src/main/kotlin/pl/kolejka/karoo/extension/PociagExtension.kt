package pl.kolejka.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.AppContainer
import pl.kolejka.karoo.log.FileLog
import java.time.Clock

class PociagExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private val karoo by lazy { KarooSystemService(this) }

    // Wyjątek w zadaniu nigdy nie zabija serwisu (SPEC 9).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> FileLog.e("Nieobsłużony wyjątek w serwisie", e) },
    )

    private val engine by lazy {
        TileEngine(RealKarooSource(karoo), AppContainer.timetable(this), Clock.systemUTC(), scope)
    }

    override val types by lazy { listOf(TrainDataType(extension) { engine }) }

    override fun onCreate() {
        super.onCreate()
        FileLog.i("PociagExtension.onCreate")
        karoo.connect { connected ->
            FileLog.i("KarooSystem connected=$connected libVersion=${karoo.libVersion} info=${karoo.info}")
        }
        if (BuildConfig.DEBUG) scope.launch { FileLog.i(engine.selfCheck("Siedlce")) }
    }

    override fun onDestroy() {
        FileLog.i("PociagExtension.onDestroy")
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "pociag"
    }
}
