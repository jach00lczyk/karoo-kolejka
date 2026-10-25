package pl.kolejka.karoo.domain.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val manifestUrl = "https://example.invalid/karoo-kolejka/manifest.json"

    private class FakeHttp : UpdateHttp {
        var manifest: String? = null
        val files = mutableMapOf<String, ByteArray>()
        val fetched = mutableListOf<String>()
        var failManifest = 0

        override suspend fun fetchText(url: String): String {
            fetched += url
            if (failManifest > 0) {
                failManifest--
                throw IOException("brak sieci")
            }
            return manifest ?: throw IOException("404")
        }

        override suspend fun download(url: String, dest: File, maxBytes: Long) {
            fetched += url
            val bytes = files[url] ?: throw IOException("404 $url")
            check(bytes.size <= maxBytes)
            dest.writeBytes(bytes)
        }
    }

    private class Env(var unmetered: Boolean = true, var recording: Boolean = false) : UpdateEnvironment {
        override fun isUnmetered() = unmetered
        override fun isRecording() = recording
    }

    private var now = Instant.parse("2026-10-26T06:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private fun gzip(content: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(content.toByteArray()) }
    }.toByteArray()

    private fun sha(bytes: ByteArray): String {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        return TimetableUpdater.sha256(f)
    }

    /** Publikuje wersję: plik .gz z treścią „db-<version>" i manifest. */
    private fun publish(http: FakeHttp, version: String, schema: Int = 1, badSha: Boolean = false) {
        val gz = gzip("db-$version")
        val name = "timetable-$version.sqlite.gz"
        http.files["https://example.invalid/karoo-kolejka/$name"] = gz
        val digest = if (badSha) "0".repeat(64) else sha(gz)
        http.manifest = """{"schema_version":$schema,"version":"$version","sha256":"$digest","size_bytes":${gz.size},"url":"$name","generated_at":"x","valid_from":"2026-10-25","valid_to":"2026-11-09","extra":1}"""
    }

    private class Harness(val files: TimetableFiles, val http: FakeHttp, val env: Env, val updater: TimetableUpdater)

    private fun harness(validator: TimetableValidator = TimetableValidator { it.readText().startsWith("db-") }): Harness {
        val files = TimetableFiles(File(tmp.root, "timetable"))
        val http = FakeHttp()
        val env = Env()
        return Harness(files, http, env, TimetableUpdater(files, manifestUrl, http, validator, env, clock))
    }

    @Test
    fun `first run downloads, verifies and installs`() = runTest {
        val h = harness()
        publish(h.http, "v1")
        assertEquals(UpdateResult.Updated("v1"), h.updater.checkOnce())
        assertEquals("db-v1", h.files.current.readText())
        assertEquals("v1", h.updater.installedVersion())
        assertFalse(h.files.tmpGz.exists() || h.files.tmpDb.exists())
        assertTrue(h.http.fetched.contains("https://example.invalid/karoo-kolejka/timetable-v1.sqlite.gz"))
    }

    @Test
    fun `same version only records the check`() = runTest {
        val h = harness()
        publish(h.http, "v1")
        h.updater.checkOnce()
        now = now.plus(Duration.ofHours(7))
        h.http.fetched.clear()
        assertEquals(UpdateResult.UpToDate, h.updater.checkOnce())
        assertEquals(listOf(manifestUrl), h.http.fetched)
    }

    @Test
    fun `checks at most every six hours unless forced`() = runTest {
        val h = harness()
        publish(h.http, "v1")
        h.updater.checkOnce()
        now = now.plus(Duration.ofHours(5))
        h.http.fetched.clear()
        assertEquals(UpdateResult.SkippedRecent, h.updater.checkOnce())
        assertTrue(h.http.fetched.isEmpty())
        assertEquals(UpdateResult.UpToDate, h.updater.checkOnce(force = true))
    }

    @Test
    fun `new version replaces current and keeps previous as fallback`() = runTest {
        val h = harness()
        publish(h.http, "v1")
        h.updater.checkOnce()
        publish(h.http, "v2")
        assertEquals(UpdateResult.Updated("v2"), h.updater.checkOnce(force = true))
        assertEquals("db-v2", h.files.current.readText())
        assertEquals("db-v1", h.files.previous.readText())
        // Przerwana podmiana: brak bieżącego → otwieramy poprzedni
        h.files.current.delete()
        assertEquals(h.files.previous, h.files.active())
    }

    @Test
    fun `checksum mismatch keeps old data`() = runTest {
        val h = harness()
        publish(h.http, "v1")
        h.updater.checkOnce()
        publish(h.http, "v2", badSha = true)
        assertTrue(h.updater.checkOnce(force = true) is UpdateResult.Failed)
        assertEquals("db-v1", h.files.current.readText())
        assertEquals("v1", h.updater.installedVersion())
        assertFalse(h.files.tmpGz.exists() || h.files.tmpDb.exists())
    }

    @Test
    fun `invalid database is rejected`() = runTest {
        val h = harness(validator = { false })
        publish(h.http, "v1")
        assertTrue(h.updater.checkOnce() is UpdateResult.Failed)
        assertFalse(h.files.current.exists())
    }

    @Test
    fun `unsupported schema is rejected before download`() = runTest {
        val h = harness()
        publish(h.http, "v9", schema = 2)
        assertTrue(h.updater.checkOnce() is UpdateResult.Failed)
        assertEquals(listOf(manifestUrl), h.http.fetched)
    }

    @Test
    fun `deferred while recording without wifi, but not when there is no data at all`() = runTest {
        val h = harness()
        h.env.recording = true
        h.env.unmetered = false
        publish(h.http, "v1")
        assertEquals(UpdateResult.Updated("v1"), h.updater.checkOnce()) // brak danych → pobieramy mimo jazdy
        publish(h.http, "v2")
        now = now.plus(Duration.ofHours(7))
        assertEquals(UpdateResult.Deferred, h.updater.checkOnce())
        // Koniec jazdy → kolejne wywołanie pobiera (odroczenie nie zapisuje czasu sprawdzenia)
        h.env.recording = false
        assertEquals(UpdateResult.Updated("v2"), h.updater.checkOnce())
    }

    @Test
    fun `manifest fetch is retried twice with backoff`() = runTest {
        val h = harness()
        publish(h.http, "v1")
        h.http.failManifest = 2
        assertEquals(UpdateResult.Updated("v1"), h.updater.checkOnce())
        h.http.failManifest = 3
        assertTrue(h.updater.checkOnce(force = true) is UpdateResult.Failed)
        assertEquals("db-v1", h.files.current.readText())
    }

    @Test
    fun `seeded file version is recorded`() = runTest {
        val h = harness()
        h.files.dir.mkdirs()
        h.files.current.writeText("db-seed")
        h.updater.recordSeed("seed")
        publish(h.http, "seed")
        assertEquals(UpdateResult.UpToDate, h.updater.checkOnce())
    }
}
