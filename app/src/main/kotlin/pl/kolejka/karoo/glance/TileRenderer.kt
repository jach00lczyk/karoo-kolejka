package pl.kolejka.karoo.glance

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.models.ViewConfig
import pl.kolejka.karoo.MainActivity
import pl.kolejka.karoo.domain.plan.TileState
import pl.kolejka.karoo.domain.text.Emphasis
import pl.kolejka.karoo.domain.text.LineSize
import pl.kolejka.karoo.domain.text.TileLayout
import pl.kolejka.karoo.domain.text.TileLine
import pl.kolejka.karoo.domain.text.TileModel
import pl.kolejka.karoo.domain.text.TileText

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
        val (lines, fontSp) = fit(all, widthDp, heightDp)
        val background = if (model.state is TileState.LastTrainMissed) Color(0xFF7A0000) else Color.Black

        return glance.compose(context, DpSize.Unspecified) {
            var modifier = GlanceModifier.fillMaxSize().background(background).padding(horizontal = 4.dp, vertical = 2.dp)
            if (!config.preview) {
                val intent = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                modifier = modifier.clickable(actionStartActivity(intent))
            }
            Column(modifier = modifier) {
                lines.forEachIndexed { i, line ->
                    val size = lineFont(line, fontSp, widthDp).sp
                    val weight = if (!line.small && (i == 0 || line.emphasis != Emphasis.DIM)) FontWeight.Bold else FontWeight.Normal
                    Row {
                        Text(
                            text = line.text,
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(colorOf(line.emphasis)), fontSize = size, fontWeight = weight),
                        )
                        line.badge?.let {
                            Text(
                                text = " · $it",
                                maxLines = 1,
                                style = TextStyle(color = ColorProvider(BADGE), fontSize = size, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }.remoteViews
    }

    /**
     * Czcionka bazowa: zaczynamy od maksimum i zmniejszamy, aż suma wysokości linii (każda
     * z własnym limitem szerokości, [lineFont]) zmieści się w polu. Gdy nawet przy minimum się
     * nie mieści — odcinamy linie od końca (są uporządkowane od najważniejszej).
     */
    private fun fit(all: List<TileLine>, widthDp: Float, heightDp: Float): Pair<List<TileLine>, Float> {
        var lines = all
        var base = MAX_SP
        while (true) {
            val total = lines.sumOf { (lineFont(it, base, widthDp) * LINE_HEIGHT).toDouble() }
            when {
                total <= heightDp || lines.size <= 1 -> return lines to base
                base > MIN_SP -> base = (base * 0.94f).coerceAtLeast(MIN_SP)
                else -> lines = lines.dropLast(1)
            }
        }
    }

    private fun lineFont(line: TileLine, base: Float, widthDp: Float): Float {
        val byWidth = widthDp / (visualLength(line.fullText) * CHAR_WIDTH)
        return minOf(base * scale(line), byWidth).coerceAtLeast(MIN_SP * SMALL)
    }

    /** Emoji (także ⚡✅⏭️⚠️ z bloku U+2300–U+27BF) zajmują ~2 znaki; selektor wariantu — 0. */
    private fun visualLength(s: String): Float =
        s.codePoints().toArray().sumOf { cp ->
            when {
                cp == 0xFE0F -> 0.0
                cp >= 0x1F000 || cp in 0x2300..0x27BF -> 2.0
                else -> 1.0
            }
        }.toFloat().coerceAtLeast(1f)

    private fun scale(l: TileLine) = when (l.size) {
        LineSize.LARGE -> 1f
        LineSize.MEDIUM -> MEDIUM
        LineSize.SMALL -> SMALL
    }

    private fun colorOf(e: Emphasis) = when (e) {
        Emphasis.NORMAL -> Color.White
        Emphasis.WARN -> Color(0xFFFFD000)
        Emphasis.ALERT -> Color.White
        Emphasis.DIM -> Color(0xFFB0B0B0)
    }

    companion object {
        private const val MIN_SP = 14f
        private const val MAX_SP = 26f
        /** Wysokość linii TextView względem czcionki (z odstępami fontu). */
        private const val LINE_HEIGHT = 1.38f
        /** Średnia szerokość znaku w em dla czcionki systemowej (pogrubione cyfry ≈ 0,58). */
        private const val CHAR_WIDTH = 0.58f
        private const val MEDIUM = 0.88f
        private const val SMALL = 0.68f
        private val BADGE = Color(0xFFFF4D4D)
    }
}
