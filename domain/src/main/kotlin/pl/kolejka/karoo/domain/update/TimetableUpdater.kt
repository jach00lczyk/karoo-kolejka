package pl.kolejka.karoo.domain.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Properties
import java.util.zip.GZIPInputStream

/** Manifest z gh-pages (SPEC 3.5). `url` jest względny wobec adresu manifestu. */
@Serializable
data class Manifest(
    @SerialName("schema_version") val schemaVersion: Int,
    val version: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val url: String,
    @SerialName("valid_to") val validTo: String,
)

/** Transport HTTP; implementacja produkcyjna w module app. Błędy jako wyjątki. */
interface UpdateHttp {
    suspend fun fetchText(url: String): String
    suspend fun download(url: String, dest: File, maxBytes: Long)
}

/** Sprawdza pobraną bazę (integrity_check + schema_version) i zwraca jej wersję albo null. */
fun interface TimetableValidator {
    fun validate(file: File): Boolean
}

/** Warunki otoczenia: Wi-Fi (sieć bez limitu) i trwająca jazda (SPEC 9.2). */
interface UpdateEnvironment {
    fun isUnmetered(): Boolean
    fun isRecording(): Boolean
}

sealed interface UpdateResult {
    data object SkippedRecent : UpdateResult
    data object UpToDate : UpdateResult
    /** Nowa wersja jest, ale trwa jazda bez Wi-Fi — spróbujemy po jeździe. */
    data object Deferred : UpdateResult
    data class Updated(val version: String) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Pliki rozkładu w katalogu aplikacji: `current.sqlite`, poprzednia wersja jako zapas
 * (`previous.sqlite`, SPEC 1.3), pliki tymczasowe i stan (`state.properties`).
 */
class TimetableFiles(val dir: File) {
    val current = File(dir, "current.sqlite")
    val previous = File(dir, "previous.sqlite")
    val tmpGz = File(dir, "download.gz.tmp")
    val tmpDb = File(dir, "download.sqlite.tmp")
    private val stateFile = File(dir, "state.properties")

    /** Plik do otwarcia: bieżący, a gdy go brak (np. przerwana podmiana) — poprzedni. */
    fun active(): File? = current.takeIf { it.exists() } ?: previous.takeIf { it.exists() }

    fun readState(): Properties = Properties().also { p ->
        if (stateFile.exists()) stateFile.inputStream().use { p.load(it) }
    }

    fun writeState(p: Properties) {
        val tmp = File(dir, "state.properties.tmp")
        tmp.outputStream().use { p.store(it, null) }
        tmp.renameTo(stateFile)
    }

    /** Atomowa podmiana: bieżący → poprzedni, nowy → bieżący. */
    fun promote(newDb: File) {
        if (current.exists()) {
            previous.delete()
            check(current.renameTo(previous)) { "nie można przenieść bieżącej wersji" }
        }
        check(newDb.renameTo(current)) { "nie można podmienić rozkładu" }
    }
}

/**
 * Jednorazowe sprawdzenie aktualizacji rozkładu (SPEC 9). Bez timerów: wołane przy starcie
 * serwisu, po końcu jazdy (gdy odłożone) i z ekranu „O danych".
 */
class TimetableUpdater(
    private val files: TimetableFiles,
    private val manifestUrl: String,
    private val http: UpdateHttp,
    private val validator: TimetableValidator,
    private val env: UpdateEnvironment,
    private val clock: Clock,
    private val supportedSchema: Int = 1,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkOnce(force: Boolean = false): UpdateResult = mutex.withLock {
        files.dir.mkdirs()
        val state = files.readState()
        val now = clock.millis()
        val lastOk = state.getProperty(KEY_LAST_CHECK)?.toLongOrNull() ?: 0L
        val haveData = files.active() != null
        if (!force && haveData && now - lastOk in 0 until MIN_INTERVAL.toMillis()) {
            return UpdateResult.SkippedRecent
        }

        val manifest = try {
            retrying { json.decodeFromString(Manifest.serializer(), http.fetchText(manifestUrl)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("manifest: ${e.message ?: e.javaClass.simpleName}")
        }
        if (manifest.schemaVersion != supportedSchema) {
            return fail("nieobsługiwany schema_version ${manifest.schemaVersion}")
        }
        if (haveData && manifest.version == state.getProperty(KEY_VERSION)) {
            markChecked(state, now)
            return UpdateResult.UpToDate
        }
        if (haveData && env.isRecording() && !env.isUnmetered()) {
            log("Nowa wersja ${manifest.version}, ale trwa jazda bez Wi-Fi — odkładam")
            return UpdateResult.Deferred
        }

        return try {
            val url = URI(manifestUrl).resolve(manifest.url).toString()
            retrying { http.download(url, files.tmpGz, manifest.sizeBytes + SIZE_SLACK) }
            val sha = sha256(files.tmpGz)
            if (!sha.equals(manifest.sha256, ignoreCase = true)) return fail("sha256 się nie zgadza")
            GZIPInputStream(files.tmpGz.inputStream().buffered()).use { input ->
                files.tmpDb.outputStream().use { input.copyTo(it) }
            }
            if (!validator.validate(files.tmpDb)) return fail("baza nie przeszła walidacji")
            files.promote(files.tmpDb)
            state.setProperty(KEY_VERSION, manifest.version)
            markChecked(state, now)
            log("Rozkład zaktualizowany do ${manifest.version}")
            UpdateResult.Updated(manifest.version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("pobieranie: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            files.tmpGz.delete()
            files.tmpDb.delete()
        }
    }

    /** Wersja aktywnego pliku według stanu (null dla pliku spoza aktualizatora, np. wbudowanego). */
    fun installedVersion(): String? = files.readState().getProperty(KEY_VERSION)

    /** Zapisuje wersję pliku zasianego spoza aktualizatora (np. wbudowanego w build debug). */
    fun recordSeed(version: String) {
        files.dir.mkdirs()
        val s = files.readState()
        s.setProperty(KEY_VERSION, version)
        files.writeState(s)
    }

    private fun markChecked(state: Properties, now: Long) {
        state.setProperty(KEY_LAST_CHECK, now.toString())
        files.writeState(state)
    }

    private fun fail(reason: String): UpdateResult.Failed {
        log("Aktualizacja rozkładu nieudana: $reason")
        return UpdateResult.Failed(reason)
    }

    /** Do 2 ponowień z backoffem (SPEC 9.2). */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var wait = 2_000L
        repeat(RETRIES) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Próba nieudana (${e.message}), ponawiam za ${wait / 1000} s")
                delay(wait)
                wait *= 2
            }
        }
        return block()
    }

    companion object {
        val MIN_INTERVAL: Duration = Duration.ofHours(6)
        private const val RETRIES = 2
        private const val SIZE_SLACK = 64 * 1024L
        private const val KEY_VERSION = "version"
        private const val KEY_LAST_CHECK = "last_check_ms"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
