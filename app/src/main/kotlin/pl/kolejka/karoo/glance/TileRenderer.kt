package pl.kolejka.karoo.glance

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.models.ViewConfig
import pl.kolejka.karoo.MainActivity
import pl.kolejka.karoo.ui.Tab
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.text.Emphasis
import pl.kolejka.karoo.domain.text.LineSize
import pl.kolejka.karoo.domain.text.TileLayout
import pl.kolejka.karoo.domain.text.TileLine
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.domain.text.TileText
import pl.kolejka.karoo.domain.text.Tint

/**
 * Kafelek: wysoki kontrast, bez animacji (SPEC 7.1). Rozmiar czcionki dobierany do wysokości
 * z bieżącego [ViewConfig.viewSize] — w jeździe pole bywa wyższe niż w podglądzie.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class TileRenderer(private val context: Context) {
    private val glance = GlanceRemoteViews()

    suspend fun render(model: TileModel, config: ViewConfig): RemoteViews {
        val layout = when {
            config.gridSize.first < 60 -> TileLayout.COMPACT
            config.gridSize.second >= 30 -> TileLayout.TALL
            else -> TileLayout.WIDE
        }
        val all = TileText.lines(model, layout)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val heightDp = config.viewSize.second / density - 6
        val widthDp = config.viewSize.first / density - 10
        val (lines, fonts) = fit(all, widthDp, heightDp)
        val alert = model.state is TileState.LastTrainMissed
        val background = if (alert) Color(0xFF7A0000) else Color.Black

        return glance.compose(context, DpSize.Unspecified) {
            var modifier = GlanceModifier.fillMaxSize().background(background).padding(horizontal = 4.dp, vertical = 2.dp)
            // Podgląd w edytorze nieklikalny (P3); ustawienie może wyłączyć klikalność.
            if (!config.preview && model.clickable) {
                val intent = Intent(context, MainActivity::class.java)
                    .putExtra(Tab.EXTRA_TAB, tabFor(model).name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                modifier = modifier.clickable(actionStartActivity(intent))
            }
            // Glance rysuje w jednym kontenerze najwyżej 10 dzieci, a resztę po cichu pomija
            // (sprawdzone na Karoo 5.11) — linie idą w zagnieżdżonych kolumnach po ≤ 10.
            Column(modifier = modifier) {
                lines.withIndex().chunked(GLANCE_MAX_CHILDREN).forEach { chunk ->
                    Column { chunk.forEach { (i, line) -> LineRow(line, bold = i == 0, fonts[i], alert) } }
                }
            }
        }.remoteViews
    }

    @Composable
    private fun LineRow(line: TileLine, bold: Boolean, fontSp: Float, alert: Boolean) {
        if (line.divider) {
            Column(GlanceModifier.fillMaxWidth().padding(vertical = (DIVIDER_DP - 1.dp) / 2)) {
                Box(GlanceModifier.fillMaxWidth().height(1.dp).background(DIVIDER_COLOR)) {}
            }
            return
        }
        val size = fontSp.sp
        val weight = if (!line.small && (bold || line.emphasis != Emphasis.DIM)) FontWeight.Bold else FontWeight.Normal
        val rowModifier = if (line.trailing != null) GlanceModifier.fillMaxWidth() else GlanceModifier
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            // Glance łączy kolejne Texty w wierszu bez odstępów — fragmenty niosą własne spacje.
            line.segments.forEach { seg ->
                Text(
                    text = seg.text,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(seg.tint?.let(::tintColor) ?: colorOf(line.emphasis)), fontSize = size, fontWeight = weight),
                )
            }
            line.badge?.let {
                Text(
                    text = " · $it",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(BADGE), fontSize = size, fontWeight = FontWeight.Bold),
                )
            }
            line.trailing?.let { t ->
                // Wiek opóźnień: zwykły tekst w kolorze zależnym od wieku, przy prawej krawędzi.
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = t.text,
                    maxLines = 1,
                    // Na czerwonym tle („nie zdążysz") czerwony tekst ginie — wtedy biały.
                    style = TextStyle(color = ColorProvider(if (alert && t.tint == Tint.RED) Color.White else t.tint?.let(::tintColor) ?: colorOf(Emphasis.DIM)), fontSize = size, fontWeight = weight),
                )
            }
        }
    }

    /**
     * Czcionka bazowa: zaczynamy od maksimum i zmniejszamy, aż suma wysokości linii (każda
     * z własnym limitem szerokości, [lineFont]) zmieści się w polu. Gdy nawet przy minimum się
     * nie mieści — odcinamy linie od końca (są uporządkowane od najważniejszej), całymi blokami
     * ([TileLine.keepWithNext]), żeby nie zostawić np. nazwy stacji bez jej pociągu.
     * Zwraca linie i czcionkę każdej z nich.
     */
    private fun fit(all: List<TileLine>, widthDp: Float, heightDp: Float): Pair<List<TileLine>, List<Float>> {
        var lines = all
        var base = MAX_SP
        while (true) {
            val chosen = lines.map { shortest(it, base, widthDp) }
            val fonts = fonts(chosen, base, widthDp)
            val total = chosen.indices.sumOf { i -> lineHeight(chosen[i], fonts[i]).toDouble() }
            when {
                total <= heightDp || lines.size <= 1 -> return chosen to fonts
                base > MIN_SP -> base = (base * 0.94f).coerceAtLeast(MIN_SP)
                else -> {
                    lines = lines.dropLast(1)
                    while (lines.size > 1 && lines.last().keepWithNext) lines = lines.dropLast(1)
                }
            }
        }
    }

    /**
     * Czcionki linii; wszystkie linie z godzinami ([TileLine.times]) dostają tę samą — najmniejszą
     * z nich — żeby dłuższa linia (np. z opóźnieniem) nie wyglądała na mniej ważną.
     */
    private fun fonts(lines: List<TileLine>, base: Float, widthDp: Float): List<Float> {
        val each = lines.map { lineFont(it, base, widthDp) }
        val times = lines.indices.filter { lines[it].times }.minOfOrNull { each[it] } ?: return each
        return lines.indices.map { if (lines[it].times) times else each[it] }
    }

    /**
     * Pierwszy wariant linii ([TileLine.variants]), który mieści się w czytelnej czcionce
     * ([LEGIBLE_SP], nie większej niż wynika z [base]); gdy żaden — najkrótszy. Dłuższa nazwa jest
     * ważniejsza niż większa czcionka.
     */
    private fun shortest(line: TileLine, base: Float, widthDp: Float): TileLine {
        if (line.variants.isEmpty()) return line
        val desired = minOf(base * scale(line), LEGIBLE_SP * scale(line))
        val all = listOf(line.segments) + line.variants
        val fits = all.firstOrNull { segs -> widthFont(line.copy(segments = segs), widthDp) >= desired } ?: all.last()
        return line.copy(segments = fits, variants = emptyList())
    }

    private fun widthFont(line: TileLine, widthDp: Float): Float = widthDp / emWidth(line.fullText)

    private fun lineHeight(line: TileLine, font: Float): Float = if (line.divider) DIVIDER_DP.value else font * LINE_HEIGHT

    private fun lineFont(line: TileLine, base: Float, widthDp: Float): Float {
        return minOf(base * scale(line), widthFont(line, widthDp)).coerceAtLeast(MIN_SP * SMALL)
    }

    /**
     * Szacowana szerokość tekstu w em (czcionka systemowa, pogrubiona). Zmierzone na Karoo 5.11:
     * pogrubione litery ≈ 0,6 em, cyfry ≈ 0,58, spacje i interpunkcja wąskie. Emoji (U+1F000+, ⏱, oraz znaki
     * z U+2300–U+27BF z selektorem U+FE0F) ≈ 1,1 em; same ● ○ ✕ są zwykłym tekstem.
     */
    private fun emWidth(s: String): Float {
        val cps = s.codePoints().toArray()
        return cps.indices.sumOf { i ->
            val cp = cps[i]
            when {
                cp == 0xFE0F -> 0.0
                cp >= 0x1F000 || cp == 0x23F1 -> 1.1
                cp in 0x2300..0x27BF && cps.getOrNull(i + 1) == 0xFE0F -> 1.1
                cp == ' '.code -> 0.28
                cp in '0'.code..'9'.code -> 0.58
                cp.toChar() in ".,:;'[]|" -> 0.3
                else -> 0.6
            }
        }.toFloat().coerceAtLeast(0.5f)
    }

    private fun scale(l: TileLine) = when (l.size) {
        LineSize.LARGE -> 1f
        LineSize.MEDIUM -> MEDIUM
        LineSize.SMALL -> SMALL
    }

    /** Zakładka otwierana kliknięciem kafelka (SPEC 7.4). */
    private fun tabFor(model: TileModel): Tab = when (val s = model.state) {
        is TileState.Ok, is TileState.NoTrainsToday, is TileState.AlreadyAtTarget -> Tab.FINISH
        is TileState.LastTrainMissed -> if (s.alternative != null) Tab.ON_ROUTE else Tab.FINISH
        is TileState.NoStationNearFinish -> if (s.stationsOnRoute > 0) Tab.ON_ROUTE else Tab.ALL
        else -> Tab.ALL
    }

    private fun tintColor(t: Tint) = when (t) {
        Tint.GREEN -> Color(0xFF34D058)
        Tint.BLUE -> Color(0xFF4DA3FF)
        Tint.YELLOW -> Color(0xFFFFD000)
        Tint.RED -> BADGE
    }

    private fun colorOf(e: Emphasis) = when (e) {
        Emphasis.NORMAL -> Color.White
        Emphasis.WARN -> Color(0xFFFFD000)
        Emphasis.ALERT -> Color.White
        Emphasis.DIM -> Color(0xFFB0B0B0)
    }

    companion object {
        private const val GLANCE_MAX_CHILDREN = 10
        private val DIVIDER_DP = 9.dp
        private val DIVIDER_COLOR = Color(0xFF707070)
        private const val MIN_SP = 14f
        private const val MAX_SP = 26f
        /** Wysokość linii TextView względem czcionki (z odstępami fontu). */
        private const val LINE_HEIGHT = 1.38f
        /** Czcionka, przy której nazwy stacji uznajemy za czytelne (przed skalą rozmiaru linii). */
        private const val LEGIBLE_SP = 19f
        private const val MEDIUM = 0.88f
        private const val SMALL = 0.68f
        private val BADGE = Color(0xFFFF4D4D)
    }
}
