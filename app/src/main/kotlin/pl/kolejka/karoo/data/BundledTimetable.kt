package pl.kolejka.karoo.data

import android.content.Context
import pl.kolejka.karoo.log.FileLog
import java.io.File

/**
 * Rozkład wbudowany w assets builda debug: zasiewa dane przy pierwszym uruchomieniu bez sieci.
 * Build release go nie ma — pierwsze dane przychodzą z aktualizatora (SPEC 9.4: „Połącz z Wi-Fi").
 */
object BundledTimetable {
    private const val ASSET = "timetable.sqlite"

    /** Kopiuje asset do [target], jeśli istnieje. Zwraca true, gdy plik został zasiany. */
    fun seed(context: Context, target: File): Boolean {
        val tmp = File(target.parentFile, "seed.tmp")
        return try {
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) error("rename nie powiódł się")
            FileLog.i("Zasiano rozkład z assets (${target.length()} B)")
            true
        } catch (_: java.io.FileNotFoundException) {
            false
        } catch (e: Exception) {
            FileLog.w("Nie udało się zasiać rozkładu", e)
            tmp.delete()
            false
        }
    }
}
