package pl.kolejka.karoo.domain.karoo

import java.time.Instant

/**
 * Interpretacja surowych wartości Karoo. Jednostki ustalone w fazie 0 na Karoo 3
 * (docs/device-findings.md, jazda 25.10.2026):
 * - TIME_OF_ARRIVAL — czas epoki w ms,
 * - TIME_TO_DESTINATION — ms,
 * - DISTANCE_TO_DESTINATION — m,
 * - SPEED / AVERAGE_SPEED — m/s.
 * Każda wartość jest dodatkowo sprawdzana pod kątem sensowności (SPEC 11.6); null → fallback „≈".
 */
object KarooUnits {
    private const val MAX_SPEED_MPS = 30.0

    fun etaFromTimeOfArrival(raw: Double?): Instant? =
        if (raw == null || !raw.isFinite() || raw < 1e12) null else Instant.ofEpochMilli(raw.toLong())

    fun etaFromTimeToDestination(raw: Double?, now: Instant): Instant? =
        if (raw == null || !raw.isFinite() || raw <= 0) null else now.plusMillis(raw.toLong())

    /** Odrzucamy wartości większe niż trasa + zapas na objazd. */
    fun distanceM(raw: Double?, routeDistanceM: Double): Double? = when {
        raw == null || !raw.isFinite() || raw < 0 -> null
        raw > routeDistanceM * 1.5 + 5_000 -> null
        else -> raw
    }

    fun speedMps(raw: Double?): Double? =
        if (raw == null || !raw.isFinite() || raw < 0 || raw > MAX_SPEED_MPS) null else raw
}
