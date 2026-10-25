package app.wallhop.ui.screens

import kotlinx.coroutines.delay

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.wallhop.link.QrInvite
import app.wallhop.ui.components.ButtonStyle
import app.wallhop.ui.components.CircleButton
import app.wallhop.ui.components.WButton
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.rememberHaptics
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Full-screen camera that looks for a Wallhop pairing QR. The camera runs only while this
 * screen is open, and frames never leave the phone.
 */
@Composable
fun QrScanScreen(onScanned: (QrInvite) -> Unit, onClose: () -> Unit, onEnterAddress: () -> Unit = onClose) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = rememberHaptics()
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    // Android won't show the prompt again: the button goes to Lync's page in Settings instead.
    var blocked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        asked = true
        blocked = !it && activity != null &&
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    // Back from Settings with the camera allowed: start scanning.
    LifecycleResumeEffect(Unit) {
        if (!allowed && granted()) allowed = true
        onPauseOrDispose { }
    }
    BackHandler(onBack = onClose)
    var notWallhop by remember { mutableStateOf(false) }
    // A Lync code from a newer Mac app than this phone's.
    var newer by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    // A good code: the frame closes in on it and turns mint for a beat, then pairing goes on.
    var found by remember { mutableStateOf<app.wallhop.link.QrInvite?>(null) }
    val lock = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(found) {
        val invite = found ?: return@LaunchedEffect
        haptics.perform(Haptic.Success)
        lock.animateTo(1f, androidx.compose.animation.core.spring(1f, 600f))
        delay(120)
        onScanned(invite)
    }
    // A code that isn't Lync's buzzes once, not on every frame it stays in view.
    var lastWrong by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed && !cameraFailed) {
            CameraPreview(
                onFailure = { cameraFailed = true },
                onCode = { text ->
                    val invite = QrInvite.parse(text)
                    if (invite != null) {
                        found = invite
                        true
                    } else {
                        newer = QrInvite.isFromNewerLync(text)
                        notWallhop = !newer
                        if (text != lastWrong) { lastWrong = text; haptics.perform(Haptic.Failure) }
                        false
                    }
                },
            )
            Viewfinder(lock.value)
        }
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(Modifier.padding(top = top + 8.dp, start = 16.dp)) {
            CircleButton(WIcons.ChevronLeft, "Back", onClick = { onClose() })
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                allowed && cameraFailed -> {
                    val problem = app.wallhop.core.Problems.CAMERA_FAILED
                    Text(problem.title, style = Type.title3, color = Color.White, textAlign = TextAlign.Center)
                    Text(problem.action, style = Type.subheadline, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    WButton(app.wallhop.core.Fix.EnterAddress.label, onEnterAddress, style = ButtonStyle.Filled)
                }
                allowed && newer -> {
                    val problem = app.wallhop.core.Problems.QR_NEWER
                    Text(problem.title, style = Type.title3, color = Color.White, textAlign = TextAlign.Center)
                    Text(problem.action, style = Type.subheadline, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    WButton(app.wallhop.core.Fix.Update.label, { app.wallhop.core.Open.run(context, app.wallhop.core.Fix.Update) }, style = ButtonStyle.Filled)
                }
                allowed -> {
                    Text("Scan the code on your Mac", style = Type.title3, color = Color.White, textAlign = TextAlign.Center)
                    Text(
                        if (notWallhop) "That's not a Lync code. On your Mac, open Lync › Settings › Add a phone › Show code."
                        else "On your Mac, open Lync › Settings › Add a phone › Show code.",
                        style = Type.subheadline, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
                    )
                }
                asked -> {
                    Text("Camera is off for Lync", style = Type.title3, color = Color.White, textAlign = TextAlign.Center)
                    Text(
                        if (blocked) "Allow the camera in Settings to scan the code, or go back and pick your Mac from the list."
                        else "Allow the camera to scan the code, or go back and pick your Mac from the list.",
                        style = Type.subheadline, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (blocked) {
                        WButton("Open Settings", { app.wallhop.core.Open.appSettings(context) }, style = ButtonStyle.Filled)
                    } else {
                        WButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) }, style = ButtonStyle.Filled)
                    }
                }
            }
        }
    }
}

/**
 * CameraX preview plus a ZXing QR decoder on the frames. [onCode] returns true to stop.
 * [onFailure] when the camera can't be started.
 */
@Composable
private fun CameraPreview(onCode: (String) -> Boolean, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Set when the preview leaves the screen. The camera provider can arrive after that, and
    // must not bind the camera then.
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    val main = remember { ContextCompat.getMainExecutor(context) }
    var done by remember { mutableStateOf(false) }
    var lastText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { disposed.set(true) } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                if (disposed.get()) return@addListener
                val provider = runCatching { providerFuture.get() }.getOrElse {
                    onFailure()
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = decode(reader, image)
                    image.close()
                    // The same unusable code is reported once, not on every frame.
                    if (text != null && !done && text != lastText) {
                        main.execute {
                            lastText = text
                            if (!done && !disposed.get() && onCode(text)) {
                                done = true
                                provider.unbindAll()
                            }
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { onFailure() }
            }, main)
            view
        },
        onRelease = {
            disposed.set(true)
            // Without blocking the main thread: unbind once the provider is ready, then stop the analyser.
            val providerFuture = ProcessCameraProvider.getInstance(it.context)
            providerFuture.addListener({
                runCatching { providerFuture.get().unbindAll() }
                executor.shutdown()
            }, main)
        },
    )
}

/** Reads a QR from the brightness (Y) plane, which is all ZXing needs. */
private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val stride = plane.rowStride
    val source = PlanarYUVLuminanceSource(bytes, stride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

/**
 * Dims everything but a rounded square, with bright corner marks. [lock] 0→1 as a code is found:
 * the square closes in a little and the marks turn mint.
 */
@Composable
private fun Viewfinder(lock: Float = 0f) {
    val mint = Color(0xFF5FD9A0)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f * (1f - 0.08f * lock)
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2 - size.height * 0.06f)
        val radius = 32.dp.toPx()
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, topLeft, Size(side, side), CornerRadius(radius), blendMode = BlendMode.Clear)
        val arm = side * 0.16f
        val stroke = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val corners = listOf(
            topLeft to Offset(1f, 1f),
            Offset(topLeft.x + side, topLeft.y) to Offset(-1f, 1f),
            Offset(topLeft.x, topLeft.y + side) to Offset(1f, -1f),
            Offset(topLeft.x + side, topLeft.y + side) to Offset(-1f, -1f),
        )
        corners.forEach { (corner, dir) ->
            val path = Path().apply {
                moveTo(corner.x, corner.y + dir.y * (radius + arm))
                lineTo(corner.x, corner.y + dir.y * radius)
                quadraticTo(corner.x, corner.y, corner.x + dir.x * radius, corner.y)
                lineTo(corner.x + dir.x * (radius + arm), corner.y)
            }
            drawPath(path, androidx.compose.ui.graphics.lerp(Color.White, mint, lock), style = stroke)
        }
    }
}
