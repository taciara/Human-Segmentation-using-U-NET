package online.seuprojeto.filtrofanta

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var prepareOverlay: View
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
    private lateinit var btnRetake: ImageButton
    private lateinit var lookColor: TextView
    private lateinit var lookBw: TextView
    private lateinit var topLogoCapture: ImageView

    @Volatile
    private var personBw = true

    private var adminTapCount = 0
    private var adminTapResetAt = 0L

    private val imageConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        processExecutor.execute {
            if (::assets.isInitialized) assets.clearSceneCache()
            if (::compositor.isInitialized) compositor.invalidateScenes()
        }
        Toast.makeText(this, "Imagens atualizadas", Toast.LENGTH_SHORT).show()
    }

    private val cameraConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        restartCamera()
        Toast.makeText(this, "Câmera reiniciada", Toast.LENGTH_SHORT).show()
    }

    private val avPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val camOk = grants[Manifest.permission.CAMERA] == true
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true
        when {
            camOk && micOk -> {
                warmUpMicrophoneForUsb()
                startCamera()
            }
            !micOk -> {
                statusText.text =
                    "Permita Microfone nas configurações — a EMEET precisa disso para abrir no USB."
                Toast.makeText(
                    this,
                    "Sem permissão de gravação/microfone a EMEET não abre.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> statusText.text = "Permissão de câmera negada."
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)

    private lateinit var assets: BoothAssets
    private lateinit var segmenter: SegmentationEngine
    private lateinit var compositor: BoothCompositor
    private var uvc: UvcCameraController? = null
    private var deviceCam: DeviceCameraController? = null

    private var lastPreview: Bitmap? = null
    private var lastPolaroid: Bitmap? = null
    private val shareExecutor = Executors.newSingleThreadExecutor()
    private var lastQr: Bitmap? = null
    private var lastShareUrl: String? = null
    private var shareJob = 0
    private var enhanceJob = 0
    private var hasLiveFeed = false
    private var holdLive = false
    private var frozenShot: Bitmap? = null
    private var lastComposeAt = 0L
    private var lastUsbIntentAt = 0L
    private var previewMask: SegmentationEngine.PersonMask? = null
    private var previewMaskAt = 0L
    private var previewMaskPersonBw = true
    private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction()) return
            val device = readUsbDeviceExtra(intent) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                Log.i(TAG, "USB permission granted for ${device.deviceName}")
                uvc?.onUsbPermissionGranted(device)
            } else {
                uvc?.onUsbPermissionDenied()
                statusText.text = "USB negado. Reconecte a EMEET e marque Sempre."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        personBw = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PERSON_BW, true)
        setupLookToggle()
        applyLookUi()
        setupAdminEntry()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            screenCapture.setPadding(0, bars.top / 3, 0, bars.bottom + dp(16))
            screenResult.setPadding(screenResult.paddingLeft, bars.top / 3, screenResult.paddingRight, bars.bottom + dp(16))
            screenReady.setPadding(screenReady.paddingLeft, bars.top / 3, screenReady.paddingRight, bars.bottom + dp(16))
            insets
        }
        title = "${BuildConfig.APP_DISPLAY_NAME} ${BuildConfig.VERSION_NAME}"

        btnSnap.setOnClickListener { startCountdownAndCapture() }
        btnShare.setOnClickListener { showReadyScreen() }
        btnAgain.setOnClickListener { resetToCapture() }
        btnRetake.setOnClickListener { resetToCapture() }

        processExecutor.execute {
            try {
                val imageConfig = BoothImageConfig(this)
                assets = BoothAssets(this, imageConfig)
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

        if (!usbReceiverRegistered) {
            val filter = IntentFilter(usbPermissionAction())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(usbPermissionReceiver, filter)
            }
            usbReceiverRegistered = true
        }

        handleUsbIntent(intent)
        mainHandler.postDelayed({ hideSplashOnly() }, 4_000)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.postDelayed({ uvc?.resumeAfterUsbPermission() }, 300)
    }

    private fun bindViews() {
        screenCapture = findViewById(R.id.screenCapture)
        screenResult = findViewById(R.id.screenResult)
        screenReady = findViewById(R.id.screenReady)
        preview = findViewById(R.id.preview)
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        shotImg = findViewById(R.id.shotImg)
        prepareOverlay = findViewById(R.id.prepareOverlay)
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
        btnRetake = findViewById(R.id.btnRetake)
        lookColor = findViewById(R.id.lookColor)
        lookBw = findViewById(R.id.lookBw)
        topLogoCapture = findViewById(R.id.topLogoCapture)
    }

    private fun setupAdminEntry() {
        topLogoCapture.setOnClickListener {
            if (screenCapture.visibility != View.VISIBLE) return@setOnClickListener
            val now = System.currentTimeMillis()
            if (now - adminTapResetAt > 2500L) adminTapCount = 0
            adminTapResetAt = now
            adminTapCount += 1
            if (adminTapCount >= 3) {
                adminTapCount = 0
                promptAdminPin()
            }
        }
    }

    private fun promptAdminPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Senha"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Configuração")
            .setMessage("Digite a senha para acessar o menu.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                if (input.text.toString() == BoothImageConfig.ADMIN_PIN) {
                    showConfigMenu()
                } else {
                    Toast.makeText(this, "Senha incorreta", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showConfigMenu() {
        val options = arrayOf(
            "Alterar fundos e personagem",
            "Câmera (EMEET USB ou celular)",
            "Qualidade da foto",
        )
        AlertDialog.Builder(this)
            .setTitle("Menu de Configuração")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> imageConfigLauncher.launch(Intent(this, ImageConfigActivity::class.java))
                    1 -> cameraConfigLauncher.launch(Intent(this, CameraConfigActivity::class.java))
                    2 -> showQualityMenu()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showQualityMenu() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val currentQuality = prefs.getString("captureQuality", "high") ?: "high"

        val qualities = arrayOf("Padrão (960x720)", "Alta (1280x960)", "Máxima (1920x1440)")
        val qualityKeys = arrayOf("high", "higher", "max")
        val checkedItem = qualityKeys.indexOf(currentQuality)

        AlertDialog.Builder(this)
            .setTitle("Qualidade da Foto")
            .setSingleChoiceItems(qualities, checkedItem) { dialog, which ->
                val selected = qualityKeys[which]
                prefs.edit().putString("captureQuality", selected).apply()

                val (w, h) = when (selected) {
                    "high" -> 960 to 720
                    "higher" -> 1280 to 960
                    "max" -> 1920 to 1440
                    else -> 960 to 720
                }
                uvc?.setCaptureResolution(w, h)

                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupLookToggle() {
        lookColor.setOnClickListener { setPersonBw(false) }
        lookBw.setOnClickListener { setPersonBw(true) }
    }

    private fun setPersonBw(bw: Boolean) {
        if (personBw == bw) return
        personBw = bw
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_PERSON_BW, bw).apply()
        applyLookUi()
        if (!::compositor.isInitialized) return
        clearPreviewMaskCache()
        processExecutor.execute { compositor.invalidateScenes() }
    }

    private fun clearPreviewMaskCache() {
        previewMask = null
        previewMaskAt = 0L
    }

    private fun applyLookUi() {
        val on = R.drawable.look_toggle_chip_on
        val off = R.drawable.look_toggle_chip_off
        lookColor.setBackgroundResource(if (personBw) off else on)
        lookBw.setBackgroundResource(if (personBw) on else off)
        lookColor.setTextColor(
            ContextCompat.getColor(this, if (personBw) R.color.look_toggle_off else R.color.ink),
        )
        lookBw.setTextColor(
            ContextCompat.getColor(this, if (personBw) R.color.ink else R.color.look_toggle_off),
        )
    }

    private fun showScreen(capture: Boolean, result: Boolean, ready: Boolean) {
        screenCapture.visibility = if (capture) View.VISIBLE else View.GONE
        screenResult.visibility = if (result) View.VISIBLE else View.GONE
        screenReady.visibility = if (ready) View.VISIBLE else View.GONE
        holdLive = !capture
    }

    private fun resetToCapture() {
        shareJob += 1
        enhanceJob += 1
        lastShareUrl = null
        lastPolaroid?.recycle()
        lastPolaroid = null
        lastQr?.recycle()
        lastQr = null
        qrImg.setImageDrawable(null)
        qrWait.visibility = View.VISIBLE
        btnSnap.isEnabled = true
        btnShare.isEnabled = false
        prepareOverlay.visibility = View.GONE
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
        val needCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        val needMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        if (!needCam && !needMic) {
            warmUpMicrophoneForUsb()
            startCamera()
            return
        }
        statusText.text = "Permita Câmera e Microfone para usar a EMEET USB"
        avPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
            ),
        )
    }

    private fun usesEmeetCamera(): Boolean {
        val type = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_CAMERA_TYPE, "emeet")
            ?: "emeet"
        return type != "device"
    }

    private fun startCamera() {
        if (usesEmeetCamera()) {
            val delay = if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) 1_200L else 350L
            mainHandler.postDelayed({ startUvc() }, delay)
        } else {
            startDeviceCamera()
        }
    }

    private fun warmUpMicrophoneForUsb() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        processExecutor.execute {
            var recorder: AudioRecord? = null
            try {
                val rate = 8_000
                val min = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (min <= 0) return@execute
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min,
                )
                recorder.startRecording()
                Thread.sleep(100)
                recorder.stop()
            } catch (err: Exception) {
                Log.w(TAG, "warmUpMicrophoneForUsb", err)
            } finally {
                recorder?.release()
            }
        }
    }

    private fun requestUsbPermissionIfNeeded(device: UsbDevice) {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        if (mgr.hasPermission(device)) {
            uvc?.onUsbPermissionGranted(device)
            return
        }
        val pending = PendingIntent.getBroadcast(
            this,
            USB_PERMISSION_REQUEST_CODE,
            Intent(usbPermissionAction()).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        mgr.requestPermission(device, pending)
    }

    private fun readUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun restartCamera() {
        uvc?.release()
        uvc = null
        deviceCam?.release()
        deviceCam = null
        hasLiveFeed = false
        loader.visibility = View.VISIBLE
        startCamera()
    }

    private fun snapshotFromCamera(): Bitmap? = uvc?.snapshotFull() ?: deviceCam?.snapshotFull()

    private fun startDeviceCamera() {
        if (deviceCam != null) return
        uvcTexture.surfaceTextureListener = null
        deviceCam = DeviceCameraController(
            this,
            uvcTexture,
            onStatus = { msg -> mainHandler.post { statusText.text = msg } },
            onFrameBitmap = { bmp -> enqueueFrame(bmp) },
        ).also { it.start() }
    }

    private fun startUvc() {
        if (uvc != null) return
        deviceCam?.release()
        deviceCam = null
        uvc = UvcCameraController(
            this,
            uvcTexture,
            onStatus = { msg -> mainHandler.post { statusText.text = msg } },
            onFrameBitmap = { bmp -> enqueueFrame(bmp) },
            onNeedUsbPermission = { device ->
                mainHandler.post { requestUsbPermissionIfNeeded(device) }
            },
        ).also { controller ->
            // Restaurar qualidade de foto salva
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val quality = prefs.getString("captureQuality", "high") ?: "high"
            val (w, h) = when (quality) {
                "high" -> 960 to 720
                "higher" -> 1280 to 960
                "max" -> 1920 to 1440
                else -> 960 to 720
            }
            controller.setCaptureResolution(w, h)

            uvcTexture.surfaceTextureListener = controller
            controller.start()
        }
    }

    private fun enqueueFrame(frame: Bitmap) {
        if (holdLive) {
            frame.recycle()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastComposeAt < MIN_PREVIEW_COMPOSE_MS) {
            frame.recycle()
            return
        }
        if (processing.getAndSet(true)) {
            frame.recycle()
            return
        }
        processExecutor.execute {
            var working: Bitmap? = frame
            try {
                if (!::segmenter.isInitialized) {
                    mainHandler.post {
                        preview.setImageBitmap(frame)
                        statusText.text = "Preview cru (IA indisponível)"
                        markFeedReady()
                    }
                    working = null
                    return@execute
                }
                val live = downscaleForPreview(frame)
                if (live !== frame) frame.recycle()
                working = live
                val cropped = BoothAssets.coverCrop(
                    live,
                    BoothAssets.PREVIEW_W,
                    BoothAssets.PREVIEW_H,
                    BoothAssets.CAMERA_ZOOM,
                )
                if (cropped !== live) {
                    live.recycle()
                    working = cropped
                }
                val mask = obtainPreviewMask(cropped, now)
                val composed = compositor.compose(
                    cropped,
                    mask.data,
                    mask.width,
                    mask.height,
                    highQuality = false,
                    personBw = personBw,
                )
                if (cropped !== composed) cropped.recycle()
                working = null
                mainHandler.post {
                    preview.setImageBitmap(composed)
                    lastPreview?.recycle()
                    lastPreview = composed
                    markFeedReady()
                }
            } catch (err: Exception) {
                Log.e(TAG, "process", err)
                working?.recycle()
                mainHandler.post {
                    statusText.text = "Erro no filtro: ${err.message}"
                }
            } finally {
                lastComposeAt = System.currentTimeMillis()
                processing.set(false)
            }
        }
    }

    private fun startCountdownAndCapture() {
        if (!btnSnap.isEnabled) return
        btnSnap.isEnabled = false
        countdown.visibility = View.VISIBLE
        runCountdownStep(5) {
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
        mainHandler.postDelayed({ runCountdownStep(n - 1, onDone) }, 1000)
    }

    private fun capturePhoto() {
        val previewShot = lastPreview ?: run {
            Toast.makeText(this, "Aguardando primeiro frame da EMEET…", Toast.LENGTH_SHORT).show()
            btnSnap.isEnabled = true
            return
        }
        holdLive = true
        val job = ++enhanceJob
        frozenShot?.recycle()
        frozenShot = previewShot.copy(Bitmap.Config.ARGB_8888, false)
        shotImg.setImageBitmap(frozenShot)
        prepareOverlay.visibility = View.VISIBLE
        btnShare.isEnabled = false
        showScreen(capture = false, result = true, ready = false)

        processExecutor.execute {
            var source: Bitmap? = null
            try {
                source = snapshotFromCamera()
                    ?: previewShot.copy(Bitmap.Config.ARGB_8888, false)
                val inner = renderStill(source)
                if (source !== inner) source.recycle()
                source = null
                val card = PolaroidExporter.wrapShot(this, inner)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "foto_${stamp}_${System.currentTimeMillis() % 1000}.jpg"
                mainHandler.post {
                    if (job != enhanceJob) {
                        card.recycle()
                        inner.recycle()
                        return@post
                    }
                    try {
                        lastPolaroid?.recycle()
                        lastPolaroid = card
                        lastShareUrl = "${BuildConfig.SHARE_ORIGIN.trimEnd('/')}/p/$fileName"
                        lastQr?.recycle()
                        lastQr = QrEncoder.encode(lastShareUrl!!, 280)
                        frozenShot?.recycle()
                        frozenShot = inner
                        shotImg.setImageBitmap(frozenShot)
                        prepareOverlay.visibility = View.GONE
                        qrImg.setImageBitmap(lastQr)
                        qrWait.visibility = View.GONE
                        startShareUpload(card, fileName)
                        btnShare.isEnabled = true
                    } catch (err: Exception) {
                        card.recycle()
                        inner.recycle()
                        prepareOverlay.visibility = View.GONE
                        Toast.makeText(this, "Falha ao salvar: ${err.message}", Toast.LENGTH_LONG).show()
                        btnSnap.isEnabled = true
                        showScreen(capture = true, result = false, ready = false)
                    }
                }
            } catch (err: Exception) {
                source?.recycle()
                Log.e(TAG, "capture", err)
                mainHandler.post {
                    if (job != enhanceJob) return@post
                    prepareOverlay.visibility = View.GONE
                    Toast.makeText(this, "Falha ao salvar: ${err.message}", Toast.LENGTH_LONG).show()
                    btnSnap.isEnabled = true
                    showScreen(capture = true, result = false, ready = false)
                }
            }
        }
    }

    private fun renderStill(frame: Bitmap): Bitmap {
        val (tw, th) = BoothAssets.coverSize(frame.width, frame.height)
        val cropped = BoothAssets.coverCrop(frame, tw, th, BoothAssets.CAMERA_ZOOM)
        val cap = 640
        val longest = maxOf(tw, th).toFloat().coerceAtLeast(1f)
        val scale = minOf(1f, cap / longest)
        val segW = maxOf(384, (tw * scale).toInt())
        val segH = maxOf(480, (th * scale).toInt())
        val segInput = Bitmap.createScaledBitmap(cropped, segW, segH, true)
        val mask = segmenter.personMask(segInput)
        segInput.recycle()
        val composed = compositor.compose(
            cropped,
            mask.data,
            mask.width,
            mask.height,
            highQuality = true,
            personBw = personBw,
        )
        if (cropped !== composed && cropped !== frame) cropped.recycle()
        return composed
    }

    private fun obtainPreviewMask(cropped: Bitmap, now: Long): SegmentationEngine.PersonMask {
        val staleLook = previewMaskPersonBw != personBw
        val staleTime = previewMask == null || now - previewMaskAt >= PREVIEW_MASK_REFRESH_MS
        if (!staleLook && !staleTime) {
            return previewMask!!
        }
        val segInput = Bitmap.createScaledBitmap(
            cropped,
            BoothAssets.PREVIEW_SEG_W,
            BoothAssets.PREVIEW_SEG_H,
            true,
        )
        val mask = segmenter.personMask(segInput)
        segInput.recycle()
        previewMask = mask
        previewMaskAt = now
        previewMaskPersonBw = personBw
        return mask
    }

    private fun downscaleForPreview(src: Bitmap): Bitmap {
        val maxSide = 360
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide / longest.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, (src.width * scale).toInt()),
            maxOf(1, (src.height * scale).toInt()),
            false,
        )
    }

    private fun startShareUpload(card: Bitmap, fileName: String) {
        val job = ++shareJob
        shareExecutor.execute {
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    val scaled = scaleForUpload(card)
                    scaled.compress(Bitmap.CompressFormat.JPEG, 93, out)
                    if (scaled !== card) scaled.recycle()
                    out.toByteArray()
                }
                ShareApi.uploadPolaroid(bytes, fileName)
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
        val maxSide = 2000
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
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        if (usbReceiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            usbReceiverRegistered = false
        }
        processExecutor.shutdownNow()
        shareExecutor.shutdownNow()
        uvc?.release()
        deviceCam?.release()
        if (::compositor.isInitialized) compositor.release()
        if (::segmenter.isInitialized) segmenter.close()
        lastPreview?.recycle()
        frozenShot?.recycle()
        lastPolaroid?.recycle()
        lastQr?.recycle()
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val now = System.currentTimeMillis()
        if (now - lastUsbIntentAt < USB_INTENT_DEBOUNCE_MS) return
        lastUsbIntentAt = now
        readUsbDeviceExtra(intent)?.let { device ->
            mainHandler.postDelayed({
                warmUpMicrophoneForUsb()
                requestUsbPermissionIfNeeded(device)
            }, 600)
        }
        mainHandler.postDelayed({ uvc?.onUsbIntent() }, 1_500)
    }

    companion object {
        private const val TAG = "FiltroFanta"
        private const val PREFS = "booth_prefs"
        private const val KEY_PERSON_BW = "person_bw"
        private const val KEY_CAMERA_TYPE = "camera_type"
        private const val MIN_PREVIEW_COMPOSE_MS = 120L
        private const val PREVIEW_MASK_REFRESH_MS = 480L
        private const val USB_INTENT_DEBOUNCE_MS = 10_000L
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_PERMISSION"
        private const val USB_PERMISSION_REQUEST_CODE = 4401
    }

    private fun usbPermissionAction(): String = packageName + ACTION_USB_PERMISSION_SUFFIX

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
