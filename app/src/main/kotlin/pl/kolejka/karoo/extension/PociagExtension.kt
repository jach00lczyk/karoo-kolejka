package pl.kolejka.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.log.FileLog
import pl.kolejka.karoo.probe.DeviceProbe
import pl.kolejka.karoo.probe.ProbeDataType

class PociagExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private val karoo by lazy { KarooSystemService(this) }

    // Wyjątek w zadaniu nigdy nie zabija serwisu (SPEC 9).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> FileLog.e("Nieobsłużony wyjątek w serwisie", e) },
    )

    override val types by lazy { listOf(ProbeDataType(extension)) }

    override fun onCreate() {
        super.onCreate()
        FileLog.i("PociagExtension.onCreate")
        karoo.connect { connected ->
            FileLog.i("KarooSystem connected=$connected libVersion=${karoo.libVersion} info=${karoo.info}")
        }
        DeviceProbe(karoo, scope).start()
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
