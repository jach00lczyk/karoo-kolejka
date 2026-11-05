package pl.kolejka.karoo.domain.text

import pl.kolejka.karoo.domain.model.Station
import pl.kolejka.karoo.domain.model.Train
import pl.kolejka.karoo.domain.plan.Eta
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.plan.TrainClass
import pl.kolejka.karoo.domain.plan.TrainOption
import pl.kolejka.karoo.domain.plan.UpcomingStation
import pl.kolejka.karoo.domain.time.ServiceTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Wszystko, co pokazuje kafelek. */
data class TileModel(
    val state: TileState,
    val upcoming: List<UpcomingStation> = emptyList(),
    val stationName: (Int) -> String = { "#$it" },
    /** ETA mety i pozostały dystans — do linii „ETA" w nagłówku. */
    val finishEta: Eta? = null,
    val remainingM: Double? = null,
    /** Ustawienie „Kafelek otwiera aplikację" (P3). */
    val clickable: Boolean = true,
    /**
     * Ile minut temu udało się pobrać opóźnienia (SPEC 7.3); [NEVER] = jeszcze ani razu,
     * null = nie pokazuj (brak klucza, rozkład bez ID kursów, brak pociągów na kafelku).
     */
    val delayAgeMin: Long? = null,
    /** Trwa sprawdzanie opóźnień — zamiast minut „⏱ …". */
    val delayRefreshing: Boolean = false,
) {
    companion object {
        const val NEVER = Long.MAX_VALUE
    }
}

enum class Emphasis { NORMAL, WARN, ALERT, DIM }

/** Wielkość linii względem czcionki bazowej; renderer dobiera bazową do rozmiaru pola. */
enum class LineSize { LARGE, MEDIUM, SMALL }

/** Kolor fragmentu linii niezależny od [Emphasis] (znaki klas pociągów, opóźnienie). */
enum class Tint { GREEN, BLUE, YELLOW, RED }

/** Fragment linii; [tint] null = kolor całej linii. */
data class Seg(val text: String, val tint: Tint? = null)

data class TileLine(
    val segments: List<Seg>,
    val emphasis: Emphasis = Emphasis.NORMAL,
    val size: LineSize = LineSize.LARGE,
    /** Wyróżnione dopiski na końcu linii (np. „OSTATNI"), rysowane na czerwono. */
    val badge: String? = null,
    /** Linia tworzy blok z następną — renderer nie zostawi jej bez niej (np. stacja bez pociągu). */
    val keepWithNext: Boolean = false,
    /** Linia z godzinami pociągu — renderer daje wszystkim takim liniom tę samą czcionkę. */
    val times: Boolean = false,
    /** Pozioma kreska zamiast tekstu (oddziela metę od stacji po drodze). */
    val divider: Boolean = false,
    /** Tekst wyrównany do prawej w tym samym rozmiarze (wiek danych o opóźnieniach). */
    val trailing: Seg? = null,
    /**
     * Krótsze wersje [segments] (od najdłuższej). Renderer bierze pierwszą, która mieści się bez
     * zmniejszania czcionki — skracamy tylko, gdy trzeba.
     */
    val variants: List<List<Seg>> = emptyList(),
) {
    constructor(
        text: String,
        emphasis: Emphasis = Emphasis.NORMAL,
        size: LineSize = LineSize.LARGE,
        badge: String? = null,
        keepWithNext: Boolean = false,
    ) : this(listOf(Seg(text)), emphasis, size, badge, keepWithNext)

    val text: String get() = segments.joinToString("") { it.text }
    val fullText: String get() = (if (badge != null) "$text · $badge" else text) + (trailing?.let { "  ${it.text}" } ?: "")
    val small: Boolean get() = size == LineSize.SMALL
}

/** Układ zależny od rozmiaru pola: pół szerokości / pełna szerokość / wysoki (≥ pół strony). */
enum class TileLayout { COMPACT, WIDE, TALL }

/**
 * Linie tekstu kafelka w kolejności ważności (SPEC 7.1–7.2). Renderer pokazuje tyle, ile się
 * zmieści. [compact] = wariant mały (pół szerokości).
 */
object TileText {
    private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ServiceTime.ZONE)

    fun hm(i: Instant): String = HM.format(i)

    /** Planowy odjazd z opóźnieniem z API: „17:42 +6" ([tight]: „17:42+6"); SPEC 6.8a. */
    fun dep(t: Train, tight: Boolean = false): String = depSegs(t, tight).joinToString("") { it.text }

    /** Jak [dep], ale minuty opóźnienia jako osobny, czerwony fragment. */
    fun depSegs(t: Train, tight: Boolean = false): List<Seg> {
        val delay = t.delayMin.takeIf { it >= 1 && !t.cancelled } ?: return listOf(Seg(hm(t.departure)))
        return listOf(Seg(hm(t.departure)), Seg(if (tight) "+$delay" else " +$delay", Tint.RED))
    }

    /** „● 17:42 +6 → 18:31": znak klasy w kolorze [tint], opóźnienie na czerwono. */
    private fun timeLine(
        mark: String?,
        tint: Tint?,
        t: Train,
        emphasis: Emphasis,
        tight: Boolean = false,
        suffix: String = "",
        badge: String? = null,
        keepWithNext: Boolean = false,
    ): TileLine {
        val arrow = if (tight) "→" else " → "
        val segs = buildList {
            mark?.let { add(Seg("$it ", tint)) }
            addAll(depSegs(t, tight))
            add(Seg("$arrow${hm(t.labelArrival)}$suffix"))
        }
        return TileLine(segs, emphasis, LineSize.LARGE, badge, keepWithNext, times = true)
    }


    const val CANCELLED = "ODWOŁANY"

    fun lines(m: TileModel, compact: Boolean): List<TileLine> = lines(m, if (compact) TileLayout.COMPACT else TileLayout.WIDE)

    fun lines(m: TileModel, layout: TileLayout): List<TileLine> {
        val s = m.state
        return if (layout == TileLayout.TALL && s is TileState.Ok) tall(s, m) else basic(m, layout == TileLayout.COMPACT, layout == TileLayout.TALL)
    }

    private fun basic(m: TileModel, compact: Boolean, tall: Boolean): List<TileLine> = when (val s = m.state) {
        TileState.NoRoute -> listOf(TileLine("Brak trasy"), TileLine("dotknij: rozkład", Emphasis.DIM))
        TileState.Computing -> listOf(TileLine("Liczę…", Emphasis.DIM))
        TileState.DataMissing -> listOf(TileLine("Brak rozkładu", Emphasis.WARN), TileLine("Połącz z Wi-Fi", Emphasis.DIM))
        is TileState.DataStale -> listOf(TileLine("Rozkład nieaktualny", Emphasis.WARN), TileLine("${s.ageDays} dni", Emphasis.DIM))
        is TileState.AlreadyAtTarget -> listOf(TileLine("Meta przy celu"), TileLine(short(s.station, compact)))
        is TileState.NoTrainsToday -> listOf(TileLine("$WARNING Dziś brak pociągów", Emphasis.WARN), TileLine("z ${short(s.station, compact)}")) + upcomingFor(m, compact, tall)
        is TileState.NoStationNearFinish -> buildList {
            val nearest = s.nearestM?.let { " (najbl. ${km(it)})" }.orEmpty()
            if (tall) {
                add(TileLine("Brak stacji ≤1 km od mety", Emphasis.DIM))
                s.nearestM?.let { add(TileLine("najbliższa ${km(it)}", Emphasis.DIM, size = LineSize.SMALL)) }
            } else {
                add(TileLine(if (compact) "Brak stacji${nearest}" else "Brak stacji ≤1 km od mety$nearest", Emphasis.DIM))
            }
            addAll(upcomingFor(m, compact, tall))
        }
        is TileState.LastTrainMissed -> if (tall) lastMissedTall(s, m) else buildList {
            val last = s.lastFromFinish?.train
            add(TileLine(if (last != null) "$WARNING Ostatni z ${short(s.station, compact)} ${dep(last)} — nie zdążysz" else "$WARNING Nie zdążysz na ostatni", Emphasis.ALERT))
            s.alternative?.let { add(TileLine("Zjedź: ${short(it.station, compact)} ${hm(it.train.departure)} (km ${(it.alongM / 1000).roundToInt()})", Emphasis.ALERT)) }
            addAll(upcomingFor(m, compact, tall))
        }
        is TileState.Ok -> ok(s, m, compact)
    }

    /**
     * Wysoki kafel (wąski: ~255 dp na Karoo 3): meta (skąd → dokąd, ETA i dystans), trzy
     * pociągi (🟠🟢🔵🔴 godziny + szczegóły mniejszą czcionką), potem stacje po drodze w całości
     * małą czcionką (skąd → dokąd, ETA i dystans, pierwszy osiągalny pociąg).
     */
    private fun tall(s: TileState.Ok, m: TileModel): List<TileLine> = buildList {
        val main = s.main.train
        // ⏱ zawsze w pierwszym wierszu, wyrównany do prawej, w rozmiarze nazw stacji (uzgodnione 5.11).
        add(fromToLine(s.station.name, m.stationName(main.labelStationId), Emphasis.NORMAL, LineSize.MEDIUM).copy(trailing = delayChip(m)))
        add(TileLine(ride(m.finishEta?.instant, m.remainingM, s.etaApproximate || s.dataStale), Emphasis.DIM, LineSize.SMALL))
        for (o in s.display) {
            val r = row(o, o === s.main)
            val t = o.train
            add(timeLine(r.mark, r.tint, t, r.emphasis, keepWithNext = true))
            val details = buildList {
                if (t.isBus) add(Seg("$BUS "))
                platformSeg(t)?.let { add(it); add(Seg(" ")) }
                add(Seg(r.detail))
            }
            add(TileLine(details, r.emphasis.takeIf { it != Emphasis.NORMAL } ?: Emphasis.DIM, LineSize.SMALL, badge = "OSTATNI".takeIf { o.isLast }))
        }
        s.safePlan?.let {
            add(TileLine("$WARNING Plan B: ${NameShortener.short(it.station.name, 14)}", Emphasis.WARN, LineSize.SMALL, keepWithNext = true))
            add(TileLine(listOfNotNull(Seg("${hm(it.train.departure)} → ${hm(it.train.labelArrival)} "), platformSeg(it.train)), Emphasis.WARN, LineSize.SMALL))
        }
        addAll(upcomingTall(m))
    }

    /**
     * Wysoki kafel po ostatnim pociągu z mety: duże „nie zdążysz", a pod spodem kompletna
     * propozycja zjazdu (stacja, pociąg z peronem, ETA dojazdu) — jedna długa linia była
     * zmniejszana do nieczytelnej czcionki.
     */
    private fun lastMissedTall(s: TileState.LastTrainMissed, m: TileModel): List<TileLine> = buildList {
        add(TileLine(listOf(Seg("$WARNING Nie zdążysz")), Emphasis.ALERT, trailing = delayChip(m)))
        val last = s.lastFromFinish?.train
        add(TileLine(if (last != null) "ostatni z ${NameShortener.short(s.station.name, 14)} ${dep(last)}" else "na ostatni z ${NameShortener.short(s.station.name, 14)}", Emphasis.ALERT, LineSize.MEDIUM))
        s.alternative?.let { a ->
            val t = a.train
            add(TileLine("Zjedź: ${NameShortener.short(a.station.name, 14)}", Emphasis.ALERT, keepWithNext = true))
            add(timeLine(null, null, t, Emphasis.NORMAL, keepWithNext = true))
            val details = listOfNotNull(
                Seg(NameShortener.short(m.stationName(t.labelStationId), 14) + (if (t.isBus) " $BUS" else "") + " "),
                platformSeg(t),
            )
            add(TileLine(details, Emphasis.ALERT, LineSize.SMALL, keepWithNext = true))
            add(TileLine("$ETA ${hm(a.etaStation)} · km ${(a.alongM / 1000).roundToInt()}", Emphasis.ALERT, LineSize.SMALL))
        }
        addAll(upcomingTall(m))
    }

    /** Stacje po drodze pod kreską oddzielającą je od mety (uzgodnione na Karoo 5.11). */
    private fun upcomingTall(m: TileModel): List<TileLine> =
        if (m.upcoming.isEmpty()) emptyList() else listOf(DIVIDER) + m.upcoming.flatMap { u ->
        val t = u.train
        listOf(
            fromToLine(u.station.name, t?.let { m.stationName(it.labelStationId) }, Emphasis.NORMAL, LineSize.SMALL).copy(keepWithNext = true),
            TileLine(ride(u.eta, u.distanceM, approximate = false), Emphasis.DIM, LineSize.SMALL, keepWithNext = true),
            if (t != null) {
                val bus = if (t.isBus) "$BUS " else ""
                TileLine(listOf(Seg(bus)) + depSegs(t) + listOfNotNull(Seg(" → ${hm(t.labelArrival)} "), platformSeg(t)), Emphasis.NORMAL, LineSize.SMALL)
            } else {
                TileLine("brak pociągu", Emphasis.DIM, LineSize.SMALL)
            },
        )
    }

    /** „Skąd → dokąd" z krótszymi wariantami na wypadek braku miejsca. */
    private fun fromToLine(from: String, to: String?, emphasis: Emphasis, size: LineSize): TileLine {
        val v = NameShortener.fromToVariants(from, to).map { listOf(Seg(it)) }
        return TileLine(v.first(), emphasis, size, variants = v.drop(1))
    }

    /** „ETA 14:38 · 52 km" (≈ gdy ETA z fallbacku). */
    fun ride(eta: Instant?, distanceM: Double?, approximate: Boolean): String {
        val parts = listOfNotNull(eta?.let { (if (approximate) "≈" else "") + hm(it) }, distanceM?.let { km(it) })
        return if (parts.isEmpty()) "$ETA —" else "$ETA ${parts.joinToString(" · ")}"
    }

        /** Zamiast emoji roweru (uzgodnione na Karoo 5.11). */
    const val ETA = "ETA"
    const val BUS = "\uD83D\uDE8C" // 🚌 komunikacja zastępcza
    const val WARNING = "\u26A0\uFE0F" // ⚠️
    // Znaki tekstowe zamiast emoji (węższe; kolor nadaje Tint) — uzgodnione na Karoo 5.11.
    const val CATCHABLE = "●" // do złapania, jeśli przyspieszysz (żółty; ten sam znak co główny)
    const val SURE = "●" // zdążysz wg ETA (zielony)
    const val NEXT = "○" // następny (niebieski)
    const val MISSED = "✕" // nie zdążysz / odwołany (czerwony)

    private fun ok(s: TileState.Ok, m: TileModel, compact: Boolean): List<TileLine> {
        val main = s.main.train
        val approx = if (s.etaApproximate || s.dataStale) " ≈" else ""
        if (compact) {
            return listOf(
                timeLine(null, null, main, if (s.main.slack.toMinutes() < 5) Emphasis.WARN else Emphasis.NORMAL, tight = true),
                TileLine("${NameShortener.tiny(s.station.name)}→${NameShortener.tiny(m.stationName(main.labelStationId))}$approx"),
            ) + s.next?.let { listOf(TileLine(listOf(Seg("$NEXT ", Tint.BLUE)) + depSegs(it.train, tight = true), Emphasis.DIM)) }.orEmpty()
        }
        val platform = platformText(main)?.let { "  [$it]" }.orEmpty()
        return buildList {
            add(TileLine(listOf(Seg("${NameShortener.short(s.station.name, 14)} → ${NameShortener.short(m.stationName(main.labelStationId), 14)}$platform$approx")), trailing = delayChip(m)))
            for (o in s.display) add(rowLine(o, o === s.main))
            s.safePlan?.let { add(TileLine("$WARNING Plan B: ${NameShortener.short(it.station.name, 14)} ${hm(it.train.departure)}", Emphasis.WARN)) }
            addAll(upcoming(m, compact = false))
        }
    }

    /** Kolor znaku klasy: żółty do złapania, zielony główny, niebieski następny, czerwony odpada/odwołany. */
    fun tintOf(o: TrainOption, isMain: Boolean): Tint = when {
        o.cls == TrainClass.MISSED || o.train.cancelled -> Tint.RED
        o.cls == TrainClass.CATCHABLE -> Tint.YELLOW
        isMain -> Tint.GREEN
        else -> Tint.BLUE
    }

    private data class Row(val mark: String, val tint: Tint, val detail: String, val emphasis: Emphasis)

    private fun row(o: TrainOption, isMain: Boolean): Row {
        val mark = when (o.cls) {
            // Główny bywa trzymany przez histerezę mimo ujemnego zapasu — znak mówi prawdę o klasie.
            // Zapas < 5 min sygnalizuje żółty kolor całej linii (Emphasis.WARN).
            TrainClass.SURE -> if (!isMain) NEXT else SURE
            TrainClass.CATCHABLE -> CATCHABLE
            TrainClass.MISSED -> MISSED
        }.let { if (o.train.cancelled) MISSED else it }
        val detail = when {
            o.train.cancelled -> CANCELLED
            !isMain && o.cls == TrainClass.CATCHABLE -> "potrzeba ${o.vReqKmh?.roundToInt() ?: "?"} km/h"
            o.cls == TrainClass.MISSED && !isMain -> "odpada"
            else -> "zapas ${o.slack.toMinutes()} min"
        }
        val emphasis = when {
            isMain && o.slack.toMinutes() < 5 -> Emphasis.WARN
            o.isLast -> Emphasis.WARN
            o.cls == TrainClass.MISSED -> Emphasis.DIM
            else -> Emphasis.NORMAL
        }
        return Row(mark, tintOf(o, isMain), detail, emphasis)
    }

    private fun rowLine(o: TrainOption, isMain: Boolean): TileLine {
        val t = o.train
        val r = row(o, isMain)
        val flags = listOfNotNull(BUS.takeIf { t.isBus }, "OSTATNI".takeIf { o.isLast }).joinToString(" ")
        return timeLine(r.mark, r.tint, t, r.emphasis, tight = true, suffix = "  ${r.detail}${if (flags.isNotEmpty()) "  $flags" else ""}")
    }

    private fun upcomingFor(m: TileModel, compact: Boolean, tall: Boolean) = if (tall) upcomingTall(m) else upcoming(m, compact)

    private fun upcoming(m: TileModel, compact: Boolean): List<TileLine> =
        m.upcoming.map { u ->
            val name = if (compact) NameShortener.tiny(u.station.name) else NameShortener.short(u.station.name, 14)
            val train = u.train?.let { "${dep(it)}${platformText(it)?.let { p -> " [$p]" }.orEmpty()}" } ?: "—"
            TileLine("$name ~${hm(u.eta)}: $train", Emphasis.DIM)
        }

    fun platformText(t: Train): String? = t.platform?.let { p -> "P$p" + (t.track?.let { "/t$it" }.orEmpty()) }

    /**
     * „⏱ 3'" — ile minut temu pobrano opóźnienia (krótko, żeby w pierwszym wierszu zmieściły się stacje): szare do 15 min, żółte do 30, potem czerwone
     * (planer ignoruje dane starsze niż 30 min); „⏱ —" na czerwono, gdy jeszcze się nie udało.
     */
    fun delayChip(m: TileModel): Seg? {
        val min = m.delayAgeMin ?: return null
        if (m.delayRefreshing) return Seg("$CLOCK …")
        val text = when {
            min == TileModel.NEVER -> "$CLOCK —"
            min >= 60 -> "$CLOCK >1h"
            else -> "$CLOCK $min'"
        }
        val tint = when {
            min == TileModel.NEVER || min > 30 -> Tint.RED
            min >= 15 -> Tint.YELLOW
            else -> null
        }
        return Seg(text, tint)
    }

    const val CLOCK = "\u23F1" // ⏱ (bez selektora emoji — węższy)

    /** Peron w nawiasie: „[P2/t4]" (pastylka na jasnym tle wyglądała słabo — Karoo 5.11). */
    private fun platformSeg(t: Train): Seg? = platformText(t)?.let { Seg("[$it]") }

    private val DIVIDER = TileLine(emptyList(), size = LineSize.SMALL, keepWithNext = true, divider = true)

    private fun short(st: Station, compact: Boolean) = if (compact) NameShortener.tiny(st.name) else NameShortener.short(st.name, 16)

    fun km(m: Double): String = when {
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        m < 10_000 -> "%.1f km".format(java.util.Locale("pl"), m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }
}
