package online.seuprojeto.filtrofanta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.serenegiant.usb.UVCParam
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private val latestJpeg = AtomicReference("")
    // Incrementado a cada novo frame real gravado em latestJpeg. A câmera EMEET
    // nesse hardware só entrega um frame novo a cada poucos segundos (medido: até
    // 27s de intervalo), mas o JS fazia poll a cada 50-150ms — reenviando e
    // reprocessando (RVM) o MESMO frame dezenas de vezes por nada. O JS compara
    // esse id com o último que viu e só faz POST /frame quando ele muda.
    private val frameId = AtomicInteger(0)
    private val skip = AtomicInteger(0)
    private val encodeBusy = AtomicBoolean(false)
    private val frameExecutor = Executors.newSingleThreadExecutor()
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

            // O processo sandboxed do Chromium (renderer do WebView) pode morrer
            // por OOM ou crash — vimos isso travar a tela em preto permanentemente
            // durante testes, com o ActivityManager recusando relançar o processo
            // ("process is bad") mesmo reiniciando a Activity manualmente. Sem
            // tratar esse callback, o WebView some e nada mais é desenhado.
            // Recriar a Activity inteira é a recuperação mais confiável: reabre
            // splash, WebView e a câmera USB do zero.
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "WebView renderer gone (crashed=${detail?.didCrash()}) — recreating activity")
                recreate()
                return true
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
        web.loadUrl("$BOOTH_URL?v=apk8")
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
                // NÃO chamar setPreviewSize() aqui: a câmera já abriu com uma resolução
                // fixa (via openCamera(Size) ou openCamera(UVCParam)). Tentar reconfigurar
                // depois de aberta não tem efeito real no stream ativo e pode deixar o
                // estado interno inconsistente (causa observada: frames pretos).
                val sz = helper.previewSize
                if (sz != null) {
                    previewW = sz.width
                    previewH = sz.height
                }
                previewReady = true
                Log.i(TAG, "camera open ${previewW}x${previewH}")
                notifyUsbHint("Câmera conectada, aguardando sensor…")
                attachSurface()
                // PIXEL_FORMAT_RAW foi testado esperando pegar o MJPEG bruto sem
                // a lib decodificar, mas na prática ela sempre decodifica
                // internamente antes de entregar (RAW = YUYV 2 bytes/pixel, não
                // JPEG) — não há como evitar essa conversão via essa API.
                // Pacotes isochronous corrompidos durante a conversão MJPEG->NV21
                // ocasionalmente produzem um padrão de "listras" na parte da
                // imagem capturada depois do ponto de corrupção. Sem forma de
                // evitar isso nessa lib, ficamos com NV21 (que funciona a maior
                // parte do tempo) — ver detecção de tamanho abaixo para pelo
                // menos descartar frames truncados.
                helper.setFrameCallback(IFrameCallback { buf -> onFrame(buf) }, UVCCamera.PIXEL_FORMAT_NV21)
                try {
                    val cap = helper.imageCaptureConfig
                    cap.setCaptureMode(IImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    cap.setJpegCompressionQuality(70)
                    helper.setImageCaptureConfig(cap)
                } catch (err: Exception) {
                    Log.e(TAG, "imageCaptureConfig", err)
                }
                helper.startPreview()
                // takePicture()/IImageCapture usa o endpoint UVC "still capture"
                // separado, que continua devolvendo frames vazios mesmo com o fix de
                // isochronous packets (UVCAndroid 1.0.13). O endpoint de STREAMING
                // (IFrameCallback/onFrame) já entrega dados reais agora, então usamos
                // só ele: snapshotLoop/grabStillPicture ficam desativados.
                Log.i(TAG, "USB_STILL_CAPTURE_DISABLED: relying on onFrame() stream only")
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
            sizes?.take(10)?.forEach { s ->
                Log.i(TAG, "  size ${s.width}x${s.height} type=${s.type} fps=${s.fps}")
            }
            val realPick = pickPreviewSize(sizes)
            when {
                realPick != null -> {
                    Log.i(TAG, "openCamera(param) ${realPick.width}x${realPick.height} type=${realPick.type} +FIX_BANDWIDTH")
                    openWithQuirk(helper, realPick)
                }
                attempt < 5 -> scheduleOpenCamera(helper, attempt + 1)
                else -> {
                    // supportedSizeList nunca populou — forçar MJPEG manualmente.
                    // dmesg mostra erro real do driver: "gpd_free_count:7,
                    // number_of_packets:8 / mtk_kick_CmdQ:607 Error Here" — o pool de
                    // descritores isochronous (GPD) do controller MUSB (MediaTek MT6768)
                    // é menor que o número de pacotes que a transferência está pedindo,
                    // então a transferência falha silenciosamente (frame sempre vazio).
                    // Resolução/fps bem baixos reduzem o número de pacotes por transfer.
                    Log.w(TAG, "openCamera FORCED MJPEG 320x240@15fps +FIX_BANDWIDTH (sizes empty)")
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
            Log.e(TAG, "openCamera(UVCParam) failed, trying openCamera(Size)", err)
            try {
                helper.openCamera(size)
            } catch (err2: Exception) {
                Log.e(TAG, "openCamera(Size) also failed, trying default", err2)
                helper.openCamera()
            }
        }
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
                            val bytes = f.readBytes()
                            latestJpeg.set(Base64.encodeToString(bytes, Base64.NO_WRAP))
                            val c = frameLog.incrementAndGet()
                            if (c == 1 || c % 25 == 0) Log.i(TAG, "snap ok $n")
                            if (DEBUG_SAVE_FRAMES && (c == 1 || c % 50 == 0)) {
                                try {
                                    val dbg = File(getExternalFilesDir(null), "debug_frame_$c.jpg")
                                    dbg.writeBytes(bytes)
                                    Log.i(TAG, "debug frame saved: ${dbg.absolutePath} size=$n")
                                } catch (dbgErr: Exception) {
                                    Log.e(TAG, "debug save", dbgErr)
                                }
                            }
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
        // Preferir MJPEG: câmeras 4K como a EMEET S600L geralmente só entregam
        // YUYV cru em resoluções muito baixas/poucos fps por causa da banda USB;
        // abrir em YUYV pode resultar em frames vazios/pretos. MJPEG é confiável.
        val mjpeg = sizes.filter { it.type == UVCCamera.FRAME_FORMAT_MJPEG }
        val pool = if (mjpeg.isNotEmpty()) mjpeg else sizes
        val prefer = listOf(
            320 to 240,
            640 to 480,
            1280 to 720,
            800 to 600,
            960 to 540
        )
        for ((w, h) in prefer) {
            pool.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return pool.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: pool.minByOrNull { it.width * it.height }
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
        // A callback nativa da câmera (Thread-3) chama onFrame() muitas vezes por
        // segundo. Se processarmos (subsample+JPEG+Base64) na própria callback,
        // qualquer lentidão bloqueia a entrega dos PRÓXIMOS frames pela lib nativa
        // (foi isso que travou o preview: um frame de 8MP demorando >15s dentro da
        // callback). Copiamos o buffer rápido e devolvemos o controle imediatamente;
        // o trabalho pesado roda numa thread de background dedicada, descartando o
        // frame se a anterior ainda não terminou (não enfileira trabalho atrasado).
        if (encodeBusy.getAndSet(true)) {
            frame.position(frame.limit())
            return
        }
        val data = ByteArray(n)
        frame.get(data)
        frameExecutor.execute {
            try {
                processFrame(data, n, logged)
            } finally {
                encodeBusy.set(false)
            }
        }
    }

    private fun processFrame(data: ByteArray, n: Int, logged: Int) {
        // Formato NV21: Y plane (w*h bytes) + VU plane (w*h/2 bytes). Um frame
        // truncado/com pacotes isochronous perdidos no meio do transporte USB às
        // vezes chega com tamanho diferente do exato esperado — descartar esses
        // evita pelo menos os casos mais grosseiros de corrupção (o resto, onde o
        // tamanho bate mas o CONTEÚDO tem um trecho corrompido, não dá pra
        // detectar de forma barata aqui; é a causa do artefato de "listras"
        // ocasional que ainda pode aparecer).
        val pixels = n * 2 / 3
        var w = previewW
        var h = previewH
        if (w * h != pixels) {
            when (pixels) {
                640 * 480 -> { w = 640; h = 480 }
                1280 * 720 -> { w = 1280; h = 720 }
                else -> {
                    if (logged <= 20) Log.w(TAG, "frame size mismatch (n=$n, expected ${w}x$h), dropping")
                    return
                }
            }
        }
        try {
            val t0 = System.currentTimeMillis()
            val rawOut = ByteArrayOutputStream()
            val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
            if (!yuv.compressToJpeg(Rect(0, 0, w, h), 55, rawOut)) return
            val rawBytes = rawOut.toByteArray()

            var finalBytes = rawBytes
            if (w > 800 || h > 800) {
                val targetLong = 640
                var sampleSize = 1
                while ((maxOf(w, h) / sampleSize) > targetLong * 2) sampleSize *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                if (bmp != null) {
                    val scale = targetLong.toFloat() / maxOf(bmp.width, bmp.height)
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else bmp
                    val finalOut = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 60, finalOut)
                    finalBytes = finalOut.toByteArray()
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }
            }
            val tookMs = System.currentTimeMillis() - t0
            if (logged <= 10 || logged % 60 == 0) Log.i(TAG, "encode ${w}x${h}->${finalBytes.size}b took ${tookMs}ms")
            run {
                val jpegBytes = finalBytes
                latestJpeg.set(Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                frameId.incrementAndGet()
                if (DEBUG_SAVE_FRAMES && (logged == 1 || logged % 200 == 0)) {
                    try {
                        val dbg = File(getExternalFilesDir(null), "onframe_debug_$logged.jpg")
                        dbg.writeBytes(jpegBytes)
                        Log.i(TAG, "onFrame debug saved: ${dbg.absolutePath} size=${jpegBytes.size}")
                    } catch (dbgErr: Exception) {
                        Log.e(TAG, "onFrame debug save", dbgErr)
                    }
                }
                if (!bridgeFromSnap && pageReady) {
                    bridgeFromSnap = true
                    // processFrame() roda numa thread de background, não na main
                    // thread — WebView exige que evaluateJavascript() seja chamado
                    // na main thread.
                    mainHandler.post {
                        web.evaluateJavascript(
                            "try{if(window.startUsbBridge){window.startUsbBridge();}}catch(e){}",
                            null
                        )
                    }
                }
            }
        } catch (err: Exception) {
            Log.e(TAG, "jpeg", err)
        }
    }

    inner class BoothBridge {
        @JavascriptInterface
        fun latestJpeg(): String = latestJpeg.get() ?: ""

        @JavascriptInterface
        fun latestFrameId(): Int = frameId.get()

        @JavascriptInterface
        fun isApk(): Boolean = true
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(snapshotLoop)
        mainHandler.removeCallbacksAndMessages(null)
        try { frameExecutor.shutdownNow() } catch (_: Exception) {}
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
        const val DEBUG_SAVE_FRAMES = true
    }
}
