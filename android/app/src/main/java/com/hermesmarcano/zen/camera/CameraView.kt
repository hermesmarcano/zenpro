package com.hermesmarcano.zen.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesmarcano.zen.core.QrCodec
import com.hermesmarcano.zen.core.ScanDebouncer
import com.hermesmarcano.zen.photo.limitedTo
import com.hermesmarcano.zen.photo.rotated
import java.util.concurrent.Executors

/** Vista previa de la cámara trasera con análisis de fotogramas y/o captura de foto opcionales. */
@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
    imageCapture: ImageCapture? = null,
    torchOn: Boolean = false,
    onUnavailable: () -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val onUnavailableState by rememberUpdatedState(onUnavailable)

    DisposableEffect(owner, analyzer, imageCapture) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        future.addListener(
            {
                // Si la vista ya salió de la composición, enlazar la cámara la dejaría ocupada sin dueño.
                if (disposed) return@addListener
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val useCases = mutableListOf<UseCase>(preview)
                    if (analyzer != null) {
                        useCases += ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }
                    }
                    if (imageCapture != null) useCases += imageCapture
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                } catch (e: Exception) {
                    onUnavailableState()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            camera = null
            if (future.isDone) {
                try {
                    future.get().unbindAll()
                } catch (_: Exception) {
                }
            }
            executor.shutdown()
        }
    }

    LaunchedEffect(camera, torchOn) {
        val bound = camera ?: return@LaunchedEffect
        if (bound.cameraInfo.hasFlashUnit()) bound.cameraControl.enableTorch(torchOn)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Lee QR en el plano de luminancia de cada fotograma y avisa en el hilo principal, sin repeticiones. */
class QrAnalyzer(private val onPayload: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val debouncer = ScanDebouncer()
    private val main = Handler(Looper.getMainLooper())

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val payload = QrCodec.decodeLuminance(bytes, image.width, image.height, plane.rowStride)
            if (payload != null && debouncer.shouldNotify(payload, SystemClock.elapsedRealtime())) {
                main.post { onPayload(payload) }
            }
        } catch (_: Exception) {
            // Un fotograma ilegible no debe detener el escáner.
        } finally {
            image.close()
        }
    }
}

fun newImageCapture(): ImageCapture =
    ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()

/** Toma una foto y la entrega ya girada a vertical y reducida, en el hilo principal. */
fun ImageCapture.capture(
    maxSide: Int,
    onBitmap: (android.graphics.Bitmap) -> Unit,
    onFailure: () -> Unit,
) {
    val main = Handler(Looper.getMainLooper())
    // Decodificar un JPEG de 12 MP, girarlo y reducirlo es trabajo pesado: fuera del hilo principal.
    takePicture(
        captureExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    decodeScaled(image, maxSide).rotated(image.imageInfo.rotationDegrees).limitedTo(maxSide)
                } catch (_: Throwable) {
                    null // incluye OutOfMemoryError: se reintenta en vez de tumbar la alarma
                } finally {
                    image.close()
                }
                main.post { if (bitmap != null) onBitmap(bitmap) else onFailure() }
            }

            override fun onError(exception: ImageCaptureException) {
                main.post { onFailure() }
            }
        },
    )
}

private val captureExecutor by lazy { Executors.newSingleThreadExecutor() }

/** Decodifica ya reducido (potencias de 2) en vez de materializar los píxeles completos. */
private fun decodeScaled(image: ImageProxy, maxSide: Int): android.graphics.Bitmap {
    if (image.format != android.graphics.ImageFormat.JPEG) return image.toBitmap()
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    var sample = 1
    while (maxOf(image.width, image.height) / (sample * 2) >= maxSide) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IllegalStateException("JPEG ilegible")
}
