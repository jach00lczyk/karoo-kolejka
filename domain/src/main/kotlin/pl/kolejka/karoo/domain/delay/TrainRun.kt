package pl.kolejka.karoo.domain.delay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.kolejka.karoo.domain.model.LiveTimes
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.model.TrainRef
import pl.kolejka.karoo.domain.time.ServiceTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Postój w wykonaniu kursu: czasy potwierdzone ([confirmed]) albo prognoza PLK. */
data class RunStop(
    val stationId: Int,
    val arrival: Instant?,
    val departure: Instant?,
    val confirmed: Boolean,
    val cancelled: Boolean,
)

/** Wykonanie kursu z `/api/v1/operations/train/{sid}/{oid}/{data}` (SPEC 6.8a). */
data class TrainRun(
    val ref: TrainRef,
    /** S/N = nie wyruszył, P = w drodze, C/F = zakończony, X = odwołany, Q = częściowo odwołany. */
    val status: String,
    val stops: List<RunStop>,
    val fetchedAt: Instant,
) {
    val cancelled: Boolean get() = status == "X"

    /**
     * Czasy dla stacji odjazdu i celu pociągu. Kurs może mijać stację dwa razy (API potrafi
     * zdublować postój) — bierzemy pierwszy postój na stacji odjazdu i pierwszy po nim na stacji celu.
     */
    fun liveFor(train: Train): LiveTimes? {
        val fromIdx = stops.indexOfFirst { it.stationId == train.fromStationId }
        if (fromIdx < 0) return null
        val from = stops[fromIdx]
        val to = stops.subList(fromIdx + 1, stops.size).firstOrNull { it.stationId == train.labelStationId }
        return LiveTimes(
            departure = from.departure ?: from.arrival,
            arrival = to?.arrival ?: to?.departure,
            cancelled = cancelled || from.cancelled || to?.cancelled == true,
            checkedAt = fetchedAt,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null, gdy odpowiedź nie dotyczy [ref] albo jest nieczytelna. */
        fun parse(body: String, ref: TrainRef, fetchedAt: Instant): TrainRun? {
            val dto = runCatching { json.decodeFromString<RunDto>(body) }.getOrNull() ?: return null
            if (dto.scheduleId != ref.sid || dto.orderId != ref.oid) return null
            if (dto.operatingDate != null && runCatching { LocalDate.parse(dto.operatingDate) }.getOrNull() != ref.serviceDate) return null
            val stops = dto.stations.map {
                RunStop(it.stationId, local(it.actualArrival), local(it.actualDeparture), it.isConfirmed, it.isCancelled)
            }
            return TrainRun(ref, dto.trainStatus.orEmpty(), stops, fetchedAt)
        }

        /** API podaje czas lokalny stacji bez offsetu (Europe/Warsaw). */
        private fun local(s: String?): Instant? =
            s?.let { runCatching { LocalDateTime.parse(it).atZone(ServiceTime.ZONE).toInstant() }.getOrNull() }
    }
}

@Serializable
private data class RunDto(
    val scheduleId: Int,
    val orderId: Long,
    val operatingDate: String? = null,
    val trainStatus: String? = null,
    val stations: List<StopDto> = emptyList(),
)

@Serializable
private data class StopDto(
    val stationId: Int,
    val actualArrival: String? = null,
    val actualDeparture: String? = null,
    @SerialName("isConfirmed") val isConfirmed: Boolean = false,
    @SerialName("isCancelled") val isCancelled: Boolean = false,
)
