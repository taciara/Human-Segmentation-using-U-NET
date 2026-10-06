package online.seuprojeto.filtrofanta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class DeviceCameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit,
    private val onFrameBitmap: (Bitmap) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraThread = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var started = false
    private val previewW = 320
    private val previewH = 240
    private var frameCount = 0
    private var lastFrameTime = 0L
    private val frameIntervalMs = 100 // Limitar a 10 FPS

    fun start() {
        if (started) return
        started = true
        onStatus("Abrindo câmera do dispositivo…")
        Log.i(TAG, "DeviceCameraController.start()")

        // Configurar TextureView listener
        if (!textureView.isAvailable) {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    cameraThread.execute { openCamera() }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        } else {
            cameraThread.execute { openCamera() }
        }
    }

    fun stop() {
        started = false
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar câmera", e)
        }
    }

    private fun openCamera() {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                mainHandler.post { onStatus("Câmera: permissão negada") }
                return
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Preferir câmera frontal (ID 1), se não existir, usar traseira (ID 0)
            var cameraId = "1"
            val availableCameras = cameraManager.cameraIdList
            if ("1" !in availableCameras) {
                cameraId = availableCameras.firstOrNull() ?: run {
                    mainHandler.post { onStatus("Câmera: nenhuma câmera encontrada") }
                    return
                }
            }

            Log.i(TAG, "Abrindo câmera frontal: $cameraId (disponíveis: ${availableCameras.joinToString()})")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera device opened")
                    cameraDevice = camera
                    createCameraSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.i(TAG, "Camera disconnected")
                    cameraDevice?.close()
                    cameraDevice = null
                    mainHandler.post { onStatus("Câmera desconectada") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cameraDevice?.close()
                    cameraDevice = null
                    mainHandler.post { onStatus("Erro na câmera: $error") }
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir câmera", e)
            mainHandler.post { onStatus("Câmera: erro - ${e.message}") }
        }
    }

    private fun createCameraSession(camera: CameraDevice) {
        try {
            Log.i(TAG, "Creating camera session ($previewW x $previewH)")
            imageReader = ImageReader.newInstance(previewW, previewH, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // Throttle: processar apenas 1 frame a cada 100ms
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < frameIntervalMs) {
                        return@setOnImageAvailableListener
                    }
                    lastFrameTime = now

                    frameCount++
                    if (frameCount % 10 == 0) {
                        Log.i(TAG, "Frames processados: $frameCount (${image.width}x${image.height})")
                    }
                    val bitmap = yuvToRgb(image)
                    onFrameBitmap(bitmap)
                } finally {
                    image.close()
                }
            }, mainHandler)

            camera.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.i(TAG, "Capture session configured")
                    captureSession = session
                    startPreview(camera)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    mainHandler.post { onStatus("Câmera: falha ao configurar") }
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar sessão", e)
            mainHandler.post { onStatus("Câmera: erro na sessão - ${e.message}") }
        }
    }

    private fun startPreview(camera: CameraDevice) {
        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            captureSession?.setRepeatingRequest(captureRequest.build(), null, mainHandler)
            Log.i(TAG, "Preview started (ImageReader only)")
            mainHandler.post { onStatus("Câmera do dispositivo conectada") }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar preview", e)
            mainHandler.post { onStatus("Câmera: erro no preview - ${e.message}") }
        }
    }

    private fun yuvToRgb(image: Image): Bitmap {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val ySize = yPlane.buffer.remaining()
        val uvSize = uPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uvSize * 2)
        yPlane.buffer.get(nv21, 0, ySize)
        uPlane.buffer.get(nv21, ySize, uvSize)
        vPlane.buffer.get(nv21, ySize + uvSize, uvSize)

        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(image.width * image.height)

        var pixelIndex = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val yIndex = y * image.width + x
                val uvIndex = ySize + (y / 2) * image.width + (x / 2) * 2

                val yVal = nv21[yIndex].toInt() and 0xff
                val uVal = (nv21[uvIndex].toInt() and 0xff) - 128
                val vVal = (nv21[uvIndex + 1].toInt() and 0xff) - 128

                val r = (yVal + 1.402 * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.34414 * uVal - 0.71414 * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772 * uVal).toInt().coerceIn(0, 255)

                pixels[pixelIndex++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return bitmap
    }

    companion object {
        private const val TAG = "DeviceCameraController"
    }
}
