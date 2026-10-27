package app.wallhop.glyph

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.wallhop.BuildConfig
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject

/**
 * Glyphs through Nothing's SDK. Phones (1), (2), (2a), (2a) Plus, (3a), (3a) Pro, (4a) and (4b) have
 * Glyph lights; Phone (3) (25 x 25) and (4a) Pro (13 x 13) have the Glyph Matrix. The service is
 * bound only while an effect plays, and everything runs on the main thread.
 */
internal object GlyphDevice {
    private const val TAG = "LyncGlyph"
    private val main = Handler(Looper.getMainLooper())

    /** A phone with Glyph lights: its SDK target and how many channels it has. */
    private class Lights(val target: String, val channels: Int)

    private val lights: Lights? by lazy {
        when {
            Common.is20111() -> Lights(Glyph.DEVICE_20111, 15)
            Common.is22111() -> Lights(Glyph.DEVICE_22111, 33)
            Common.is23111() -> Lights(Glyph.DEVICE_23111, 26)
            Common.is23113() -> Lights(Glyph.DEVICE_23113, 26)
            Common.is24111() -> Lights(Glyph.DEVICE_24111, 36)
            Common.is25111() -> Lights(Glyph.DEVICE_25111, 6)
            Common.is25131() -> Lights(Glyph.DEVICE_25131, 4)
            else -> null
        }
    }

    private val matrixTarget: String? by lazy {
        when {
            Common.is23112() -> Glyph.DEVICE_23112
            Common.is25111p() -> Glyph.DEVICE_25111p
            else -> null
        }
    }

    val hardware: Glyphs.Hardware? by lazy {
        // The SDK needs a Nothing phone on Android 14 or later.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !Build.MANUFACTURER.equals("Nothing", ignoreCase = true)) return@lazy null
        runCatching {
            when {
                matrixTarget != null -> Glyphs.Hardware.Matrix
                lights != null -> Glyphs.Hardware.Lights
                else -> null
            }
        }.getOrNull()
    }

    // Both managers hold the application context only (Glyphs passes applicationContext).
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var lightManager: GlyphManager? = null
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var matrixManager: GlyphMatrixManager? = null
    private var ready = false
    /** The effect waiting for the service to connect. */
    private var pending: (() -> Unit)? = null
    /** Animation steps posted to [main], so a new effect or stop() can cancel them. */
    private val steps = mutableListOf<Runnable>()
    private val release = Runnable { releaseNow() }

    fun play(context: Context, effect: Glyphs.Effect) {
        trace("play $effect on $hardware, ready=$ready")
        main.post {
            cancelSteps()
            main.removeCallbacks(release)
            val run: () -> Unit = when (hardware) {
                Glyphs.Hardware.Lights -> { { playLights(effect) } }
                Glyphs.Hardware.Matrix -> { { playMatrix(context, effect) } }
                null -> return@post
            }
            if (ready) safely(run) else {
                pending = run
                connect(context)
            }
            val lasts = if (effect == Glyphs.Effect.Ringing) 30_000L else CONNECT_MS
            main.postDelayed(release, lasts + 500)
        }
    }

    // ---- File transfers ----------------------------------------------------------------------

    /** The last transfer state shown, and the fill it showed (in steps of 1/24). */
    private var shownTransfer: Glyphs.Transfer = Glyphs.Transfer.Idle
    private var shownFill = -1

    fun transfer(context: Context, state: Glyphs.Transfer) {
        main.post {
            val was = shownTransfer
            when (state) {
                is Glyphs.Transfer.Moving -> {
                    val fill = (state.fraction.coerceIn(0f, 1f) * FILL_STEPS).toInt()
                    if (was is Glyphs.Transfer.Moving && fill == shownFill) return@post
                    shownFill = fill
                    shownTransfer = state
                    if (was !is Glyphs.Transfer.Moving) cancelSteps()
                    session(context) { transferFrame(fill, first = was !is Glyphs.Transfer.Moving) }
                    // Let go if the transfer goes quiet without ending (it always ends normally).
                    main.removeCallbacks(release)
                    main.postDelayed(release, 20_000)
                }
                // Finished: the clasp's flash. Ended without a "done" (incoming files just stop
                // reporting): a full fill means it arrived.
                Glyphs.Transfer.Done, Glyphs.Transfer.Idle, Glyphs.Transfer.Stopped -> {
                    if (was !is Glyphs.Transfer.Moving) return@post
                    shownTransfer = state
                    val arrived = state == Glyphs.Transfer.Done || (state == Glyphs.Transfer.Idle && shownFill >= FILL_STEPS - 1)
                    val fill = shownFill
                    shownFill = -1
                    cancelSteps()
                    session(context) { if (arrived) transferDone() else transferFade(fill) }
                    main.removeCallbacks(release)
                    main.postDelayed(release, 1_600)
                }
            }
        }
    }

    /** Runs [effect] now if the service is bound, or once it is. */
    private fun session(context: Context, effect: () -> Unit) {
        if (ready) safely(effect) else {
            pending = effect
            connect(context)
        }
    }

    private fun transferFrame(fill: Int, first: Boolean) {
        when (hardware) {
            Glyphs.Hardware.Matrix -> matrixLevels(Glyphs.progress(n(), fill.toFloat() / FILL_STEPS))
            // Glyph lights have no picture: a slow breathing for as long as files move.
            Glyphs.Hardware.Lights -> if (first) lightsFrame(period = 1_400, cycles = 400, interval = 200)
            null -> Unit
        }
    }

    /** Arrived: the link squeezes as it catches, lights full, holds, then fades. */
    private fun transferDone() {
        when (hardware) {
            Glyphs.Hardware.Matrix -> {
                val manager = matrixManager ?: return
                val n = n()
                fun show(apart: Int, brightness: Int) = safely { manager.setAppMatrixFrame(frameFor(n, apart, brightness)) }
                show(-1, 255)
                later(90) { show(0, 255) }
                listOf(200, 140, 90, 45, 15, 0).forEachIndexed { k, b -> later(700L + 60L * k) { show(0, b) } }
            }
            Glyphs.Hardware.Lights -> {
                lightManager?.let { runCatching { it.turnOff() } }
                lightsFrame(period = 260, cycles = 2, interval = 120)
            }
            null -> Unit
        }
    }

    /** Failed or cancelled: what was filled fades away, no flash. */
    private fun transferFade(fill: Int) {
        when (hardware) {
            Glyphs.Hardware.Matrix -> {
                val levels = Glyphs.progress(n(), fill.coerceAtLeast(0).toFloat() / FILL_STEPS)
                listOf(0.7f, 0.45f, 0.25f, 0.1f, 0f).forEachIndexed { k, f ->
                    later(70L * k) { matrixLevels(IntArray(levels.size) { (levels[it] * f).toInt() }) }
                }
            }
            Glyphs.Hardware.Lights -> lightManager?.let { runCatching { it.turnOff() } }
            null -> Unit
        }
    }

    /** Brightness levels 0 to 255, row by row, as the Matrix's own 0 to 4095. */
    private fun matrixLevels(levels: IntArray) {
        val manager = matrixManager ?: return
        manager.setAppMatrixFrame(IntArray(levels.size) { levels[it].coerceIn(0, 255) * 4095 / 255 })
    }

    private fun lightsFrame(period: Int, cycles: Int, interval: Int) {
        val manager = lightManager ?: return
        val model = lights ?: return
        val builder = manager.glyphFrameBuilder
        for (channel in 0 until model.channels) builder.buildChannel(channel)
        manager.animate(builder.buildPeriod(period).buildCycles(cycles).buildInterval(interval).build())
    }

    private fun n() = Common.getDeviceMatrixLength()

    private fun frameFor(n: Int, apart: Int, brightness: Int): IntArray {
        val grid = Glyphs.matrix(n, apart)
        return IntArray(n * n) { if (grid[it]) brightness * 4095 / 255 else 0 }
    }

    private const val FILL_STEPS = 24

    fun stop(context: Context) {
        main.post {
            cancelSteps()
            main.removeCallbacks(release)
            releaseNow()
        }
    }

    // ---- Service connection ------------------------------------------------------------------

    private fun connect(context: Context) {
        if (lightManager != null || matrixManager != null) return // Already connecting.
        when (hardware) {
            Glyphs.Hardware.Lights -> {
                val manager = GlyphManager.getInstance(context)
                lightManager = manager
                manager.init(object : GlyphManager.Callback {
                    override fun onServiceConnected(name: ComponentName) {
                        main.post {
                            if (lightManager !== manager) return@post
                            onConnected {
                                manager.register(lights!!.target)
                                manager.openSession()
                            }
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        main.post { if (lightManager === manager) { lightManager = null; ready = false } }
                    }
                })
            }
            Glyphs.Hardware.Matrix -> {
                val manager = GlyphMatrixManager.getInstance(context)
                matrixManager = manager
                trace("binding the matrix service")
                manager.init(object : GlyphMatrixManager.Callback {
                    override fun onServiceConnected(name: ComponentName) {
                        trace("matrix service connected")
                        main.post {
                            if (matrixManager !== manager) return@post
                            onConnected { manager.register(matrixTarget!!) }
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        main.post { if (matrixManager === manager) { matrixManager = null; ready = false } }
                    }
                })
            }
            null -> Unit
        }
    }

    private fun onConnected(setUp: () -> Unit) {
        val ok = runCatching(setUp).onFailure { log("couldn't start a Glyph session", it) }.isSuccess
        if (!ok) { releaseNow(); return }
        ready = true
        pending?.let { pending = null; safely(it) }
    }

    private fun releaseNow() {
        pending = null
        ready = false
        lightManager?.let { m -> runCatching { m.turnOff(); m.closeSession() }; runCatching { m.unInit() } }
        matrixManager?.let { m -> runCatching { m.closeAppMatrix() }; runCatching { m.unInit() } }
        lightManager = null
        matrixManager = null
    }

    // ---- Effects -----------------------------------------------------------------------------

    /** Glyph lights: every channel breathes, twice for a connection, for 30 s while ringing. */
    private fun playLights(effect: Glyphs.Effect) {
        val manager = lightManager ?: return
        val model = lights ?: return
        val builder = manager.glyphFrameBuilder
        for (channel in 0 until model.channels) builder.buildChannel(channel)
        val frame = when (effect) {
            Glyphs.Effect.Connected -> builder.buildPeriod(700).buildCycles(2).buildInterval(150).build()
            Glyphs.Effect.Ringing -> builder.buildPeriod(600).buildCycles(30).buildInterval(400).build()
        }
        manager.animate(frame)
    }

    /**
     * Glyph Matrix: Lync's link. For a connection it plays the app's clasp in pixels (CONNECT):
     * the hooks wait apart and dim, slide in on an ease-out, catch a pixel past each other, settle
     * as they light up full, hold, then fade. While the Mac rings the phone they squeeze together
     * and back.
     */
    private fun playMatrix(context: Context, effect: Glyphs.Effect) {
        val manager = matrixManager ?: return
        val n = Common.getDeviceMatrixLength()
        trace("matrix $n x $n")
        if (BuildConfig.STRESS_HOOKS) trace("raw frames match the SDK's: ${frame(context, n, 0).contentEquals(frameFor(n, 0, 255))}")
        fun show(apart: Int = 0, brightness: Int = 255) =
            safely { manager.setAppMatrixFrame(frame(context, n, apart, brightness)) }
        when (effect) {
            Glyphs.Effect.Connected -> CONNECT.forEach { (at, apart, brightness) ->
                if (at == 0L) show(apart, brightness) else later(at) { show(apart, brightness) }
            }
            Glyphs.Effect.Ringing -> {
                show()
                var squeezed = false
                val pulse = object : Runnable {
                    override fun run() {
                        squeezed = !squeezed
                        show(apart = if (squeezed) -1 else 0)
                        main.postDelayed(this, 350)
                    }
                }
                steps += pulse
                main.postDelayed(pulse, 350)
            }
        }
    }

    private fun frame(context: Context, n: Int, apart: Int, brightness: Int = 255): IntArray {
        val grid = Glyphs.matrix(n, apart)
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        for (i in grid.indices) bitmap.setPixel(i % n, i / n, if (grid[i]) Color.WHITE else Color.BLACK)
        val link = GlyphMatrixObject.Builder()
            .setImageSource(bitmap)
            .setScale(100)
            .setOrientation(0)
            .setPosition(0, 0)
            .setBrightness(brightness.coerceIn(0, 255))
            .build()
        return GlyphMatrixFrame.Builder().addTop(link).build(context).render()
    }

    /**
     * The connection clasp, frame by frame: (ms from the start, how far apart the hooks are in
     * pixels, brightness). The steps shrink as the hooks near (an ease-out), the catch overshoots
     * by a pixel and springs back, and the link brightens as it closes, like the app's LinkCatch.
     */
    private val CONNECT = listOf(
        Triple(0L, 4, 70), Triple(70L, 3, 95), Triple(130L, 2, 125), Triple(185L, 1, 160),
        Triple(235L, 0, 200), Triple(280L, -1, 255), Triple(370L, 0, 255),
        // Hold, then fade out.
        Triple(1_400L, 0, 200), Triple(1_470L, 0, 145), Triple(1_540L, 0, 95), Triple(1_610L, 0, 50),
        Triple(1_680L, 0, 20), Triple(1_750L, 0, 0),
    )
    private const val CONNECT_MS = 1_900L

    // ---- Helpers -----------------------------------------------------------------------------

    private fun later(delayMs: Long, step: () -> Unit) {
        val runnable = Runnable { safely(step) }
        steps += runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun cancelSteps() {
        steps.forEach(main::removeCallbacks)
        steps.clear()
    }

    private fun safely(block: () -> Unit) {
        runCatching(block).onFailure { log("Glyph effect failed", it) }
    }

    /** Test builds: each step of an effect, to see where one stops. */
    private fun trace(message: String) {
        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, message)
    }

    private fun log(message: String, error: Throwable) {
        if (BuildConfig.STRESS_HOOKS) Log.w(TAG, message, error)
    }
}
