package online.seuprojeto.filtrofanta

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class ImageConfigActivity : AppCompatActivity() {
    private lateinit var config: BoothImageConfig
    private lateinit var processingOverlay: LinearLayout
    private lateinit var btnSave: Button
    private var pendingSlot: BoothImageConfig.Slot? = null
    private val thumbCache = HashMap<BoothImageConfig.Slot, Bitmap?>()
    private val importExecutor = Executors.newSingleThreadExecutor()

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri == null || slot == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // GetContent / alguns pickers não permitem persistência; cópia local resolve.
        }
        setProcessing(true)
        importExecutor.execute {
            val ok = config.importFromUri(slot, uri)
            runOnUiThread {
                setProcessing(false)
                if (ok) {
                    thumbCache.remove(slot)
                    refreshRows()
                    updateSaveButton()
                    Toast.makeText(this, "Prévia pronta — toque em Salvar", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Não foi possível usar esta imagem", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_config)
        config = BoothImageConfig(this)
        processingOverlay = findViewById(R.id.configProcessing)
        btnSave = findViewById(R.id.btnConfigSave)

        bindRow(
            findViewById(R.id.rowBgColor),
            "Fundo (colorido)",
            BoothImageConfig.Slot.BG_COLOR,
        )
        bindRow(
            findViewById(R.id.rowBgBw),
            "Fundo (preto e branco)",
            BoothImageConfig.Slot.BG_BW,
        )
        bindRow(
            findViewById(R.id.rowCharacter),
            "Personagem (overlay)",
            BoothImageConfig.Slot.CHARACTER,
        )

        btnSave.setOnClickListener { saveAndFinish() }
        findViewById<Button>(R.id.btnConfigCancel).setOnClickListener { confirmCancel() }
        updateSaveButton()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = confirmCancel()
            },
        )
    }

    private fun saveAndFinish() {
        config.commitPending()
        setResult(RESULT_OK)
        Toast.makeText(this, "Configuração salva", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmCancel() {
        if (!config.hasPendingChanges()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Descartar alterações?")
            .setMessage("Nada será aplicado no photobooth até você tocar em Salvar.")
            .setNegativeButton("Continuar editando", null)
            .setPositiveButton("Sair sem salvar") { _, _ ->
                config.discardPending()
                finish()
            }
            .show()
    }

    private fun bindRow(root: View, title: String, slot: BoothImageConfig.Slot) {
        root.findViewById<TextView>(R.id.configRowTitle).text = title
        val status = root.findViewById<TextView>(R.id.configRowStatus)
        status.tag = slot
        root.findViewById<Button>(R.id.btnUseDefault).setOnClickListener {
            config.stageDefault(slot)
            thumbCache.remove(slot)
            refreshRows()
            updateSaveButton()
            Toast.makeText(this, "Padrão selecionado — toque em Salvar", Toast.LENGTH_SHORT).show()
        }
        root.findViewById<Button>(R.id.btnUpload).setOnClickListener {
            pendingSlot = slot
            pickImage.launch(arrayOf("image/*"))
        }
        updateRow(root, slot)
    }

    private fun refreshRows() {
        listOf(R.id.rowBgColor, R.id.rowBgBw, R.id.rowCharacter).forEach { id ->
            val root = findViewById<View>(id)
            val slot = root.findViewById<TextView>(R.id.configRowStatus).tag as BoothImageConfig.Slot
            updateRow(root, slot)
        }
    }

    private fun updateRow(root: View, slot: BoothImageConfig.Slot) {
        root.findViewById<TextView>(R.id.configRowStatus).text = config.rowStatus(slot)
        val thumb = root.findViewById<ImageView>(R.id.configRowThumb)
        val file = config.previewFile(slot)
        if (file == null) {
            thumb.setImageDrawable(null)
            thumb.visibility = View.GONE
            return
        }
        thumb.visibility = View.VISIBLE
        val cached = thumbCache[slot]
        if (cached != null && !cached.isRecycled) {
            thumb.setImageBitmap(cached)
            return
        }
        importExecutor.execute {
            val bmp = config.decodeThumbnail(file)
            runOnUiThread {
                if (bmp != null) {
                    thumbCache[slot]?.recycle()
                    thumbCache[slot] = bmp
                    thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun updateSaveButton() {
        btnSave.isEnabled = config.hasPendingChanges()
        btnSave.alpha = if (btnSave.isEnabled) 1f else 0.45f
    }

    private fun setProcessing(on: Boolean) {
        processingOverlay.visibility = if (on) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        thumbCache.values.forEach { it?.recycle() }
        thumbCache.clear()
        importExecutor.shutdownNow()
        super.onDestroy()
    }
}
