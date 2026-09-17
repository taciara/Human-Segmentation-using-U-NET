package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.TextureView
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class UvcCameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit,
    private val onFrameBitmap: (Bitmap) -> Unit,
) : TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeBusy = AtomicBoolean(false)
    private val frameExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var cameraHelper: ICameraHelper? = null
    private var usbStarted = false
    private var previewReady = false
    private var previewW = 640
    private var previewH = 480
    private var previewSurface: SurfaceTexture? = null
    private var previewGlThread: HandlerThread? = null
    private var frameLog = 0

    fun start() {
        if (usbStarted && cameraHelper != null) return
        usbStarted = true
        onStatus("Procurando câmera USB (EMEET)…")
        val helper = CameraHelper()
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                Log.i(TAG, "USB attach ${device.deviceName}")
                onStatus("Permita USB: OK → Filtro Fanta → Sempre")
                helper.selectDevice(device)
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                scheduleOpenCamera(helper, 0)
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (previewReady) return
                val sz = helper.previewSize
                if (sz != null) {
                    previewW = sz.width
                    previewH = sz.height
                }
                previewReady = true
                Log.i(TAG, "camera open ${previewW}x${previewH}")
                onStatus("Câmera USB conectada")
                attachSurface()
                helper.setFrameCallback(IFrameCallback { buf -> onFrame(buf) }, UVCCamera.PIXEL_FORMAT_NV21)
                helper.startPreview()
            }

            override fun onCameraClose(device: UsbDevice) {
                previewReady = false
            }

            override fun onDeviceClose(device: UsbDevice) {
                previewReady = false
            }

            override fun onDetach(device: UsbDevice) {
                previewReady = false
                onStatus("Câmera desconectada. Reconecte a EMEET.")
            }

            override fun onCancel(device: UsbDevice) {
                Log.w(TAG, "USB cancel ${device.deviceName}")
                usbStarted = false
                previewReady = false
                onStatus("Toque OK e escolha Sempre no Filtro Fanta")
                mainHandler.postDelayed({ helper.selectDevice(device) }, 1200)
            }
        })
        cameraHelper = helper
        val list = helper.deviceList
        Log.i(TAG, "USB devices ${list?.size ?: 0}")
        if (!list.isNullOrEmpty()) helper.selectDevice(list[0])
    }

    fun onUsbIntent() {
        restartUsb("usb-intent")
    }

    fun release() {
        try {
            frameExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            cameraHelper?.release()
        } catch (_: Exception) {
        }
        cameraHelper = null
        try {
            previewSurface?.release()
        } catch (_: Exception) {
        }
        previewSurface = null
        try {
            previewGlThread?.quitSafely()
        } catch (_: Exception) {
        }
        previewGlThread = null
    }

    private fun restartUsb(reason: String) {
        Log.i(TAG, "restartUsb $reason")
        usbStarted = false
        previewReady = false
        frameLog = 0
        try {
            cameraHelper?.release()
        } catch (_: Exception) {
        }
        cameraHelper = null
        start()
    }

    private fun scheduleOpenCamera(helper: ICameraHelper, attempt: Int) {
        val delayMs = if (attempt == 0) 250L else 450L
        mainHandler.postDelayed({
            val sizes = helper.supportedSizeList
            Log.i(TAG, "open attempt=$attempt sizes=${sizes?.size ?: 0}")
            val pick = pickPreviewSize(sizes)
            when {
                pick != null -> openWithQuirk(helper, pick)
                attempt < 5 -> scheduleOpenCamera(helper, attempt + 1)
                else -> {
                    Log.w(TAG, "forced MJPEG 320x240")
                    openWithQuirk(helper, Size(UVCCamera.FRAME_FORMAT_MJPEG, 320, 240, 15, null))
                }
            }
        }, delayMs)
    }

    private fun openWithQuirk(helper: ICameraHelper, size: Size) {
        try {
            val param = UVCParam(size, UVCCamera.UVC_QUIRK_FIX_BANDWIDTH)
            helper.openCamera(param)
        } catch (err: Exception) {
            Log.e(TAG, "openCamera(UVCParam)", err)
            try {
                helper.openCamera(size)
            } catch (err2: Exception) {
                Log.e(TAG, "openCamera(Size)", err2)
                helper.openCamera()
            }
        }
    }

    private fun pickPreviewSize(sizes: List<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        val mjpeg = sizes.filter { it.type == UVCCamera.FRAME_FORMAT_MJPEG }
        val pool = if (mjpeg.isNotEmpty()) mjpeg else sizes
        val prefer = listOf(640 to 480, 800 to 600, 1280 to 720, 320 to 240)
        for ((w, h) in prefer) {
            pool.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return pool.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: pool.minByOrNull { it.width * it.height }
    }

    private fun ensurePreviewSurface(): SurfaceTexture {
        previewSurface?.let { return it }
        val st = SurfaceTexture(OFFSCREEN_TEX_ID)
        st.setDefaultBufferSize(1280, 720)
        val ht = HandlerThread("UvcPreview").also { it.start() }
        previewGlThread = ht
        st.setOnFrameAvailableListener({
            try {
                st.updateTexImage()
            } catch (_: Exception) {
            }
        }, Handler(ht.looper))
        previewSurface = st
        return st
    }

    private fun attachSurface() {
        val st = textureView.surfaceTexture ?: ensurePreviewSurface()
        try {
            cameraHelper?.addSurface(st, false)
        } catch (err: Exception) {
            Log.e(TAG, "surface", err)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (previewReady) attachSurface()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        try {
            cameraHelper?.removeSurface(surface)
        } catch (_: Exception) {
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun onFrame(frame: ByteBuffer) {
        val n = frame.remaining()
        if (n < 100 || !previewReady) return
        frameLog++
        if (encodeBusy.getAndSet(true)) {
            frame.position(frame.limit())
            return
        }
        val data = ByteArray(n)
        frame.get(data)
        frameExecutor.execute {
            try {
                processNv21(data, n)
            } finally {
                encodeBusy.set(false)
            }
        }
    }

    private fun processNv21(data: ByteArray, n: Int) {
        var w = previewW
        var h = previewH
        val needed = w * h * 3 / 2
        val nv21 = when {
            n >= needed -> data
            else -> {
                val pixels = n * 2 / 3
                when (pixels) {
                    640 * 480 -> {
                        w = 640
                        h = 480
                    }
                    1280 * 720 -> {
                        w = 1280
                        h = 720
                    }
                    320 * 240 -> {
                        w = 320
                        h = 240
                    }
                    else -> return
                }
                data
            }
        }
        val size = w * h * 3 / 2
        if (nv21.size < size) return
        val plane = if (nv21.size == size) nv21 else nv21.copyOf(size)
        val rawOut = ByteArrayOutputStream()
        val yuv = YuvImage(plane, ImageFormat.NV21, w, h, null)
        val inset = maxOf(2, h / 40)
        if (!yuv.compressToJpeg(Rect(0, 0, w, h - inset), 82, rawOut)) return
        var bmp = BitmapFactory.decodeByteArray(rawOut.toByteArray(), 0, rawOut.size()) ?: return
        if (maxOf(bmp.width, bmp.height) > 720) {
            val scale = 720f / maxOf(bmp.width, bmp.height)
            val nw = maxOf(1, (bmp.width * scale).toInt())
            val nh = maxOf(1, (bmp.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mirror, true)
        if (flipped !== bmp) bmp.recycle()
        mainHandler.post { onFrameBitmap(flipped) }
    }

    companion object {
        private const val TAG = "FiltroFantaUvc"
        private const val OFFSCREEN_TEX_ID = 42
    }
}
