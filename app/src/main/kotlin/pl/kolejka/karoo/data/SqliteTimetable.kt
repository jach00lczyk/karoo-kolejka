package pl.kolejka.karoo.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.Connection
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.TargetGroups
import pl.kolejka.karoo.domain.plan.DataStatus
import pl.kolejka.karoo.domain.timetable.Timetable
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Rozkład z pliku SQLite z pipeline'u (SPEC 1.3, 3.4), otwarty raz, read-only.
 * Kursory zawsze w `use {}` (SPEC 11.4).
 */
class SqliteTimetable private constructor(private val db: SQLiteDatabase) : Timetable {

    private val meta: Map<String, String> = db.rawQuery("SELECT key, value FROM meta", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    override val version: String = meta["feed_version"].orEmpty()

    /** Identyfikator danych (czas generacji) — do zasiewu i logów. */
    val dataVersion: String = meta["generated_at"].orEmpty()

    /** Atrybucja źródeł do pokazania w „O danych" (SPEC 3.5). */
    val attribution: String = meta["source_attribution"].orEmpty()

    override val stations: List<Station> = db.rawQuery(
        "SELECT id, name, name_norm, lat, lon, agencies, group_id FROM station", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Station(
                        id = c.getInt(0), name = c.getString(1), nameNorm = c.getString(2),
                        pos = LatLon(c.getDouble(3), c.getDouble(4)), agencies = c.getString(5),
                        groupId = if (c.isNull(6)) null else c.getInt(6),
                    ),
                )
            }
        }
    }

    val dataStatus: DataStatus = DataStatus(
        validTo = LocalDate.parse(meta.getValue("valid_to")),
        generatedAt = Instant.parse(meta.getValue("generated_at").replace("+00:00", "Z")),
    )

    val defaultTargets: TargetGroups =
        TargetGroups(Json.decodeFromString<List<List<Int>>>(meta.getValue("default_targets")).map { it.toSet() })

    override fun connections(from: Set<Int>, targets: Set<Int>, serviceDate: LocalDate, depRange: IntRange): List<Connection> {
        if (from.isEmpty() || targets.isEmpty()) return emptyList()
        // ID są liczbami z naszej bazy — bezpieczne do wklejenia w IN (…).
        val sql = """
            SELECT a.trip_id, a.station_id, a.dep_s, b.station_id, b.arr_s, a.platform, a.track,
                   t.agency, t.route_short_name, t.headsign, t.is_bus
            FROM stop_time a
            JOIN trip_date d ON d.trip_id = a.trip_id AND d.service_date = ?
            JOIN stop_time b ON b.trip_id = a.trip_id AND b.seq > a.seq
            JOIN trip t ON t.id = a.trip_id
            WHERE a.station_id IN (${from.joinToString()})
              AND a.dep_s BETWEEN ? AND ?
              AND (a.flags & 1) = 0
              AND b.station_id IN (${targets.joinToString()})
              AND (b.flags & 2) = 0
            ORDER BY a.dep_s, b.arr_s
        """.trimIndent()
        val date = serviceDate.year * 10000 + serviceDate.monthValue * 100 + serviceDate.dayOfMonth
        return db.rawQuery(sql, arrayOf(date.toString(), depRange.first.toString(), depRange.last.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Connection(
                            tripId = c.getInt(0), serviceDate = serviceDate,
                            fromStationId = c.getInt(1), depS = c.getInt(2),
                            toStationId = c.getInt(3), arrS = c.getInt(4),
                            platform = c.getStringOrNull(5), track = c.getStringOrNull(6),
                            agency = c.getString(7), routeShortName = c.getStringOrNull(8),
                            headsign = c.getStringOrNull(9), isBus = c.getInt(10) != 0,
                        ),
                    )
                }
            }
        }
    }

    /** Wywoływane po podmianie na nowszą wersję (z opóźnieniem, by nie przerwać trwającego zapytania). */
    fun close() = db.close()

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        const val SCHEMA_VERSION = 1

        /** Otwiera plik i sprawdza schemat; null przy jakimkolwiek błędzie (→ DataMissing). */
        fun open(file: File): SqliteTimetable? = try {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val schema = db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (schema?.toIntOrNull() != SCHEMA_VERSION) {
                db.close()
                null
            } else {
                SqliteTimetable(db)
            }
        } catch (e: Exception) {
            pl.kolejka.karoo.log.FileLog.e("Nie można otworzyć rozkładu ${file.path}", e)
            null
        }
    }
}
