package pl.kolejka.karoo.data

import android.content.Context
import pl.kolejka.karoo.log.FileLog
import java.io.File

/**
 * Klucz API PDP PLK na urządzeniu (SPEC 11.10). Repo i APK są publiczne, a regulamin API zabrania
 * udostępniania klucza — dlatego nie ma go w buildzie. Właściciel wgrywa go raz:
 *
 *     adb push pdp_api_key.txt /sdcard/Android/data/pl.kolejka.karoo/files/
 *
 * Przy pierwszym odczycie plik jest przenoszony do pamięci prywatnej aplikacji (niedostępnej
 * przez adb bez roota) i usuwany z katalogu zewnętrznego. Klucz nigdy nie trafia do logów.
 */
class ApiKeyStore(private val context: Context) {
    private val internal get() = File(context.filesDir, "pdp_api_key")

    @Volatile private var cached: String? = null

    /** Klucz albo null (wtedy aplikacja działa na samym rozkładzie). Wołać poza wątkiem UI. */
    fun key(): String? {
        cached?.let { return it }
        importExternal()
        return internal.takeIf { it.exists() }?.readText()?.trim()?.takeIf { valid(it) }?.also { cached = it }
    }

    private fun importExternal() {
        val external = File(context.getExternalFilesDir(null) ?: return, EXTERNAL_NAME)
        if (!external.exists()) return
        try {
            // Plik z panelu PDP ma postać „API Key: …"; akceptujemy też sam klucz.
            val key = external.readLines().firstNotNullOfOrNull { line ->
                line.substringAfter("API Key:", line).trim().takeIf { valid(it) }
            }
            if (key == null) {
                FileLog.w("Plik $EXTERNAL_NAME nie zawiera klucza API — usuwam go")
                return
            }
            internal.writeText(key)
            cached = key
            FileLog.i("Zaimportowano klucz API PDP (${key.length} znaków)")
        } finally {
            if (!external.delete()) FileLog.w("Nie udało się usunąć $EXTERNAL_NAME z katalogu zewnętrznego")
        }
    }

    private fun valid(k: String) = k.length >= 32 && k.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    companion object {
        const val EXTERNAL_NAME = "pdp_api_key.txt"
    }
}
