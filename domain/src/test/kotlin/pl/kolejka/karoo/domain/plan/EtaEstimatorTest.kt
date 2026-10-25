package pl.kolejka.karoo.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EtaEstimatorTest {
    private val now = Instant.parse("2026-10-26T15:00:00Z")

    private fun eta(
        karoo: Instant? = null,
        last: KarooEtaSample? = null,
        remaining: Double? = 10_000.0,
        avg: Double? = null,
    ) = EtaEstimator.finishEta(now, karoo, last, remaining, 40_000.0, avg, 25.0)

    @Test
    fun `karoo eta used when streaming`() {
        assertEquals(Eta(now.plusSeconds(1800), false), eta(karoo = now.plusSeconds(1800)))
    }

    @Test
    fun `last karoo eta held for two minutes as approximate`() {
        val sample = KarooEtaSample(now.plusSeconds(1800), receivedAt = now.minusSeconds(110))
        assertEquals(Eta(now.plusSeconds(1800), true), eta(last = sample))
        val old = KarooEtaSample(now.plusSeconds(1800), receivedAt = now.minusSeconds(130))
        assertEquals(Eta(now.plusSeconds(1440), true), eta(last = old)) // 10 km / 25 km/h
    }

    @Test
    fun `fallback uses moving average, then default speed, then route distance`() {
        assertEquals(now.plusSeconds(2000), eta(avg = 5.0).instant)
        assertEquals(now.plusSeconds(1440), eta(avg = 0.1).instant) // stoi → prędkość domyślna
        assertEquals(now.plusSeconds(5760), eta(remaining = null).instant) // 40 km przed startem
        assertEquals(now.plusSeconds(5760), eta(remaining = Double.NaN).instant)
    }

    @Test
    fun `implausible karoo eta is ignored`() {
        assertEquals(true, eta(karoo = now.minusSeconds(3600)).approximate)
        assertEquals(true, eta(karoo = Instant.EPOCH).approximate)
    }
}
