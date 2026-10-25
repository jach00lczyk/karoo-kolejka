package pl.kolejka.karoo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.kolejka.karoo.domain.update.TimetableValidator
import pl.kolejka.karoo.domain.update.UpdateEnvironment
import pl.kolejka.karoo.domain.update.UpdateHttp
import pl.kolejka.karoo.log.FileLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP dla aktualizatora (SPEC 1.4, docs/device-findings.md):
 * - manifest (~0,5 KB) najpierw przez Karoo (`OnHttpResponse` — działa też przez mostek z telefonem),
 *   a gdy się nie uda — bezpośrednio;
 * - plik danych (~1,4 MB) tylko bezpośrednio, bo `OnHttpResponse` ma limit 100 KB.
 */
class AndroidUpdateHttp(private val karoo: () -> KarooSystemService?) : UpdateHttp {

    override suspend fun fetchText(url: String): String {
        karoo()?.takeIf { it.connected }?.let { k ->
            val body = viaKaroo(k, url)
            if (body != null) return body
        }
        return withContext(Dispatchers.IO) {
            open(url).run { inputStream.use { String(it.readBytes(), Charsets.UTF_8) } }
        }
    }

    override suspend fun download(url: String, dest: File, maxBytes: Long) = withContext(Dispatchers.IO) {
        val conn = open(url)
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("plik większy niż w manifeście")
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Cache-Control", "no-cache")
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        return conn
    }

    private suspend fun viaKaroo(k: KarooSystemService, url: String): String? {
        val result = withTimeoutOrNull(TIMEOUT_MS.toLong()) {
            callbackFlow {
                val id = k.addConsumer(
                    OnHttpResponse.MakeHttpRequest("GET", url, headers = mapOf("Cache-Control" to "no-cache"), waitForConnection = false),
                    onError = { close() },
                ) { event: OnHttpResponse ->
                    (event.state as? HttpResponseState.Complete)?.let { trySend(it) }
                }
                awaitClose { k.removeConsumer(id) }
            }.firstOrNull()
        }
        return if (result?.statusCode == 200 && result.body != null) {
            String(result.body!!, Charsets.UTF_8)
        } else {
            FileLog.i("Manifest przez Karoo nieudany (${result?.statusCode} ${result?.error}), próbuję bezpośrednio")
            null
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
    }
}

/** `PRAGMA integrity_check` + `schema_version` (SPEC 1.3). */
object SqliteValidator : TimetableValidator {
    override fun validate(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val ok = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            val schema = db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            ok && schema?.toIntOrNull() == SqliteTimetable.SCHEMA_VERSION
        }
    } catch (e: Exception) {
        FileLog.w("Walidacja rozkładu nieudana", e)
        false
    }
}

class AndroidUpdateEnvironment(private val context: Context) : UpdateEnvironment {
    /** Ustawiane przez serwis z `RideState`. */
    @Volatile var recording: Boolean = false

    override fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun isRecording(): Boolean = recording
}
