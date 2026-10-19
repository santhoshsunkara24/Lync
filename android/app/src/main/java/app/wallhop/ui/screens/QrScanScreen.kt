package app.wallhop.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.RoundRect
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
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Full-screen camera that looks for a Wallhop pairing QR. The camera runs only while this
 * screen is open, and frames never leave the phone.
 */
@Composable
fun QrScanScreen(onScanned: (QrInvite) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)
    var notWallhop by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) {
            CameraPreview(
                onCode = { text ->
                    val invite = QrInvite.parse(text)
                    if (invite != null) {
                        haptics.perform(Haptic.Success)
                        onScanned(invite)
                        true
                    } else {
                        notWallhop = true
                        false
                    }
                },
            )
            Viewfinder()
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
                allowed -> {
                    Text("Scan the code on your Mac", style = Type.title3, color = Color.White, textAlign = TextAlign.Center)
                    Text(
                        if (notWallhop) "That's not a Lync code. On your Mac: menu bar icon › Settings › Add a phone."
                        else "On your Mac, click the Lync icon in the menu bar. The code is in the panel.",
                        style = Type.subheadline, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
                    )
                }
                asked -> {
                    Text("Camera is off for Lync", style = Type.title3, color = Color.White, textAlign = TextAlign.Center)
                    Text("Allow the camera to scan the code, or go back and pick your Mac from the list.",
                        style = Type.subheadline, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    WButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) }, style = ButtonStyle.Filled)
                }
            }
        }
    }
}

/** CameraX preview plus a ZXing QR decoder on the frames. [onCode] returns true to stop. */
@Composable
private fun CameraPreview(onCode: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    val main = remember { ContextCompat.getMainExecutor(context) }
    var done by remember { mutableStateOf(false) }
    var lastText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
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
                            if (!done && onCode(text)) {
                                done = true
                                provider.unbindAll()
                            }
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, main)
            view
        },
        onRelease = { ProcessCameraProvider.getInstance(it.context).get().unbindAll() },
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
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

/** Dims everything but a rounded square, with bright corner marks. */
@Composable
private fun Viewfinder() {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f
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
            drawPath(path, Color.White, style = stroke)
        }
    }
}
