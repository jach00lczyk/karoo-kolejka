package pl.kolejka.karoo.domain.model

import pl.kolejka.karoo.domain.geo.LatLon
import java.time.Instant
import java.time.LocalDate

data class Station(
    val id: Int,
    val name: String,
    val nameNorm: String,
    val pos: LatLon,
    val agencies: String,
    val groupId: Int?,
)

/**
 * Stacje docelowe: lista grup w kolejności preferencji (SPEC 6.1). Grupa = 1+ stacji
 * traktowanych jako ten sam cel (np. Śródmieście + Śródmieście WKD).
 */
data class TargetGroups(val groups: List<Set<Int>>) {
    val allIds: Set<Int> = groups.flatten().toSet()

    /** Indeks grupy (0 = najwyższa) albo null, gdy stacja nie jest celem. */
    fun groupOf(stationId: Int): Int? = groups.indexOfFirst { stationId in it }.takeIf { it >= 0 }
}

/**
 * Surowy wiersz z rozkładu: kurs zatrzymujący się na stacji odjazdu i później na stacji celu.
 * Czasy w sekundach względem „południa minus 12 h" dnia kursowania [serviceDate].
 */
data class Connection(
    val tripId: Int,
    val serviceDate: LocalDate,
    val fromStationId: Int,
    val depS: Int,
    val toStationId: Int,
    val arrS: Int,
    val platform: String?,
    val track: String?,
    val agency: String,
    val routeShortName: String?,
    val headsign: String?,
    val isBus: Boolean,
)

/** Pociąg z czasami bezwzględnymi i etykietą celu (najwyżej położona obsługiwana grupa). */
data class Train(
    val tripId: Int,
    val serviceDate: LocalDate,
    val fromStationId: Int,
    val departure: Instant,
    val platform: String?,
    val track: String?,
    val agency: String,
    val routeShortName: String?,
    val isBus: Boolean,
    /** Grupa celu użyta jako etykieta (najwyższa obsługiwana po stacji odjazdu). */
    val labelGroup: Int,
    val labelStationId: Int,
    val labelArrival: Instant,
    /** Najwcześniejszy przyjazd do dowolnej grupy celów. */
    val earliestArrival: Instant,
) {
    val key: TrainKey get() = TrainKey(tripId, serviceDate, fromStationId)
}

data class TrainKey(val tripId: Int, val serviceDate: LocalDate, val fromStationId: Int)

enum class PreferenceMode { FASTEST, STRICT }

/** Ustawienia użytkownika (SPEC 8.4) z wartościami domyślnymi ze specyfikacji. */
data class Settings(
    val finishRadiusM: Double = 1000.0,
    val routeRadiusM: Double = 1000.0,
    val accessSpeedKmh: Double = 15.0,
    val bufferMin: Int = 5,
    val fallbackSpeedKmh: Double = 25.0,
    val vMaxKmh: Double = 30.0,
    val mode: PreferenceMode = PreferenceMode.FASTEST,
    val strictToleranceMin: Int = 30,
    val tileClickable: Boolean = true,
    val notifications: Boolean = true,
)
