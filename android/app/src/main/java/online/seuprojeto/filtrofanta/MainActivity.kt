package online.seuprojeto.filtrofanta

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var preview: ImageView
    private lateinit var statusText: TextView
    private lateinit var splash: FrameLayout
    private lateinit var splashStatus: TextView
    private lateinit var uvcTexture: TextureView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)

    private lateinit var assets: BoothAssets
    private lateinit var segmenter: SegmentationEngine
    private lateinit var compositor: BoothCompositor
    private var uvc: UvcCameraController? = null

    private var lastPreview: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        splash = findViewById(R.id.splash)
        splashStatus = findViewById(R.id.splashStatus)
        uvcTexture = findViewById(R.id.uvc)
        findViewById<Button>(R.id.btnCapture).setOnClickListener { capturePhoto() }

        title = "Filtro Fanta ${BuildConfig.VERSION_NAME}"

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

    private fun hideSplashOnly() {
        splash.visibility = View.GONE
    }

    private fun hideSplash(msg: String) {
        statusText.text = msg
        splashStatus.text = msg
        splash.visibility = View.GONE
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
        if (processing.getAndSet(true)) {
            frame.recycle()
            return
        }
        processExecutor.execute {
            try {
                if (!::segmenter.isInitialized) {
                    mainHandler.post {
                        preview.setImageBitmap(frame)
                        statusText.text = "Preview cru (IA indisponível) · ${BuildConfig.VERSION_NAME}"
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
                    statusText.text = "Ao vivo · ${BuildConfig.VERSION_NAME}"
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

    private fun capturePhoto() {
        val inner = lastPreview ?: run {
            Toast.makeText(this, "Aguardando primeiro frame da EMEET…", Toast.LENGTH_SHORT).show()
            return
        }
        val card = PolaroidExporter.wrapShot(this, inner)
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "fanta_$name.jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val folder = java.io.File(dir, "FiltroFanta").apply { mkdirs() }
                java.io.File(folder, fileName).outputStream().use { out ->
                    card.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
            card.recycle()
            Toast.makeText(this, "Salvo em Fotos/FiltroFanta/$fileName", Toast.LENGTH_LONG).show()
        } catch (err: Exception) {
            card.recycle()
            Toast.makeText(this, "Falha ao salvar: ${err.message}", Toast.LENGTH_LONG).show()
        }
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
        uvc?.release()
        if (::compositor.isInitialized) compositor.release()
        if (::segmenter.isInitialized) segmenter.close()
        lastPreview?.recycle()
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
}
