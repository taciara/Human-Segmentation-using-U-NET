package online.seuprojeto.filtrofanta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.TextureView
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private val latestJpeg = AtomicReference("")
    private val skip = AtomicInteger(0)
    private var cameraHelper: ICameraHelper? = null
    private var usbStarted = false
    private var previewReady = false
    private var previewW = 640
    private var previewH = 480
    private var pageReady = false
    private var splashHidden = false
    private var previewSurface: SurfaceTexture? = null
    private var previewGlThread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameLog = AtomicInteger(0)
    private val textureGrab = AtomicInteger(0)
    @Volatile private var snapInFlight = false
    @Volatile private var bridgeFromSnap = false
    private lateinit var web: WebView
    private lateinit var splash: View
    private lateinit var splashStatus: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        splash = findViewById(R.id.splash)
        splashStatus = findViewById(R.id.splashStatus)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                hideSplashOnly()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "page $url")
                onSiteReady()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "web error ${error?.errorCode} ${error?.description}")
                    splashStatus.text = "Sem internet. Verifique Wi‑Fi e abra de novo."
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) hideSplashOnly()
                if (newProgress >= 50) onSiteReady()
            }
        }
        web.addJavascriptInterface(BoothBridge(), "AndroidBooth")
        findViewById<TextureView>(R.id.uvc).surfaceTextureListener = this
        web.loadUrl("$BOOTH_URL?v=apk7")
        mainHandler.postDelayed({ hideSplashOnly() }, 3_500)
        mainHandler.postDelayed(fallbackSiteReady, 15_000)
        handleUsbIntent(intent)
    }

    private fun hideSplashOnly() {
        if (splashHidden) return
        splashHidden = true
        splash.visibility = View.GONE
    }

    private fun onSiteReady() {
        if (pageReady) return
        pageReady = true
        mainHandler.removeCallbacks(fallbackSiteReady)
        hideSplashOnly()
        Log.i(TAG, "site ready")
        web.evaluateJavascript(
            "try{if(window.startUsbBridge){window.startUsbBridge();}'ok'}catch(e){String(e)}",
            { v -> Log.i(TAG, "boot $v") }
        )
        mainHandler.postDelayed({ ensureCameraPermission() }, 2_000)
    }

    private val fallbackSiteReady = Runnable {
        if (!pageReady) {
            Log.w(TAG, "site fallback — boot without full load")
            onSiteReady()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!pageReady) return
        mainHandler.postDelayed(usbResumeRetry, 700)
    }

    private val usbResumeRetry = Runnable {
        if (latestJpeg.get().isNullOrEmpty()) {
            retryUsbSelection("onResume")
        }
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.i(TAG, "USB intent attached")
            restartUsb("usb-intent")
        }
    }

    private fun notifyUsbHint(message: String) {
        if (!pageReady) return
        val safe = message.replace("\\", "\\\\").replace("'", "\\'")
        web.evaluateJavascript(
            "try{var el=document.getElementById('usbWait');if(el)el.textContent='$safe';}catch(e){}",
            null
        )
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
            } catch (_: Exception) {}
        }, Handler(ht.looper))
        previewSurface = st
        return st
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startUsb()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startUsb()
    }

    private fun restartUsb(reason: String) {
        Log.i(TAG, "restartUsb $reason")
        usbStarted = false
        previewReady = false
        skip.set(0)
        frameLog.set(0)
        try { cameraHelper?.release() } catch (_: Exception) {}
        cameraHelper = null
        startUsb()
    }

    private fun retryUsbSelection(reason: String) {
        val helper = cameraHelper
        if (helper == null) {
            startUsb()
            return
        }
        val list = helper.deviceList
        Log.i(TAG, "retryUsb $reason devices=${list?.size ?: 0}")
        if (!list.isNullOrEmpty()) {
            notifyUsbHint("Toque OK e escolha Sempre no Filtro Fanta…")
            helper.selectDevice(list[0])
        }
    }

    private fun startUsb() {
        if (usbStarted && cameraHelper != null) return
        usbStarted = true
        val helper = CameraHelper()
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                Log.i(TAG, "USB attach ${device.deviceName}")
                notifyUsbHint("Permita o acesso USB à EMEET (OK → Sempre)…")
                helper.selectDevice(device)
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                scheduleOpenCamera(helper, 0)
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (previewReady) return
                val sizes = helper.supportedSizeList
                val pick = pickPreviewSize(sizes)
                if (pick != null) {
                    try {
                        helper.setPreviewSize(pick)
                        previewW = pick.width
                        previewH = pick.height
                        Log.i(TAG, "setPreviewSize ${pick.width}x${pick.height}")
                    } catch (err: Exception) {
                        Log.e(TAG, "setPreviewSize", err)
                    }
                }
                val sz = helper.previewSize
                if (sz != null) {
                    previewW = sz.width
                    previewH = sz.height
                }
                previewReady = true
                Log.i(TAG, "camera open ${previewW}x${previewH}")
                notifyUsbHint("Câmera conectada, aplicando filtro…")
                attachSurface()
                helper.setFrameCallback(IFrameCallback { buf -> onFrame(buf) }, UVCCamera.PIXEL_FORMAT_NV21)
                try {
                    val cap = helper.imageCaptureConfig
                    cap.setCaptureMode(IImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    cap.setJpegCompressionQuality(62)
                    helper.setImageCaptureConfig(cap)
                } catch (err: Exception) {
                    Log.e(TAG, "imageCaptureConfig", err)
                }
                helper.startPreview()
                if (pageReady) {
                    mainHandler.removeCallbacks(snapshotLoop)
                    mainHandler.postDelayed(snapshotLoop, 800)
                } else {
                    mainHandler.postDelayed({
                        mainHandler.removeCallbacks(snapshotLoop)
                        mainHandler.postDelayed(snapshotLoop, 800)
                    }, 3_000)
                }
            }

            override fun onCameraClose(device: UsbDevice) {
                previewReady = false
            }
            override fun onDeviceClose(device: UsbDevice) {
                previewReady = false
            }
            override fun onDetach(device: UsbDevice) {
                previewReady = false
                latestJpeg.set("")
                notifyUsbHint("EMEET desconectada. Plugue de novo.")
            }
            override fun onCancel(device: UsbDevice) {
                Log.w(TAG, "USB cancel ${device.deviceName}")
                usbStarted = false
                previewReady = false
                notifyUsbHint("Toque OK e escolha Sempre no Filtro Fanta 3")
                mainHandler.postDelayed({ helper.selectDevice(device) }, 1200)
            }
        })
        cameraHelper = helper
        val list = helper.deviceList
        Log.i(TAG, "USB devices ${list?.size ?: 0}")
        if (!list.isNullOrEmpty()) helper.selectDevice(list[0])
    }

    private fun scheduleOpenCamera(helper: ICameraHelper, attempt: Int) {
        val delayMs = if (attempt == 0) 250L else 450L
        mainHandler.postDelayed({
            val sizes = helper.supportedSizeList
            val formats = helper.supportedFormatList?.size ?: 0
            Log.i(TAG, "open attempt=$attempt sizes=${sizes?.size ?: 0} formats=$formats")
            sizes?.take(6)?.forEach { s ->
                Log.i(TAG, "  size ${s.width}x${s.height} type=${s.type} fps=${s.fps}")
            }
            val pick = pickPreviewSize(sizes)
            when {
                pick != null -> {
                    Log.i(TAG, "openCamera ${pick.width}x${pick.height}")
                    helper.openCamera(pick)
                }
                attempt < 5 -> scheduleOpenCamera(helper, attempt + 1)
                else -> {
                    Log.w(TAG, "openCamera default")
                    helper.openCamera()
                }
            }
        }, delayMs)
    }

    private val snapshotLoop = object : Runnable {
        override fun run() {
            if (previewReady) grabStillPicture()
            mainHandler.postDelayed(this, 200)
        }
    }

    private fun grabStillPicture() {
        if (snapInFlight) return
        val helper = cameraHelper ?: return
        snapInFlight = true
        val f = File(cacheDir, "booth_snap.jpg")
        try {
            if (f.exists()) f.delete()
            val opts = IImageCapture.OutputFileOptions.Builder(f).build()
            helper.takePicture(opts, object : IImageCapture.OnImageCaptureCallback {
                override fun onImageSaved(result: IImageCapture.OutputFileResults) {
                    snapInFlight = false
                    try {
                        if (f.exists() && f.length() > 400) {
                            val n = f.length()
                            latestJpeg.set(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                            val c = frameLog.incrementAndGet()
                            if (c == 1 || c % 25 == 0) Log.i(TAG, "snap ok $n")
                            if (!bridgeFromSnap && pageReady) {
                                bridgeFromSnap = true
                                web.evaluateJavascript(
                                    "try{if(window.startUsbBridge){window.startUsbBridge();}}catch(e){}",
                                    null
                                )
                            }
                        }
                    } catch (err: Exception) {
                        Log.e(TAG, "snap read", err)
                    } finally {
                        f.delete()
                    }
                }

                override fun onError(code: Int, msg: String, err: Throwable?) {
                    snapInFlight = false
                    f.delete()
                    val n = frameLog.incrementAndGet()
                    if (n <= 8) Log.e(TAG, "snap err $code $msg", err)
                }
            })
        } catch (err: Exception) {
            snapInFlight = false
            Log.e(TAG, "snap", err)
        }
    }

    private fun pickPreviewSize(sizes: List<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        val prefer = listOf(
            640 to 480,
            1280 to 720,
            800 to 600,
            960 to 540
        )
        for ((w, h) in prefer) {
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    private fun attachSurface() {
        val tv = findViewById<TextureView>(R.id.uvc)
        val st = tv.surfaceTexture ?: ensurePreviewSurface()
        try {
            cameraHelper?.addSurface(st, false)
            Log.i(TAG, "surface attached")
        } catch (err: Exception) {
            Log.e(TAG, "surface", err)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (previewReady) attachSurface()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        try { cameraHelper?.removeSurface(surface) } catch (_: Exception) {}
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!previewReady) return
        if (textureGrab.incrementAndGet() % 12 != 0) return
        val tv = findViewById<TextureView>(R.id.uvc)
        mainHandler.post {
            try {
                val bmp: Bitmap = tv.bitmap ?: return@post
                val out = ByteArrayOutputStream()
                if (bmp.compress(Bitmap.CompressFormat.JPEG, 58, out) && out.size() > 400) {
                    latestJpeg.set(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
            } catch (err: Exception) {
                Log.e(TAG, "tex", err)
            }
        }
    }

    private fun onFrame(frame: ByteBuffer) {
        val n = frame.remaining()
        if (n < 100) return
        val logged = frameLog.incrementAndGet()
        if (logged == 1 || logged % 120 == 0) Log.i(TAG, "frame bytes=$n")
        val stride = if (previewW > 1280) 12 else 3
        if (skip.incrementAndGet() % stride != 0) {
            frame.position(frame.limit())
            return
        }
        val data = ByteArray(n)
        frame.get(data)
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
            latestJpeg.set(Base64.encodeToString(data, Base64.NO_WRAP))
            return
        }
        val pixels = n * 2 / 3
        var w = previewW
        var h = previewH
        if (w * h != pixels) {
            when (pixels) {
                640 * 480 -> { w = 640; h = 480 }
                1280 * 720 -> { w = 1280; h = 720 }
                else -> return
            }
        }
        try {
            val out = ByteArrayOutputStream()
            if (w > 1280 || h > 720) {
                val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
                if (!yuv.compressToJpeg(Rect(0, 0, w, h), 35, out)) return
            } else {
                val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
                val q = if (w <= 640) 55 else 45
                if (!yuv.compressToJpeg(Rect(0, 0, w, h), q, out)) return
            }
            if (out.size() > 400) {
                latestJpeg.set(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        } catch (err: Exception) {
            Log.e(TAG, "jpeg", err)
        }
    }

    inner class BoothBridge {
        @JavascriptInterface
        fun latestJpeg(): String = latestJpeg.get() ?: ""

        @JavascriptInterface
        fun isApk(): Boolean = true
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(snapshotLoop)
        mainHandler.removeCallbacksAndMessages(null)
        try { cameraHelper?.release() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        try { previewGlThread?.quitSafely() } catch (_: Exception) {}
        previewGlThread = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "FiltroFanta"
        const val REQ_CAMERA = 32
        const val OFFSCREEN_TEX_ID = 42
        const val BOOTH_URL = "https://fanta-filtro.seuprojeto.online/"
    }
}
