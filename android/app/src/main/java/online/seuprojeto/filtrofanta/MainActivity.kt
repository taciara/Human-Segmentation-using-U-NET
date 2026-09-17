package online.seuprojeto.filtrofanta

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var screenCapture: LinearLayout
    private lateinit var screenResult: LinearLayout
    private lateinit var screenReady: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var shotImg: ImageView
    private lateinit var qrImg: ImageView
    private lateinit var qrWait: View
    private lateinit var loader: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var countdown: FrameLayout
    private lateinit var countdownNum: TextView
    private lateinit var splash: FrameLayout
    private lateinit var splashStatus: TextView
    private lateinit var uvcTexture: TextureView
    private lateinit var btnSnap: Button
    private lateinit var btnShare: Button
    private lateinit var btnAgain: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)

    private lateinit var assets: BoothAssets
    private lateinit var segmenter: SegmentationEngine
    private lateinit var compositor: BoothCompositor
    private var uvc: UvcCameraController? = null

    private var lastPreview: Bitmap? = null
    private var lastSavedUri: Uri? = null
    private var lastPolaroid: Bitmap? = null
    private val shareExecutor = Executors.newSingleThreadExecutor()
    private var lastQr: Bitmap? = null
    private var lastShareUrl: String? = null
    private var shareJob = 0
    private var hasLiveFeed = false
    private var holdLive = false
    private var frozenShot: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            screenCapture.setPadding(0, bars.top / 3, 0, bars.bottom + dp(16))
            screenResult.setPadding(screenResult.paddingLeft, bars.top / 3, screenResult.paddingRight, bars.bottom + dp(16))
            screenReady.setPadding(screenReady.paddingLeft, bars.top / 3, screenReady.paddingRight, bars.bottom + dp(16))
            insets
        }
        title = "Filtro Fanta ${BuildConfig.VERSION_NAME}"

        btnSnap.setOnClickListener { startCountdownAndCapture() }
        btnShare.setOnClickListener { showReadyScreen() }
        btnAgain.setOnClickListener { resetToCapture() }

        processExecutor.execute {
            try {
                assets = BoothAssets(this)
                segmenter = SegmentationEngine(this)
                compositor = BoothCompositor(assets)
                mainHandler.post {
                    hideSplash("Conecte a EMEET no USB-C")
                    ensureCameraPermission()
                }
            } catch (err: Exception) {
                Log.e(TAG, "init", err)
                mainHandler.post {
                    splashStatus.text = "Erro ao carregar IA: ${err.message}"
                    statusText.text = splashStatus.text.toString()
                    ensureCameraPermission()
                }
            }
        }

        handleUsbIntent(intent)
        mainHandler.postDelayed({ hideSplashOnly() }, 4_000)
    }

    private fun bindViews() {
        screenCapture = findViewById(R.id.screenCapture)
        screenResult = findViewById(R.id.screenResult)
        screenReady = findViewById(R.id.screenReady)
        preview = findViewById(R.id.preview)
        shotImg = findViewById(R.id.shotImg)
        qrImg = findViewById(R.id.qrImg)
        qrWait = findViewById(R.id.qrWait)
        loader = findViewById(R.id.loader)
        statusText = findViewById(R.id.statusText)
        countdown = findViewById(R.id.countdown)
        countdownNum = findViewById(R.id.countdownNum)
        splash = findViewById(R.id.splash)
        splashStatus = findViewById(R.id.splashStatus)
        uvcTexture = findViewById(R.id.uvc)
        btnSnap = findViewById(R.id.btnSnap)
        btnShare = findViewById(R.id.btnShare)
        btnAgain = findViewById(R.id.btnAgain)
    }

    private fun showScreen(capture: Boolean, result: Boolean, ready: Boolean) {
        screenCapture.visibility = if (capture) View.VISIBLE else View.GONE
        screenResult.visibility = if (result) View.VISIBLE else View.GONE
        screenReady.visibility = if (ready) View.VISIBLE else View.GONE
        holdLive = !capture
    }

    private fun resetToCapture() {
        shareJob += 1
        lastSavedUri = null
        lastShareUrl = null
        lastPolaroid?.recycle()
        lastPolaroid = null
        lastQr?.recycle()
        lastQr = null
        qrImg.setImageDrawable(null)
        qrWait.visibility = View.VISIBLE
        btnSnap.isEnabled = true
        btnShare.isEnabled = false
        showScreen(capture = true, result = false, ready = false)
    }

    private fun hideSplashOnly() {
        splash.visibility = View.GONE
    }

    private fun hideSplash(msg: String) {
        statusText.text = msg
        splashStatus.text = msg
        splash.visibility = View.GONE
    }

    private fun markFeedReady() {
        if (hasLiveFeed) return
        hasLiveFeed = true
        loader.visibility = View.GONE
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startUvc()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startUvc()
        } else {
            statusText.text = "Permissão de câmera negada (necessária para USB UVC)."
        }
    }

    private fun startUvc() {
        if (uvc != null) return
        uvc = UvcCameraController(
            this,
            uvcTexture,
            onStatus = { msg -> mainHandler.post { statusText.text = msg } },
            onFrameBitmap = { bmp -> enqueueFrame(bmp) },
        ).also {
            uvcTexture.surfaceTextureListener = it
            it.start()
        }
    }

    private fun enqueueFrame(frame: Bitmap) {
        if (holdLive) {
            frame.recycle()
            return
        }
        if (processing.getAndSet(true)) {
            frame.recycle()
            return
        }
        processExecutor.execute {
            try {
                if (!::segmenter.isInitialized) {
                    mainHandler.post {
                        preview.setImageBitmap(frame)
                        statusText.text = "Preview cru (IA indisponível)"
                        markFeedReady()
                    }
                    return@execute
                }
                val mask = segmenter.personMask(frame)
                val composed = compositor.compose(frame, mask.data, mask.width, mask.height)
                frame.recycle()
                mainHandler.post {
                    lastPreview?.recycle()
                    lastPreview = composed
                    preview.setImageBitmap(composed)
                    markFeedReady()
                }
            } catch (err: Exception) {
                Log.e(TAG, "process", err)
                frame.recycle()
                mainHandler.post {
                    statusText.text = "Erro no filtro: ${err.message}"
                }
            } finally {
                processing.set(false)
            }
        }
    }

    private fun startCountdownAndCapture() {
        if (!btnSnap.isEnabled) return
        btnSnap.isEnabled = false
        countdown.visibility = View.VISIBLE
        runCountdownStep(3) {
            countdown.visibility = View.GONE
            capturePhoto()
        }
    }

    private fun runCountdownStep(n: Int, onDone: () -> Unit) {
        if (n < 1) {
            onDone()
            return
        }
        countdownNum.text = n.toString()
        mainHandler.postDelayed({ runCountdownStep(n - 1, onDone) }, 900)
    }

    private fun capturePhoto() {
        val inner = lastPreview ?: run {
            Toast.makeText(this, "Aguardando primeiro frame da EMEET…", Toast.LENGTH_SHORT).show()
            btnSnap.isEnabled = true
            return
        }
        val card = PolaroidExporter.wrapShot(this, inner)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "foto_${stamp}_${System.currentTimeMillis() % 1000}.jpg"
        try {
            lastPolaroid?.recycle()
            lastPolaroid = card
            lastShareUrl = "${BuildConfig.SHARE_ORIGIN.trimEnd('/')}/p/$fileName"
            lastQr?.recycle()
            lastQr = QrEncoder.encode(lastShareUrl!!, 280)
            frozenShot?.recycle()
            frozenShot = inner.copy(Bitmap.Config.ARGB_8888, false)
            shotImg.setImageBitmap(frozenShot)
            qrImg.setImageBitmap(lastQr)
            qrWait.visibility = View.GONE
            btnShare.isEnabled = false
            showScreen(capture = false, result = true, ready = false)
            startShareUpload(card, fileName)
            mainHandler.postDelayed({ btnShare.isEnabled = true }, 150)
        } catch (err: Exception) {
            card.recycle()
            Toast.makeText(this, "Falha ao salvar: ${err.message}", Toast.LENGTH_LONG).show()
            btnSnap.isEnabled = true
            showScreen(capture = true, result = false, ready = false)
        }
    }

    private fun saveToGallery(card: Bitmap, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FiltroFanta")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    card.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val folder = java.io.File(dir, "FiltroFanta").apply { mkdirs() }
            val file = java.io.File(folder, fileName)
            file.outputStream().use { out ->
                card.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            Uri.fromFile(file)
        }
    }

    private fun startShareUpload(card: Bitmap, fileName: String) {
        val job = ++shareJob
        shareExecutor.execute {
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    val scaled = scaleForUpload(card)
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    if (scaled !== card) scaled.recycle()
                    out.toByteArray()
                }
                ShareApi.uploadPolaroid(bytes, fileName)
                val uri = saveToGallery(card, fileName)
                if (job == shareJob) {
                    mainHandler.post { lastSavedUri = uri }
                }
            } catch (err: Exception) {
                Log.e(TAG, "upload", err)
                mainHandler.post {
                    if (job != shareJob) return@post
                    if (screenReady.visibility == View.VISIBLE) {
                        Toast.makeText(this, "Falha ao enviar: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun scaleForUpload(src: Bitmap): Bitmap {
        val maxSide = 1100
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide / longest.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, (src.width * scale).toInt()),
            maxOf(1, (src.height * scale).toInt()),
            true,
        )
    }

    private fun showReadyScreen() {
        if (lastPolaroid == null) {
            Toast.makeText(this, "Tire uma foto antes de compartilhar.", Toast.LENGTH_SHORT).show()
            return
        }
        qrWait.visibility = if (lastQr == null) View.VISIBLE else View.GONE
        if (lastQr != null) qrImg.setImageBitmap(lastQr)
        showScreen(capture = false, result = false, ready = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            uvc?.onUsbIntent()
        }
    }

    override fun onDestroy() {
        processExecutor.shutdownNow()
        shareExecutor.shutdownNow()
        uvc?.release()
        if (::compositor.isInitialized) compositor.release()
        if (::segmenter.isInitialized) segmenter.close()
        lastPreview?.recycle()
        frozenShot?.recycle()
        lastPolaroid?.recycle()
        lastQr?.recycle()
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            mainHandler.postDelayed({ uvc?.onUsbIntent() }, 500)
        }
    }

    companion object {
        private const val TAG = "FiltroFanta"
        private const val REQ_CAMERA = 32
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
