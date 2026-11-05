package pl.kolejka.karoo.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.kolejka.karoo.BuildConfig
import pl.kolejka.karoo.domain.geo.LatLon
import pl.kolejka.karoo.domain.model.LiveTimes
import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.Alternative
import pl.kolejka.karoo.domain.plan.Eta
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainClass
import pl.kolejka.karoo.domain.plan.TrainOption
import pl.kolejka.karoo.domain.plan.UpcomingStation
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.log.FileLog
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tryb demo kafelka — tylko build debug: zmyślone stany do sprawdzania wyglądu na Karoo bez jazdy.
 * Przełączanie z komputera (nic nie zapisuje się na dysk, restart procesu wyłącza demo):
 *
 *     adb shell am broadcast -a pl.kolejka.karoo.DEMO -p pl.kolejka.karoo --ei n 3   # scenariusz 3
 *     adb shell am broadcast -a pl.kolejka.karoo.DEMO -p pl.kolejka.karoo --ei n 0   # wyłącz
 *
 * albo przyciskami w zakładce Opcje. Czasy liczone względem bieżącej minuty.
 */
object DemoScenarios {
    const val ACTION = "pl.kolejka.karoo.DEMO"

    private val _current = MutableStateFlow<Int?>(null)

    /** Numer scenariusza (1…[count]) albo null = prawdziwe dane. */
    val current: StateFlow<Int?> get() = _current

    val names = listOf(
        "Opóźnienie +6, do złapania i następny",
        "Następny pociąg odwołany",
        "Na styk (⚠️), OSTATNI i plan B",
        "ZKA, opóźnienie +25, ETA ≈",
        "Ostatni z mety — nie zdążysz",
        "Brak stacji przy mecie, stacje po drodze",
        "Brak trasy",
        "Brak rozkładu",
    )
    val count get() = names.size

    fun set(n: Int?) {
        if (!BuildConfig.DEBUG) return
        val v = n?.takeIf { it in 1..count }
        _current.value = v
        FileLog.i("Demo kafelka: ${v?.let { "$it. ${names[it - 1]}" } ?: "wyłączone"}")
    }

    fun next() = set(((_current.value ?: 0) % count) + 1)
    fun previous() = set(((_current.value ?: 1) + count - 2) % count + 1)

    /** Odbiornik `adb shell am broadcast` — rejestrowany tylko w debug. */
    fun register(context: Context) {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getStringExtra("step")) {
                    "next" -> next()
                    "prev" -> previous()
                    else -> set(intent.getIntExtra("n", 0))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    // ------------------------------------------------------------------ dane

    private fun st(id: Int, name: String) = Station(id, name, name.lowercase(), LatLon(52.2, 21.0), "KM", null)
    private val minsk = st(39008, "Mińsk Mazowiecki")
    private val sulejowek = st(38711, "Sulejówek Miłosna")
    private val debe = st(39123, "Dębe Wielkie")
    private val tluszcz = st(36806, "Tłuszcz")
    private const val GDANSKA = 36103
    private const val SRODMIESCIE = 33571
    private val stationNames = mapOf(
        GDANSKA to "Warszawa Gdańska", SRODMIESCIE to "Warszawa Śródmieście",
        minsk.id to minsk.name, sulejowek.id to sulejowek.name, debe.id to debe.name, tluszcz.id to tluszcz.name,
    )
    private val nameOf: (Int) -> String = { stationNames[it] ?: "#$it" }

    fun model(n: Int, nowInstant: Instant): TileModel {
        val now = nowInstant.truncatedTo(ChronoUnit.MINUTES)
        fun m(min: Long): Instant = now.plus(Duration.ofMinutes(min))
        fun train(
            id: Int, dep: Long, ride: Long, platform: String? = "2", track: String? = null, to: Int = GDANSKA,
            from: Station = minsk, line: String = "R2", bus: Boolean = false, delay: Long? = null, cancelled: Boolean = false,
        ) = Train(
            tripId = id, serviceDate = now.atZone(java.time.ZoneId.of("Europe/Warsaw")).toLocalDate(), fromStationId = from.id,
            departure = m(dep), platform = platform, track = track, agency = "KM", routeShortName = line, isBus = bus,
            labelGroup = 0, labelStationId = to, labelArrival = m(dep + ride), earliestArrival = m(dep + ride),
            live = if (delay != null || cancelled) LiveTimes(m(dep + (delay ?: 0)), null, cancelled, now.minusSeconds(120)) else null,
        )
        fun opt(t: Train, cls: TrainClass, slack: Long, vReq: Double? = null, last: Boolean = false) =
            TrainOption(t, cls, Duration.ofMinutes(slack), vReq, last)
        val upcoming = listOf(
            UpcomingStation(sulejowek, 21_000.0, 300.0, 9_400.0, m(22), listOf(train(11, 31, 26, "1", from = sulejowek))),
            UpcomingStation(debe, 28_000.0, 200.0, 16_400.0, m(41), listOf(train(12, 55, 39, "2", from = debe, delay = 4))),
        )
        val eta = Eta(m(48), approximate = false)

        val state: TileState = when (n) {
            1 -> TileState.Ok(
                minsk, 400.0,
                listOf(
                    opt(train(1, 50, 49), TrainClass.CATCHABLE, -6, vReq = 27.0),
                    opt(train(2, 70, 49, track = "4", delay = 6), TrainClass.SURE, 12),
                    opt(train(3, 100, 49, "1"), TrainClass.SURE, 42),
                ),
                mainIndex = 1, etaApproximate = false,
            )
            2 -> TileState.Ok(
                minsk, 400.0,
                listOf(
                    opt(train(1, 64, 49, delay = 2), TrainClass.SURE, 8),
                    opt(train(2, 94, 49, cancelled = true), TrainClass.MISSED, 38),
                    opt(train(3, 124, 49, "1"), TrainClass.SURE, 68),
                ),
                mainIndex = 0, etaApproximate = false,
            )
            3 -> TileState.Ok(
                minsk, 650.0,
                listOf(opt(train(1, 59, 52, "3", "1", to = SRODMIESCIE, line = "RE2"), TrainClass.SURE, 3, last = true)),
                mainIndex = 0, etaApproximate = false,
                safePlan = Alternative(debe, 28_000.0, m(41), train(12, 52, 39, "2", from = debe)),
            )
            4 -> TileState.Ok(
                minsk, 400.0,
                listOf(
                    opt(train(1, 60, 75, null, line = "Z61", bus = true, delay = 25), TrainClass.SURE, 33),
                    opt(train(2, 120, 75, null, line = "Z61", bus = true), TrainClass.SURE, 68),
                ),
                mainIndex = 0, etaApproximate = true,
            )
            5 -> TileState.LastTrainMissed(
                minsk,
                opt(train(1, 35, 49), TrainClass.MISSED, -18, last = true),
                Alternative(tluszcz, 64_000.0, m(20), train(13, 33, 41, "1", from = tluszcz, line = "R8")),
                etaApproximate = false,
            )
            6 -> TileState.NoStationNearFinish(2_400.0, stationsOnRoute = 2)
            7 -> TileState.NoRoute
            else -> TileState.DataMissing
        }
        val withRoute = n !in setOf(7, 8)
        return TileModel(
            state = state,
            upcoming = if (withRoute && n != 5) upcoming else emptyList(),
            stationName = nameOf,
            finishEta = eta.takeIf { withRoute },
            remainingM = 24_300.0.takeIf { withRoute },
            // ⏱: świeże (szare), 18 min (żółte), 41 min i „nigdy" (czerwone); 6–8 bez opóźnień.
            delayAgeMin = when (n) { 1 -> 2L; 2 -> 18L; 3 -> 7L; 4 -> 41L; 5 -> TileModel.NEVER; else -> null },
        )
    }
}
