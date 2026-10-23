package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class UvcCameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit,
    private val onFrameBitmap: (Bitmap) -> Unit,
    private val onNeedUsbPermission: (UsbDevice) -> Unit = {},
) : TextureView.SurfaceTextureListener {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val appLabel: String by lazy {
        context.applicationInfo.loadLabel(context.packageManager)?.toString() ?: "Cabine Fanta"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeBusy = AtomicBoolean(false)
    private val frameExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var cameraHelper: ICameraHelper? = null
    private var usbStarted = false
    private var previewReady = false
    private var previewW = 640
    private var previewH = 480
    private var captureW = 960  // Resolução de captura (foto) - maior que preview
    private var captureH = 720
    private val stillLock = Any()
    private var lastNv21: ByteArray? = null
    private var lastJpeg: ByteArray? = null
    private var stillW = 0
    private var stillH = 0
    private var previewSurface: SurfaceTexture? = null
    private var previewGlThread: HandlerThread? = null
    private var frameLog = 0
    private var selectedDeviceId: Int? = null
    private var selectInFlight = false
    private var permissionAttempts = 0
    private var pendingPermissionRetry: Runnable? = null

    fun setCaptureResolution(w: Int, h: Int) {
        captureW = w
        captureH = h
        Log.i(TAG, "Resolução de captura: ${captureW}x${captureH}")
    }

    fun start() {
        cameraHelper?.let { existing ->
            if (previewReady) return
            pickUvcDevice(existing.deviceList)?.let { trySelectDevice(existing, it, "start-resume") }
            return
        }
        if (usbStarted) return
        usbStarted = true
        onStatus("Procurando câmera USB (EMEET)…")
        val helper = CameraHelper()
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                logDevice("USB attach", device)
                if (previewReady) return
                trySelectDevice(helper, device, "attach")
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                scheduleOpenCamera(helper, 0)
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (previewReady) return
                cancelPermissionRetry()
                selectInFlight = false
                permissionAttempts = 0
                selectedDeviceId = device.deviceId
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
                selectInFlight = false
                cancelPermissionRetry()
                if (selectedDeviceId == device.deviceId) selectedDeviceId = null
                onStatus("EMEET desconectou. OTG com energia, reconecte e aguarde estabilizar.")
            }

            override fun onCancel(device: UsbDevice) {
                Log.w(TAG, "USB cancel ${device.deviceName} attempts=$permissionAttempts")
                selectInFlight = false
                previewReady = false
                permissionAttempts += 1
                if (permissionAttempts >= MAX_PERMISSION_ATTEMPTS) {
                    onStatus(
                        "Permissão USB bloqueada. Feche o app, reconecte a EMEET, " +
                            "abra de novo e toque OK + Sempre.",
                    )
                    return
                }
                onStatus("Toque OK e marque Sempre usar neste app ($appLabel)")
                schedulePermissionRetry(helper, device)
            }
        })
        cameraHelper = helper
        val list = helper.deviceList
        Log.i(TAG, "USB devices ${list?.size ?: 0}")
        list?.forEach { logDevice("enumerated", it) }
        val pick = pickUvcDevice(list)
        if (pick != null) {
            onStatus("EMEET/UVC encontrada — aguarde permissão USB")
            trySelectDevice(helper, pick, "enumerated")
        } else if (!list.isNullOrEmpty()) {
            onStatus("USB conectado, mas não é câmera UVC. Use a EMEET.")
        } else {
            onStatus("Conecte a EMEET no OTG (USB-C) e toque em Permitir")
        }
    }

    fun snapshotFull(): Bitmap? {
        val jpeg: ByteArray?
        val nv21: ByteArray?
        val w: Int
        val h: Int
        synchronized(stillLock) {
            jpeg = lastJpeg
            nv21 = lastNv21
            w = stillW
            h = stillH
        }
        var bmp = when {
            jpeg != null -> {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            }
            nv21 != null && w > 0 && h > 0 -> nv21ToBitmap(nv21, w, h, step = 1)
            else -> null
        } ?: return null
        val inset = maxOf(2, bmp.height / 40)
        val usableH = (bmp.height - inset).coerceAtLeast(1)
        if (usableH < bmp.height) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, usableH)
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mirror, true)
        if (flipped !== bmp) bmp.recycle()
        return flipped
    }

    fun onUsbIntent() {
        if (previewReady) return
        val helper = cameraHelper
        if (helper == null) {
            start()
            return
        }
        pickUvcDevice(helper.deviceList)?.let { trySelectDevice(helper, it, "usb-intent") }
    }

    /** Chamado depois que MainActivity recebe UsbManager.EXTRA_PERMISSION_GRANTED. */
    fun onUsbPermissionGranted(device: UsbDevice) {
        val helper = cameraHelper ?: return
        permissionAttempts = 0
        trySelectDevice(helper, device, "usb-granted")
    }

    fun onUsbPermissionDenied() {
        selectInFlight = false
    }

    /** Após OK no popup USB (receiver pode perder o broadcast se a Activity pausou). */
    fun resumeAfterUsbPermission() {
        if (previewReady) return
        val helper = cameraHelper ?: return
        val pick = pickUvcDevice(helper.deviceList) ?: return
        if (!usbManager.hasPermission(pick)) return
        trySelectDevice(helper, pick, "resume-permission")
    }

    fun release() {
        cancelPermissionRetry()
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
        usbStarted = false
        previewReady = false
        selectInFlight = false
        selectedDeviceId = null
        permissionAttempts = 0
    }

    private fun trySelectDevice(helper: ICameraHelper, device: UsbDevice, reason: String) {
        if (!isUvcCamera(device)) {
            Log.i(TAG, "trySelect skip non-UVC reason=$reason")
            return
        }
        if (previewReady && selectedDeviceId == device.deviceId) return
        if (selectInFlight && selectedDeviceId == device.deviceId && !usbManager.hasPermission(device)) {
            Log.i(TAG, "trySelect aguardando permissão USB reason=$reason id=${device.deviceId}")
            return
        }
        if (permissionAttempts >= MAX_PERMISSION_ATTEMPTS) return
        selectedDeviceId = device.deviceId
        selectInFlight = true
        logDevice("trySelect reason=$reason", device)
        if (!usbManager.hasPermission(device)) {
            onStatus("Confirme acesso USB à EMEET (marque Sempre)")
            onNeedUsbPermission(device)
            return
        }
        onStatus("Abrindo EMEET…")
        try {
            helper.selectDevice(device)
        } catch (err: Exception) {
            selectInFlight = false
            Log.e(TAG, "selectDevice failed", err)
        }
    }

    private fun schedulePermissionRetry(helper: ICameraHelper, device: UsbDevice) {
        cancelPermissionRetry()
        val delayMs = 4000L + permissionAttempts * 2000L
        val task = Runnable {
            pendingPermissionRetry = null
            if (previewReady) return@Runnable
            val stillConnected = helper.deviceList?.any { it.deviceId == device.deviceId } == true
            if (!stillConnected) {
                selectInFlight = false
                onStatus("EMEET sumiu do USB. Reconecte (hub OTG com energia ajuda).")
                return@Runnable
            }
            trySelectDevice(helper, device, "permission-retry")
        }
        pendingPermissionRetry = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelPermissionRetry() {
        pendingPermissionRetry?.let { mainHandler.removeCallbacks(it) }
        pendingPermissionRetry = null
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
        // Preferir resoluções menores para reduzir delay (prioridade: velocidade > qualidade)
        val prefer = listOf(320 to 240, 640 to 480, 800 to 600, 960 to 720, 1280 to 720)
        for ((w, h) in prefer) {
            pool.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return pool.filter { it.width <= 1280 && it.height <= 960 }
            .maxByOrNull { it.width * it.height }
            ?: pool.minByOrNull { it.width * it.height }
    }

    private fun ensurePreviewSurface(): SurfaceTexture {
        previewSurface?.let { return it }
        val st = SurfaceTexture(OFFSCREEN_TEX_ID)
        st.setDefaultBufferSize(1920, 1080)
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
        if (frameLog % 3 != 0) {
            frame.position(frame.limit())
            return
        }
        if (encodeBusy.getAndSet(true)) {
            frame.position(frame.limit())
            return
        }
        val data = ByteArray(n)
        frame.get(data)
        frameExecutor.execute {
            try {
                processCameraFrame(data, n)
            } finally {
                encodeBusy.set(false)
            }
        }
    }

    private fun processCameraFrame(data: ByteArray, n: Int) {
        val isJpeg = n >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
        var bmp: Bitmap?
        if (isJpeg) {
            synchronized(stillLock) {
                lastJpeg = data.copyOf(n)
                lastNv21 = null
            }
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inSampleSize = 5
            }
            bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, n, opts)
        } else {
            val sized = resolveNv21(data, n) ?: return
            synchronized(stillLock) {
                lastNv21 = sized.first
                lastJpeg = null
                stillW = sized.second
                stillH = sized.third
            }
            bmp = nv21ToBitmap(sized.first, sized.second, sized.third, step = 3)
        }
        bmp ?: return
        val inset = maxOf(1, bmp.height / 40)
        val usableH = (bmp.height - inset).coerceAtLeast(1)
        if (usableH < bmp.height) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, usableH)
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mirror, true)
        if (flipped !== bmp) bmp.recycle()
        mainHandler.post { onFrameBitmap(flipped) }
    }

    private fun resolveNv21(data: ByteArray, n: Int): Triple<ByteArray, Int, Int>? {
        var w = previewW
        var h = previewH
        val needed = w * h * 3 / 2
        val nv21 = when {
            n >= needed -> data
            else -> {
                val pixels = n * 2 / 3
                when (pixels) {
                    1920 * 1080 -> {
                        w = 1920
                        h = 1080
                    }
                    1280 * 720 -> {
                        w = 1280
                        h = 720
                    }
                    640 * 480 -> {
                        w = 640
                        h = 480
                    }
                    320 * 240 -> {
                        w = 320
                        h = 240
                    }
                    else -> return null
                }
                data
            }
        }
        val size = w * h * 3 / 2
        if (nv21.size < size) return null
        val plane = if (nv21.size == size) nv21.copyOf(size) else nv21.copyOf(size)
        return Triple(plane, w, h)
    }

    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, step: Int = 1): Bitmap {
        val s = maxOf(1, step)
        val outW = maxOf(1, width / s)
        val outH = maxOf(1, height / s)
        val frameSize = width * height
        val argb = IntArray(outW * outH)
        var op = 0
        for (j in 0 until outH) {
            val srcY = j * s
            val uvpRow = frameSize + (srcY shr 1) * width
            for (i in 0 until outW) {
                val srcX = i * s
                val y = nv21[srcY * width + srcX].toInt() and 0xff
                val uv = uvpRow + (srcX and 1.inv())
                val v = (nv21[uv].toInt() and 0xff) - 128
                val u = (nv21[uv + 1].toInt() and 0xff) - 128
                val y1192 = 1192 * maxOf(0, y - 16)
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                argb[op++] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun isUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        if (device.deviceClass == 239 && device.deviceSubclass == 2) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    private fun scoreUvcDevice(device: UsbDevice): Int {
        var score = 0
        val label = buildString {
            append(device.manufacturerName.orEmpty())
            append(' ')
            append(device.productName.orEmpty())
            append(' ')
            append(device.deviceName.orEmpty())
        }
        if (label.contains("emeet", ignoreCase = true)) score += 100
        if (label.contains("smartcam", ignoreCase = true)) score += 40
        if (label.contains("webcam", ignoreCase = true)) score += 20
        if (isUvcCamera(device)) score += 10
        return score
    }

    private fun pickUvcDevice(devices: List<UsbDevice>?): UsbDevice? {
        if (devices.isNullOrEmpty()) return null
        val uvc = devices.filter { isUvcCamera(it) }
        if (uvc.isEmpty()) return null
        return uvc.maxByOrNull { scoreUvcDevice(it) }
    }

    private fun logDevice(event: String, device: UsbDevice) {
        Log.i(
            TAG,
            "$event vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                "class=${device.deviceClass} name=${device.deviceName} " +
                "product=${device.productName} mfg=${device.manufacturerName}",
        )
    }

    companion object {
        private const val TAG = "FiltroFantaUvc"
        private const val OFFSCREEN_TEX_ID = 42
        private const val MAX_PERMISSION_ATTEMPTS = 3
    }
}
