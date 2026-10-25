package pl.kolejka.karoo.probe

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import pl.kolejka.karoo.MainActivity
import pl.kolejka.karoo.log.FileLog

/**
 * FAZA 0 — graficzne pole pokazujące surowe wartości z [DeviceProbe]. Loguje [ViewConfig]
 * każdego rozmiaru (do zaprojektowania wariantów kafelka, SPEC 7.1) i sprawdza P2/P3.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ProbeDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        FileLog.i("PROBE startView config=$config")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        scope.launch {
            DeviceProbe.state
                .sample(2_000)
                .distinctUntilChanged()
                .collect { snap ->
                    val lines = buildList {
                        add("nav=${snap.nav} (#${snap.navEvents}) ride=${snap.ride}")
                        snap.streams.forEach { (k, v) -> add("$k: $v") }
                        add("grid=${config.gridSize} px=${config.viewSize} preview=${config.preview}")
                    }
                    val result = glance.compose(context, DpSize.Unspecified) {
                        var modifier = GlanceModifier.fillMaxSize().background(Color.Black).padding(4.dp)
                        if (!config.preview) {
                            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            modifier = modifier.clickable(actionStartActivity(intent))
                        }
                        Column(modifier = modifier) {
                            lines.forEach {
                                Text(it, style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp), maxLines = 2)
                            }
                        }
                    }
                    emitter.updateView(result.remoteViews)
                }
        }
        emitter.setCancellable {
            FileLog.i("PROBE stopView")
            scope.cancel()
        }
    }

    companion object {
        const val TYPE_ID = "probe"
        private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
    }
}
