package pl.kolejka.karoo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import pl.kolejka.karoo.domain.model.PreferenceMode
import pl.kolejka.karoo.domain.model.Settings
import pl.kolejka.karoo.log.FileLog

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Ustawienia użytkownika w DataStore (SPEC 1.3); zapis natychmiast przy zmianie (SPEC 8.4). */
class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data
        .catch { e ->
            FileLog.e("Błąd odczytu ustawień — używam domyślnych", e)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[FINISH_RADIUS] = s.finishRadiusM
            prefs[ROUTE_RADIUS] = s.routeRadiusM
            prefs[ACCESS_SPEED] = s.accessSpeedKmh
            prefs[BUFFER] = s.bufferMin
            prefs[FALLBACK_SPEED] = s.fallbackSpeedKmh
            prefs[V_MAX] = s.vMaxKmh
            prefs[MODE] = s.mode.name
            prefs[STRICT_TOLERANCE] = s.strictToleranceMin
            prefs[TILE_CLICKABLE] = s.tileClickable
            prefs[NOTIFICATIONS] = s.notifications
            val targets = s.targets
            if (targets == null) prefs.remove(TARGETS) else prefs[TARGETS] = Settings.encodeTargets(targets)
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            finishRadiusM = this[FINISH_RADIUS] ?: d.finishRadiusM,
            routeRadiusM = this[ROUTE_RADIUS] ?: d.routeRadiusM,
            accessSpeedKmh = this[ACCESS_SPEED] ?: d.accessSpeedKmh,
            bufferMin = this[BUFFER] ?: d.bufferMin,
            fallbackSpeedKmh = this[FALLBACK_SPEED] ?: d.fallbackSpeedKmh,
            vMaxKmh = this[V_MAX] ?: d.vMaxKmh,
            mode = this[MODE]?.let { runCatching { PreferenceMode.valueOf(it) }.getOrNull() } ?: d.mode,
            strictToleranceMin = this[STRICT_TOLERANCE] ?: d.strictToleranceMin,
            tileClickable = this[TILE_CLICKABLE] ?: d.tileClickable,
            notifications = this[NOTIFICATIONS] ?: d.notifications,
            targets = this[TARGETS]?.let(Settings::decodeTargets),
        )
    }

    private companion object {
        val FINISH_RADIUS = doublePreferencesKey("finish_radius_m")
        val ROUTE_RADIUS = doublePreferencesKey("route_radius_m")
        val ACCESS_SPEED = doublePreferencesKey("access_speed_kmh")
        val BUFFER = intPreferencesKey("buffer_min")
        val FALLBACK_SPEED = doublePreferencesKey("fallback_speed_kmh")
        val V_MAX = doublePreferencesKey("v_max_kmh")
        val MODE = stringPreferencesKey("mode")
        val STRICT_TOLERANCE = intPreferencesKey("strict_tolerance_min")
        val TILE_CLICKABLE = booleanPreferencesKey("tile_clickable")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val TARGETS = stringPreferencesKey("targets")
    }
}
