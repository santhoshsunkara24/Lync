package app.wallhop.ui.design

import app.wallhop.BuildConfig

/**
 * Stress-test switches for measuring what each glass effect costs. Test builds only; release
 * builds always draw everything.
 *
 *     adb shell setprop debug.wallhop.glass noblur,nolens   (then restart the app)
 *
 * Flags: solid, noblur, nolens, noedge (no scroll edge blur), nodroplet.
 */
object GlassDebug {
    private val flags: Set<String> = if (!BuildConfig.STRESS_HOOKS) emptySet() else runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", "debug.wallhop.glass"))
        process.inputStream.bufferedReader().readText().trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    val solid get() = "solid" in flags
    val noBlur get() = "noblur" in flags
    val noLens get() = "nolens" in flags
    val noEdge get() = "noedge" in flags
    val noDroplet get() = "nodroplet" in flags
}
