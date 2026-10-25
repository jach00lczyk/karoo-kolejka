package pl.kolejka.karoo

import android.app.Application
import android.os.SystemClock
import pl.kolejka.karoo.log.FileLog

class PociagApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLog.init(this, BuildConfig.DEBUG)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            FileLog.e("CRASH w wątku ${thread.name}", e)
            Thread.sleep(200) // daj loggerowi chwilę na zapis
            defaultHandler?.uncaughtException(thread, e)
        }
        // Faza 0: czy proces startuje sam po uruchomieniu Karoo (mały uptime bez akcji użytkownika)?
        FileLog.i("Proces start: v${BuildConfig.VERSION_NAME}, uptime=${SystemClock.elapsedRealtime() / 1000}s")
    }
}
