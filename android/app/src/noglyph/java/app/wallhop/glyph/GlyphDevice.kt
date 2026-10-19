package app.wallhop.glyph

import android.content.Context

/** Built without Nothing's Glyph SDK (see android/app/libs/README.md): no Glyphs. */
internal object GlyphDevice {
    val hardware: Glyphs.Hardware? = null
    fun play(context: Context, effect: Glyphs.Effect) = Unit
    fun stop(context: Context) = Unit
}
