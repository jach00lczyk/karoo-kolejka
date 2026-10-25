package pl.kolejka.karoo

import android.content.Context
import pl.kolejka.karoo.data.BundledTimetable
import pl.kolejka.karoo.data.SqliteTimetable

/**
 * Ręczne DI (SPEC 1.6): jedna instancja rozkładu na proces, współdzielona przez serwis
 * rozszerzenia (kafelek) i aplikację.
 */
object AppContainer {
    @Volatile private var timetable: SqliteTimetable? = null
    @Volatile private var loaded = false

    /** Otwiera rozkład przy pierwszym użyciu; null, gdy brak danych. Wołać poza wątkiem UI. */
    fun timetable(context: Context): SqliteTimetable? {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    timetable = BundledTimetable.load(context.applicationContext)
                    loaded = true
                }
            }
        }
        return timetable
    }
}
