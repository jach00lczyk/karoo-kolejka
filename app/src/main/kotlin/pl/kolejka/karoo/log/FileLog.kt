package pl.kolejka.karoo.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Log do pliku z rotacją 2 × 256 KB (SPEC 11.7). Zapis na jednym wątku w tle, bez blokowania
 * wywołującego. Plik: `Android/data/<pakiet>/files/logs/pociag.log` (do pobrania przez `adb pull`).
 */
object FileLog {
    private const val TAG = "Pociag"
    private const val MAX_BYTES = 256 * 1024L
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "filelog").apply { isDaemon = true } }
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    @Volatile private var file: File? = null
    @Volatile var debugEnabled: Boolean = false

    fun init(context: Context, debug: Boolean) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "pociag.log")
        debugEnabled = debug
    }

    fun d(msg: String) {
        if (debugEnabled) write('D', msg, null)
    }

    fun i(msg: String) = write('I', msg, null)

    fun w(msg: String, t: Throwable? = null) = write('W', msg, t)

    fun e(msg: String, t: Throwable? = null) = write('E', msg, t)

    /** Ostatnie [lines] linii (ekran „O danych", SPEC 8.4). Czyta z dysku — wołać poza wątkiem UI. */
    fun tail(lines: Int = 200): List<String> {
        val f = file ?: return emptyList()
        val old = File(f.parentFile, f.name + ".1")
        val all = buildList {
            if (old.exists()) addAll(old.readLines())
            if (f.exists()) addAll(f.readLines())
        }
        return all.takeLast(lines)
    }

    private fun write(level: Char, msg: String, t: Throwable?) {
        when (level) {
            'D' -> Log.d(TAG, msg, t)
            'I' -> Log.i(TAG, msg, t)
            'W' -> Log.w(TAG, msg, t)
            else -> Log.e(TAG, msg, t)
        }
        val f = file ?: return
        val line = buildString {
            append(LocalDateTime.now().format(timeFormat)).append(' ').append(level).append(' ').append(msg)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
        }
        executor.execute {
            try {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                FileWriter(f, true).use { it.appendLine(line) }
            } catch (_: Exception) {
                // Log nie może zabić aplikacji.
            }
        }
    }
}
