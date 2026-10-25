package pl.kolejka.karoo.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.kolejka.karoo.log.FileLog
import java.net.HttpURLConnection
import java.net.URL

/** FAZA 0 — ręcznie wyzwalane próby z ekranu aplikacji. Każda loguje wynik do FileLog. */
object ProbeActions {
    const val MAIN_ACTION = "pl.kolejka.karoo.MAIN"

    // ~0,5 MB; limit odpowiedzi OnHttpResponse to 100 KB (MAX_REQUEST_SIZE w karoo-ext 1.1.9).
    const val LARGE_URL = "https://mkuran.pl/gtfs/wkd.zip"
    const val SMALL_URL = "https://mkuran.pl/gtfs/"

    fun notification(karoo: KarooSystemService) {
        val ok = karoo.dispatch(
            SystemNotification(
                id = "probe-notification",
                header = "Pociąg do Warszawy",
                message = "Próba powiadomienia (faza 0)",
                subText = "Dotknij — czy otworzy się aplikacja?",
                action = "Otwórz",
                actionIntent = MAIN_ACTION,
            ),
        )
        FileLog.i("PROBE SystemNotification dispatched=$ok")
    }

    fun pinDrop(karoo: KarooSystemService) {
        val ok = karoo.dispatch(
            LaunchPinDrop(
                Symbol.POI(
                    id = "probe-gdanska",
                    lat = 52.25912,
                    lng = 20.99312,
                    type = Symbol.POI.Types.TRANSIT_CENTER,
                    name = "Warszawa Gdańska",
                ),
            ),
        )
        FileLog.i("PROBE LaunchPinDrop dispatched=$ok")
    }

    suspend fun httpViaKaroo(karoo: KarooSystemService, url: String) {
        val start = System.currentTimeMillis()
        FileLog.i("PROBE OnHttpResponse GET $url")
        val result = withTimeoutOrNull(120_000) {
            callbackFlow {
                val id = karoo.addConsumer(
                    OnHttpResponse.MakeHttpRequest("GET", url, waitForConnection = false),
                    onError = { FileLog.w("PROBE OnHttpResponse onError: $it"); close() },
                ) { event: OnHttpResponse ->
                    FileLog.i("PROBE OnHttpResponse stan=${event.state.javaClass.simpleName} po ${System.currentTimeMillis() - start} ms")
                    if (event.state is HttpResponseState.Complete) trySend(event.state as HttpResponseState.Complete)
                }
                awaitClose { karoo.removeConsumer(id) }
            }.firstOrNull() // onError zamyka flow bez elementu (np. RESPONSE_TOO_LARGE)
        }
        if (result == null) {
            FileLog.w("PROBE OnHttpResponse: brak odpowiedzi (błąd lub 120 s)")
        } else {
            FileLog.i(
                "PROBE OnHttpResponse Complete status=${result.statusCode} body=${result.body?.size ?: "null"} B " +
                    "error=${result.error} headers=${result.headers.keys} czas=${System.currentTimeMillis() - start} ms",
            )
        }
    }

    suspend fun directDownload(context: Context, url: String) = withContext(Dispatchers.IO) {
        FileLog.i("PROBE HttpURLConnection GET $url, sieć=${networkType(context)}")
        val start = System.currentTimeMillis()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val bytes = conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                total
            }
            val ms = System.currentTimeMillis() - start
            FileLog.i("PROBE HttpURLConnection status=${conn.responseCode} ${bytes} B w $ms ms (${bytes * 8 / maxOf(ms, 1)} kbit/s)")
        } catch (e: Exception) {
            FileLog.w("PROBE HttpURLConnection błąd po ${System.currentTimeMillis() - start} ms", e)
        }
    }

    suspend fun location(karoo: KarooSystemService) {
        val loc = withTimeoutOrNull(30_000) {
            callbackFlow {
                val id = karoo.addConsumer<OnLocationChanged> { trySend(it) }
                awaitClose { karoo.removeConsumer(id) }
            }.firstOrNull()
        }
        FileLog.i("PROBE OnLocationChanged: $loc")
    }

    fun networkType(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "?"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "brak"
        return buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
        }.joinToString("+").ifEmpty { "inna" }
    }
}
