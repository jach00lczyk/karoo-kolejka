package pl.kolejka.karoo.data

import android.content.Context
import pl.kolejka.karoo.log.FileLog
import java.io.File

/**
 * TYMCZASOWE (przed fazą 2): rozkład wbudowany w assets builda debug, kopiowany raz do plików
 * aplikacji. Faza 2 zastąpi to `TimetableUpdater` z pobieraniem z gh-pages.
 */
object BundledTimetable {
    private const val ASSET = "timetable.sqlite"

    fun load(context: Context): SqliteTimetable? {
        val dir = File(context.filesDir, "timetable").apply { mkdirs() }
        val target = File(dir, "bundled.sqlite")
        val size = try {
            context.assets.openFd(ASSET).use { it.length }
        } catch (_: Exception) {
            -1L // skompresowany asset nie ma deskryptora — kopiujemy zawsze, gdy rozmiar nieznany
        }
        try {
            if (!target.exists() || size < 0 || target.length() != size) {
                val tmp = File(dir, "bundled.tmp")
                context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(target)) error("rename nie powiódł się")
                FileLog.i("Skopiowano wbudowany rozkład (${target.length()} B)")
            }
        } catch (e: Exception) {
            FileLog.w("Brak wbudowanego rozkładu w assets", e)
            return null
        }
        return SqliteTimetable.open(target)?.also {
            FileLog.i("Rozkład: ${it.version}, ważny do ${it.dataStatus.validTo}, stacji ${it.stations.size}")
        }
    }
}
