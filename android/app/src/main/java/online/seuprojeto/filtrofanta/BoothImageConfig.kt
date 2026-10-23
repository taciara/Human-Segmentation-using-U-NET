package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min

class BoothImageConfig(context: Context) {
    enum class Slot {
        BG_COLOR,
        BG_BW,
        CHARACTER,
    }

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "booth_overrides")
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun usesCustom(slot: Slot): Boolean {
        return prefs.getBoolean(prefKey(slot), false) && activeFile(slot).isFile
    }

    fun hasPendingChanges(): Boolean {
        for (slot in Slot.entries) {
            if (pendingFile(slot).isFile) return true
            if (pendingDefault(slot)) return true
        }
        return false
    }

    fun stageDefault(slot: Slot) {
        pendingFile(slot).delete()
        prefs.edit().putBoolean(pendingDefaultKey(slot), true).apply()
    }

    /** Copia, redimensiona e grava rascunho (JPEG fundo / PNG personagem). */
    fun importFromUri(slot: Slot, uri: Uri): Boolean {
        val temp = copyUriToTemp(uri) ?: return false
        return try {
            val (maxW, maxH) = maxDimensions(slot)
            var bitmap = decodeFromFile(temp, maxW, maxH) ?: run {
                Log.e(TAG, "importFromUri: decode failed for $uri")
                return false
            }
            val scaled = fitInside(bitmap, maxW, maxH)
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
            dir.mkdirs()
            val outFile = pendingFile(slot)
            val saved = outFile.outputStream().use { output ->
                when (slot) {
                    Slot.CHARACTER -> bitmap.compress(Bitmap.CompressFormat.PNG, 92, output)
                    else -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                }
            }
            bitmap.recycle()
            if (!saved || outFile.length() <= 0L) {
                outFile.delete()
                return false
            }
            prefs.edit().putBoolean(pendingDefaultKey(slot), false).apply()
            true
        } catch (err: Exception) {
            Log.e(TAG, "importFromUri", err)
            false
        } finally {
            temp.delete()
        }
    }

    fun commitPending() {
        for (slot in Slot.entries) {
            if (pendingDefault(slot)) {
                activeFile(slot).delete()
                pendingFile(slot).delete()
                prefs.edit()
                    .putBoolean(prefKey(slot), false)
                    .putBoolean(pendingDefaultKey(slot), false)
                    .apply()
                continue
            }
            val pending = pendingFile(slot)
            if (pending.isFile) {
                val active = activeFile(slot)
                pending.copyTo(active, overwrite = true)
                pending.delete()
                prefs.edit()
                    .putBoolean(prefKey(slot), true)
                    .putBoolean(pendingDefaultKey(slot), false)
                    .apply()
            }
        }
    }

    fun discardPending() {
        for (slot in Slot.entries) {
            pendingFile(slot).delete()
            prefs.edit().putBoolean(pendingDefaultKey(slot), false).apply()
        }
    }

    fun previewFile(slot: Slot): File? {
        if (pendingDefault(slot)) return null
        val pending = pendingFile(slot)
        if (pending.isFile) return pending
        if (usesCustom(slot)) return activeFile(slot)
        return null
    }

    fun decodeThumbnail(file: File, maxSide: Int = 220): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        val nw = max(1, (src.width * scale).toInt())
        val nh = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, nw, nh, true).also {
            if (it !== src) src.recycle()
        }
    }

    fun decodeBackground(personBw: Boolean, w: Int, h: Int): Bitmap? {
        val slot = if (personBw) Slot.BG_BW else Slot.BG_COLOR
        if (!usesCustom(slot)) return null
        return decodeScaled(activeFile(slot), w, h)
    }

    fun decodeCharacter(): Bitmap? {
        if (!usesCustom(Slot.CHARACTER)) return null
        return decodeScaled(activeFile(Slot.CHARACTER), 0, 0)
    }

    fun revisionToken(): String {
        return buildString {
            for (slot in Slot.entries) {
                append(slot.name)
                append(':')
                if (usesCustom(slot)) append(activeFile(slot).lastModified()) else append('0')
                append(';')
            }
        }
    }

    fun rowStatus(slot: Slot): String {
        return when {
            pendingDefault(slot) -> "Ao salvar: voltará ao padrão do app"
            pendingFile(slot).isFile -> "Prévia pronta — toque em Salvar para aplicar"
            usesCustom(slot) -> "Personalizado (ativo no photobooth)"
            else -> "Padrão do app (${defaultAssetPath(slot)})"
        }
    }

    fun defaultAssetPath(slot: Slot): String = when (slot) {
        Slot.BG_COLOR -> "backgrounds/fundo_lockeroom_color.webp"
        Slot.BG_BW -> "backgrounds/fundo_lockeroom_pb.webp"
        Slot.CHARACTER -> "overlays/personagem.webp"
    }

    private fun copyUriToTemp(uri: Uri): File? {
        dir.mkdirs()
        val temp = File(dir, "_import_temp.bin")
        temp.delete()
        return try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (temp.length() <= 0L) null else temp
        } catch (err: Exception) {
            Log.e(TAG, "copyUriToTemp", err)
            temp.delete()
            null
        }
    }

    private fun decodeFromFile(file: File, maxW: Int, maxH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } catch (err: Exception) {
                Log.e(TAG, "ImageDecoder failed", err)
                null
            }
        }
        return null
    }

    private fun maxDimensions(slot: Slot): Pair<Int, Int> = when (slot) {
        Slot.CHARACTER -> 960 to 1400
        else -> 1080 to 1350
    }

    private fun activeFile(slot: Slot): File {
        val opt = File(dir, "${slot.name.lowercase()}.opt")
        if (opt.isFile) return opt
        val legacy = File(dir, "${slot.name.lowercase()}.bin")
        if (legacy.isFile) return legacy
        return opt
    }

    private fun pendingFile(slot: Slot) = File(dir, "${slot.name.lowercase()}.pending")

    private fun prefKey(slot: Slot) = "custom_${slot.name}"

    private fun pendingDefaultKey(slot: Slot) = "pending_default_${slot.name}"

    private fun pendingDefault(slot: Slot) = prefs.getBoolean(pendingDefaultKey(slot), false)

    private fun decodeScaled(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (w > 0 && h > 0) {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, w, h)
            }
        }
        val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (w <= 0 || h <= 0) return src
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true).also {
            if (it !== src) src.recycle()
        }
    }

    private fun fitInside(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (src.width <= maxW && src.height <= maxH) return src
        val scale = min(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        val nw = max(1, (src.width * scale).toInt())
        val nh = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (srcH > reqH || srcW > reqW) {
            var halfH = srcH / 2
            var halfW = srcW / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "BoothImageConfig"
        private const val PREFS = "booth_image_config"
        const val ADMIN_PIN = "1425"
    }
}
