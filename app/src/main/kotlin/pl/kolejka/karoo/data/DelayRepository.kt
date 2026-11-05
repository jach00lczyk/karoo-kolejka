package pl.kolejka.karoo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.kolejka.karoo.domain.delay.DelayPolicy
import pl.kolejka.karoo.domain.delay.TrainRun
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import pl.kolejka.karoo.extension.HttpResult
import pl.kolejka.karoo.extension.KarooSource
import pl.kolejka.karoo.log.FileLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Stan opóźnień dla ekranów: czy jest klucz, kiedy ostatnio się udało, ostatni błąd. */
data class DelayStatus(
    val hasKey: Boolean = true,
    val lastSuccess: Instant? = null,
    val lastError: String? = null,
    /** Trwa pobieranie (kafelek pokazuje „⏱ …"). */
    val refreshing: Boolean = false,
)

/**
 * Opóźnienia na żywo z API PDP PLK (SPEC 6.8a, 7.3). Bez własnego timera: [onShown] woła silnik
 * kafla przy każdym przeliczeniu (minutowy tik), a [DelayPolicy] decyduje, czy pytać.
 * Zapytanie przez Karoo (działa przez mostek z telefonem), awaryjnie bezpośrednio.
 */
class DelayRepository(
    private val source: KarooSource,
    private val keys: ApiKeyStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val policy = DelayPolicy()
    private val inFlight = ConcurrentHashMap.newKeySet<TrainRef>()
    private val _runs = MutableStateFlow<Map<TrainRef, TrainRun>>(emptyMap())
    private val _status = MutableStateFlow(DelayStatus())

    /** Ostatnie wykonania kursów; planer ignoruje starsze niż 30 min. */
    val runs: StateFlow<Map<TrainRef, TrainRun>> get() = _runs
    val status: StateFlow<DelayStatus> get() = _status

    /** Pociągi właśnie pokazane na kafelku — pobierz te, których dotyczy [DelayPolicy.dueAutomatic]. */
    fun onShown(trains: List<Train>) {
        if (trains.none { it.ref != null }) return
        scope.launch {
            // Scope kontenera nie ma CoroutineExceptionHandler — wyjątek nie może z niego uciec.
            try {
                if (key() == null) return@launch
                val due = policy.dueAutomatic(trains, clock.instant())
                if (due.isNotEmpty()) fetchAll(due)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLog.w("Opóźnienia: błąd", e)
            }
        }
    }

    /** Przycisk „Sprawdź opóźnienia": zwraca liczbę sprawdzonych pociągów (0 = nic do sprawdzenia lub brak klucza). */
    suspend fun refreshNow(trains: List<Train>): Int {
        if (key() == null) return 0
        val due = policy.dueManual(trains, clock.instant())
        fetchAll(due)
        return due.size
    }

    /** Sprawdza, czy jest klucz API (ekran Finisz od razu pokazuje „brak klucza"). */
    suspend fun checkKey(): Boolean = key() != null

    private suspend fun key(): String? = withContext(Dispatchers.IO) { keys.key() }
        .also { k -> _status.update { it.copy(hasKey = k != null) } }

    private suspend fun fetchAll(refs: List<TrainRef>) = withContext(Dispatchers.IO) {
        val key = keys.key() ?: return@withContext
        if (refs.isEmpty()) return@withContext
        _status.update { it.copy(refreshing = true) }
        try {
            fetchEach(refs, key)
        } finally {
            _status.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetchEach(refs: List<TrainRef>, key: String) {
        for (ref in refs) {
            if (!inFlight.add(ref)) continue
            try {
                fetch(ref, key)
            } catch (e: Exception) {
                FileLog.w("Opóźnienie ${ref.sid}/${ref.oid}: błąd", e)
                _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            } finally {
                inFlight.remove(ref)
            }
        }
    }

    private suspend fun fetch(ref: TrainRef, key: String) {
        val url = "$BASE/api/v1/operations/train/${ref.sid}/${ref.oid}/${ref.serviceDate}"
        val headers = mapOf("X-API-Key" to key, "Accept" to "application/json")
        val started = clock.millis()
        val res = source.httpGet(url, headers, TIMEOUT_MS.toLong())?.takeIf { it.status != 0 } ?: direct(url, headers)
        // URL nie zawiera klucza — można go logować.
        FileLog.i("Opóźnienie GET $url → ${res.status} (${res.body?.size ?: 0} B, ${clock.millis() - started} ms)")
        val now = clock.instant()
        when (res.status) {
            200 -> {
                val run = res.body?.let { TrainRun.parse(String(it, Charsets.UTF_8), ref, now) }
                    ?: throw IOException("nieczytelna odpowiedź")
                _runs.update { old ->
                    (old + (ref to run)).filterValues { Duration.between(it.fetchedAt, now) <= KEEP }
                }
                _status.update { it.copy(lastSuccess = now, lastError = null) }
            }
            401, 403 -> _status.update { it.copy(lastError = "klucz API odrzucony (${res.status})") }
            429 -> _status.update { it.copy(lastError = "limit zapytań API") }
            else -> _status.update { it.copy(lastError = res.error ?: "HTTP ${res.status}") }
        }
    }

    /** Bez Karoo (np. aplikacja uruchomiona przed połączeniem) — bezpośrednio, z limitem rozmiaru. */
    private fun direct(url: String, headers: Map<String, String>): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val status = conn.responseCode
            val body = if (status == 200) conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (out.size() <= MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (out.size() > MAX_BYTES) throw IOException("odpowiedź większa niż $MAX_BYTES B")
                out.toByteArray()
            } else {
                null
            }
            HttpResult(status, body, null)
        } catch (e: IOException) {
            HttpResult(0, null, e.message)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://pdp-api.plk-sa.pl"
        const val TIMEOUT_MS = 15_000
        const val MAX_BYTES = 100_000
        val KEEP: Duration = Duration.ofHours(2)
    }
}
