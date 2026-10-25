package pl.kolejka.karoo.domain.plan

import java.time.Duration
import java.time.Instant

data class Eta(val instant: Instant, val approximate: Boolean)

/** Ostatnia wartość ETA z Karoo i chwila jej otrzymania. */
data class KarooEtaSample(val eta: Instant, val receivedAt: Instant)

/** ETA mety (SPEC 6.2) z przytrzymaniem ostatniej wartości przy chwilowym braku strumienia (SPEC 5.1). */
object EtaEstimator {
    val HOLD: Duration = Duration.ofMinutes(2)
    private const val MIN_MOVING_SPEED_MPS = 0.5

    /**
     * @param karooEta bieżące ETA z Karoo, gdy strumień jest w stanie Streaming.
     * @param lastKarooEta ostatnie znane ETA z Karoo (gdy bieżącego brak).
     * @param remainingM DISTANCE_TO_DESTINATION; null przed startem nawigacji.
     */
    fun finishEta(
        now: Instant,
        karooEta: Instant?,
        lastKarooEta: KarooEtaSample?,
        remainingM: Double?,
        routeDistanceM: Double,
        movingAvgSpeedMps: Double?,
        fallbackSpeedKmh: Double,
    ): Eta {
        if (karooEta != null && plausible(now, karooEta)) return Eta(karooEta, approximate = false)
        if (lastKarooEta != null && Duration.between(lastKarooEta.receivedAt, now) <= HOLD && plausible(now, lastKarooEta.eta)) {
            return Eta(lastKarooEta.eta, approximate = true)
        }
        val remaining = remainingM?.takeIf { it.isFinite() && it >= 0 } ?: routeDistanceM.coerceAtLeast(0.0)
        val v = movingAvgSpeedMps?.takeIf { it.isFinite() && it > MIN_MOVING_SPEED_MPS } ?: (fallbackSpeedKmh / 3.6)
        return Eta(now.plusSeconds((remaining / v).toLong()), approximate = true)
    }

    private fun plausible(now: Instant, eta: Instant) =
        !eta.isBefore(now.minusSeconds(60)) && eta.isBefore(now.plus(Duration.ofHours(48)))
}
