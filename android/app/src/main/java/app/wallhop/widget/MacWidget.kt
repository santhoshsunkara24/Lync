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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import app.wallhop.link.TransferPhase
import app.wallhop.link.TransferState
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
            val send = sending
            // While a send is shown only its words and percentage change: those go as a partial
            // update, so the Liftoff's frames aren't sent to the home screen again each time.
            val key = send?.let { it.copy(percent = 0) }
            val partial = send != null && key == shownSend && send.phase == TransferPhase.Sending
            shownSend = key
            for (id in ids) {
                fun pickBy(small: Int, wide: Int, build: (Int) -> RemoteViews) =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(mapOf(SizeF(110f, 110f) to build(small), SizeF(250f, 110f) to build(wide)))
                    } else {
                        val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                        build(if (width < 250) small else wide)
                    }
                when {
                    send == null -> manager.updateAppWidget(id, pickBy(R.layout.widget_mac_small, R.layout.widget_mac) { look.views(it) })
                    partial -> runCatching {
                        manager.partiallyUpdateAppWidget(id, pickBy(R.layout.widget_send_small, R.layout.widget_send) { look.sendWords(it, send) })
                    }.onFailure { manager.updateAppWidget(id, pickBy(R.layout.widget_send_small, R.layout.widget_send) { look.sendViews(it, send) }) }
                    else -> manager.updateAppWidget(id, pickBy(R.layout.widget_send_small, R.layout.widget_send) { look.sendViews(it, send) })
                }
            }
        }

        /** The send the widget shows, or null for its usual face. Set by [follow]. */
        @Volatile private var sending: WidgetSend? = null
        /** What the last full update showed, less the percentage (see refresh). */
        @Volatile private var shownSend: WidgetSend? = null

        /** Keeps the widgets up to date while the app runs: the link coming and going, the Mac's battery. */
        fun follow(context: Context, link: LinkManager, scope: CoroutineScope) {
            scope.launch {
                combine(link.state, link.macInfo) { state, info -> Shown.of(state, info) }
                    .distinctUntilChanged()
                    .collect { refresh(context, link) }
            }
            // Files or photos going across (from the widget, the share sheet or the app): the
            // widget shows the same Liftoff as the sending screen, then "On your Mac" for a moment.
            scope.launch {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                link.transfer
                    .map { t -> t?.takeIf { it.items.isNotEmpty() }?.let(WidgetSend::of) }
                    .distinctUntilChanged()
                    .transformLatest { send ->
                        emit(send)
                        if (send?.phase == TransferPhase.Done) { kotlinx.coroutines.delay(4_000); emit(null) }
                    }
                    .distinctUntilChanged()
                    .collect { send ->
                        sending = send
                        refresh(context, link)
                    }
            }
        }
    }
}

/** A send as the widget shows it; the percentage in steps of 5, so it doesn't redraw for every chunk. */
private data class WidgetSend(val id: String, val phase: TransferPhase, val title: String, val percent: Int, val total: Long) {
    companion object {
        fun of(t: TransferState) = WidgetSend(t.id, t.phase, LinkManager.summary(t.items), (t.progress * 20).toInt() * 5, t.totalBytes)
    }
}

/** The widget's Liftoff, drawn once per colour and kept (30 frames of the send loop, and the stills). */
private object WidgetFrames {
    const val COUNT = 30
    /** Square, in px: sharp at the widget's size, light enough for the home screen to hold. */
    const val SIDE = 240
    private var colors: app.wallhop.ui.design.LinkColors? = null
    private var loop: List<Bitmap> = emptyList()
    private var landed: Bitmap? = null
    private var failed: Bitmap? = null

    @Synchronized fun loop(c: app.wallhop.ui.design.LinkColors): List<Bitmap> { prepare(c); return loop }
    @Synchronized fun landed(c: app.wallhop.ui.design.LinkColors): Bitmap { prepare(c); return landed!! }
    @Synchronized fun failed(c: app.wallhop.ui.design.LinkColors): Bitmap { prepare(c); return failed!! }

    private fun prepare(c: app.wallhop.ui.design.LinkColors) {
        if (c == colors && loop.isNotEmpty()) return
        colors = c
        loop = app.wallhop.ui.components.LiftoffFrames.sending(SIDE, c, COUNT)
        landed = app.wallhop.ui.components.LiftoffFrames.still(SIDE, c, failed = false)
        failed = app.wallhop.ui.components.LiftoffFrames.still(SIDE, c, failed = true)
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

    private val linkColors = app.wallhop.ui.design.LinkColors.from(home)

    /** The widget while a send is shown: Lync's Liftoff (a loop while sending), and its words. */
    fun sendViews(layout: Int, send: WidgetSend) = RemoteViews(context.packageName, layout).apply {
        setInt(R.id.widget_card, "setColorFilter", home.top.toArgb())
        setOnClickPendingIntent(R.id.widget_open, PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
        removeAllViews(R.id.widget_liftoff)
        val frames = when (send.phase) {
            TransferPhase.Sending -> WidgetFrames.loop(linkColors)
            TransferPhase.Done -> listOf(WidgetFrames.landed(linkColors))
            TransferPhase.Failed -> listOf(WidgetFrames.failed(linkColors))
            TransferPhase.Waiting -> listOf(WidgetFrames.landed(linkColors))
        }
        for (frame in frames) {
            addView(R.id.widget_liftoff, RemoteViews(context.packageName, R.layout.widget_frame).apply {
                setImageViewBitmap(R.id.widget_frame, frame)
            })
        }
        applySendWords(this, layout, send)
    }

    /** Only the words and percentage, for the partial updates while it sends. */
    fun sendWords(layout: Int, send: WidgetSend) = RemoteViews(context.packageName, layout).apply { applySendWords(this, layout, send) }

    private fun applySendWords(views: RemoteViews, layout: Int, send: WidgetSend) = with(views) {
        val wide = layout == R.layout.widget_send
        val bright = Contrast.WHITE
        val soft = Contrast.blend(Contrast.WHITE, 0.78f, home.top.toArgb())
        val status = when (send.phase) {
            TransferPhase.Sending -> "${send.percent}%"
            TransferPhase.Waiting -> if (wide) "Waits for your Mac" else "Waiting"
            TransferPhase.Done -> "On your Mac"
            TransferPhase.Failed -> "Didn’t send"
        }
        if (wide) {
            val overline = when (send.phase) {
                TransferPhase.Sending -> "Sending to your Mac"
                TransferPhase.Waiting -> "Waiting for your Mac"
                TransferPhase.Done -> "Sent"
                TransferPhase.Failed -> "Didn’t send"
            }
            setImageViewBitmap(R.id.widget_send_overline, text(overline, R.font.figtree, 12f, soft, 600, maxWidthDp = 150f))
            setImageViewBitmap(R.id.widget_send_title, text(send.title, R.font.figtree, 19f, bright, 600, maxWidthDp = 150f))
            val detail = if (send.phase == TransferPhase.Sending) "${send.percent}% of ${app.wallhop.ui.components.formatBytes(send.total)}" else status
            setImageViewBitmap(R.id.widget_send_status, text(detail, R.font.figtree, 13f, soft, 500, maxWidthDp = 150f))
        } else {
            setImageViewBitmap(R.id.widget_send_title, text(send.title, R.font.figtree, 14f, bright, 600, maxWidthDp = 90f))
            setImageViewBitmap(R.id.widget_send_status, text(status, R.font.galindo, 18f, bright))
        }
        setContentDescription(R.id.widget_open, "${send.title}: " + when (send.phase) {
            TransferPhase.Sending -> "sending to your Mac, ${send.percent}%"
            TransferPhase.Waiting -> "waiting for your Mac"
            TransferPhase.Done -> "on your Mac"
            TransferPhase.Failed -> "didn't send"
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
