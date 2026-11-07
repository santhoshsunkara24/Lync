package app.wallhop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import app.wallhop.R
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkService
import app.wallhop.link.LinkState
import app.wallhop.link.MacInfo
import app.wallhop.ui.MainActivity
import app.wallhop.ui.Pickers
import app.wallhop.ui.design.Contrast
import app.wallhop.ui.design.HomeColors
import app.wallhop.ui.design.themeSeeds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The home screen widget: the paired Mac's name, whether it's connected, its battery, and
 * Photos and Files buttons that open the picker and send. In the phone's colours, like Home.
 */
class MacWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Before Android 12 the launcher doesn't pick the layout by size: do it here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        Thread {
            try { refresh(context) } finally { pending.finish() }
        }.start()
    }

    companion object {
        /** Redraws every Lync widget on the home screen. Reads the wallpaper colours: not on the main thread. */
        fun refresh(context: Context, link: LinkManager = LinkManager.get(context)) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, MacWidget::class.java))
            if (ids.isEmpty()) return
            val look = Look(app, link.state.value, link.macInfo.value, paired = link.pairedMac != null)
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(mapOf(SizeF(110f, 110f) to look.views(R.layout.widget_mac_small), SizeF(250f, 110f) to look.views(R.layout.widget_mac)))
                } else {
                    val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    look.views(if (width < 250) R.layout.widget_mac_small else R.layout.widget_mac)
                }
                manager.updateAppWidget(id, views)
            }
        }

        /** Keeps the widgets up to date while the app runs: the link coming and going, the Mac's battery. */
        fun follow(context: Context, link: LinkManager, scope: CoroutineScope) {
            scope.launch {
                combine(link.state, link.macInfo) { state, info -> Shown.of(state, info) }
                    .distinctUntilChanged()
                    .collect { refresh(context, link) }
            }
        }
    }
}

/** What the widget shows, so it redraws only when that changes (not for a new wallpaper on the Mac). */
private data class Shown(val name: String?, val connected: Boolean, val battery: Int?, val charging: Boolean) {
    companion object {
        fun of(state: LinkState, info: MacInfo) = Shown(
            name = state.macName,
            connected = state is LinkState.Connected,
            battery = if (info.laptop) info.battery else null,
            charging = info.charging,
        )
    }
}

private val LinkState.macName: String?
    get() = when (this) {
        LinkState.NotPaired -> null
        is LinkState.Searching -> macName
        is LinkState.Connected -> macName
        is LinkState.Pairing -> macName
        is LinkState.PairingFailed -> macName
        is LinkState.Forgotten -> macName
    }

/** The widget's colours and words for one moment, bound into either layout (they share ids). */
private class Look(private val context: Context, state: LinkState, info: MacInfo, paired: Boolean) {
    private val shown = Shown.of(state, info)
    // Mid-pairing the state carries the new Mac's name, but there's nothing to send to yet.
    private val paired = paired && state !is LinkState.Forgotten
    private val home = themeSeeds(context)?.let { (seed, accent) -> HomeColors.from(seed, accent) } ?: HomeColors.Default
    // Connected, the card is Home's top colour; away, it steps down to the darker middle tone.
    private val card = (if (shown.connected) home.top else home.mid).toArgb()
    private val pale = home.pale.toArgb()
    private val onPale = home.onPale.toArgb()
    private val quiet = Contrast.blend(Contrast.WHITE, 0.14f, card)
    private val secondaryText = Contrast.blend(Contrast.WHITE, 0.78f, card)
    private val mint = HomeColors.CodeMint.toArgb()

    fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
        val wide = layout == R.layout.widget_mac
        setInt(R.id.widget_card, "setColorFilter", card)
        setInt(R.id.widget_photos_bg, "setColorFilter", pale)
        setInt(R.id.widget_photos_icon, "setColorFilter", onPale)
        setInt(R.id.widget_files_bg, "setColorFilter", quiet)
        setInt(R.id.widget_files_icon, "setColorFilter", Contrast.WHITE)
        if (wide) {
            setImageViewBitmap(R.id.widget_photos_label, text("Photos", R.font.figtree, 14f, onPale, 600))
            setImageViewBitmap(R.id.widget_files_label, text("Files", R.font.figtree, 14f, Contrast.WHITE, 600))
        }

        // Room for the name: the 4×2 card less its padding and the battery; the 2×2 card less its padding.
        setImageViewBitmap(
            R.id.widget_name,
            text(shown.name ?: "Lync", R.font.figtree, if (wide) 17f else 15f, Contrast.WHITE, 600, maxWidthDp = if (wide) 190f else 82f),
        )
        val status = when {
            !paired && shown.name == null -> "Pair your Mac"
            !paired -> "Pair again"
            shown.connected -> "Connected"
            else -> "Not connected"
        }
        setImageViewBitmap(R.id.widget_status, text(status, R.font.figtree, 13f, secondaryText, 500, maxWidthDp = if (wide) 190f else 88f))
        setImageViewResource(R.id.widget_link_icon, if (shown.connected) R.drawable.ic_widget_link else R.drawable.ic_widget_link_break)
        setInt(R.id.widget_link_icon, "setColorFilter", if (shown.connected) mint else secondaryText)

        // The battery is only worth showing while it's live: connected, on a MacBook.
        val battery = shown.battery.takeIf { shown.connected }
        setViewVisibility(R.id.widget_battery_group, if (battery != null) View.VISIBLE else View.GONE)
        if (battery != null) {
            setImageViewBitmap(R.id.widget_battery, text("$battery%", R.font.galindo, if (wide) 26f else 20f, Contrast.WHITE))
            if (wide) {
                setImageViewBitmap(
                    R.id.widget_power,
                    text(if (shown.charging) "Charging" else "On battery", R.font.figtree, 12f, if (shown.charging) mint else secondaryText, 600),
                )
                setViewVisibility(R.id.widget_charging_icon, if (shown.charging) View.VISIBLE else View.GONE)
                setInt(R.id.widget_charging_icon, "setColorFilter", mint)
            }
        }

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setOnClickPendingIntent(R.id.widget_open, open)
        // Not paired yet: the buttons open Lync, where pairing starts. Otherwise straight to the picker;
        // while the Mac is away, what's picked waits and goes across once it's back.
        setOnClickPendingIntent(R.id.widget_photos, if (paired) pick(WidgetPickActivity.PHOTOS) else open)
        setOnClickPendingIntent(R.id.widget_files, if (paired) pick(WidgetPickActivity.FILES) else open)
        setContentDescription(R.id.widget_open, buildString {
            append(shown.name ?: "Lync")
            append(if (shown.connected) ", connected" else ", not connected")
            if (battery != null) append(", battery $battery%" + if (shown.charging) ", charging" else "")
        })
    }

    /**
     * Text drawn as a picture: widgets can't use an app's own fonts, so Figtree and Galindo are
     * drawn here. [weight] sets Figtree's weight axis; a name too long for [maxWidthDp] ends in "…".
     */
    private fun text(value: String, font: Int, sp: Float, color: Int, weight: Int? = null, maxWidthDp: Float? = null): Bitmap {
        val metrics = context.resources.displayMetrics
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, font)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            this.color = color
            if (weight != null) fontVariationSettings = "'wght' $weight"
        }
        val shown = maxWidthDp?.let { TextUtils.ellipsize(value, paint, it * metrics.density, TextUtils.TruncateAt.END).toString() } ?: value
        val fm = paint.fontMetricsInt
        val bitmap = Bitmap.createBitmap(
            kotlin.math.ceil(paint.measureText(shown)).toInt().coerceAtLeast(1), (fm.descent - fm.ascent).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).drawText(shown, 0f, -fm.ascent.toFloat(), paint)
        return bitmap
    }

    private fun pick(kind: Int) = PendingIntent.getActivity(
        context, kind,
        Intent(context, WidgetPickActivity::class.java)
            .putExtra(WidgetPickActivity.EXTRA_KIND, kind)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Opened by the widget's Photos or Files: just the system picker, then the pick goes to the Mac. */
class WidgetPickActivity : ComponentActivity() {
    private val photos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { done(it) }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { done(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // Back from the picker after a restart: the result is on its way.
        Pickers.open = true
        val launched = runCatching {
            if (intent.getIntExtra(EXTRA_KIND, PHOTOS) == PHOTOS) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            } else {
                files.launch(arrayOf("*/*"))
            }
        }
        if (launched.isFailure) done(emptyList())
    }

    private fun done(uris: List<android.net.Uri>) {
        Pickers.open = false
        // The service takes its own read grant on the files, so this screen can close at once.
        if (uris.isNotEmpty()) LinkService.sendFiles(this, uris)
        finish()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val PHOTOS = 1
        const val FILES = 2
    }
}
