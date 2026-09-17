package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache

class BoothAssets(context: Context) {
    private val app = context.applicationContext
    private val cache = LruCache<String, Bitmap>(4)

    /**
     * Igual ao Flask (`app.py`): `bg_foto.png` + Ghostface em `overlays/personagem.png`.
     * Default da sessão web: `_defaults["background"] = "bg_foto.png"`.
     */
    fun loadScene(w: Int, h: Int): Bitmap {
        val key = "scene:$w:$h"
        cache.get(key)?.let { return it }
        val background = decodeAsset("backgrounds/bg_foto.png", w, h)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var scene = background.copy(Bitmap.Config.ARGB_8888, true)
        val characterRaw = decodeAsset("overlays/personagem.png", 0, 0, alpha = true)
        if (characterRaw != null) {
            val placed = placeOverlay(characterRaw, w, h, xShift = 0.06f)
            characterRaw.recycle()
            scene = overlayRgba(scene, placed)
            placed.recycle()
        }
        cache.put(key, scene)
        return scene
    }

    private fun decodeAsset(path: String, w: Int, h: Int, alpha: Boolean = false): Bitmap? {
        return try {
            app.assets.open(path).use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.ARGB_8888
                }
                val src = BitmapFactory.decodeStream(stream, null, opts) ?: return null
                if (w <= 0 || h <= 0) return src
                if (src.width == w && src.height == h) return src
                Bitmap.createScaledBitmap(src, w, h, true).also {
                    if (it !== src) src.recycle()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val VIEW_W = 480
        const val VIEW_H = 600

        fun coverCrop(src: Bitmap, tw: Int, th: Int): Bitmap {
            val scale = maxOf(tw.toFloat() / maxOf(1, src.width), th.toFloat() / maxOf(1, src.height))
            val nw = maxOf(1, (src.width * scale).toInt())
            val nh = maxOf(1, (src.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            val x = maxOf(0, (nw - tw) / 2)
            val y = maxOf(0, (nh - th) / 2)
            val cw = tw.coerceAtMost(scaled.width - x)
            val ch = th.coerceAtMost(scaled.height - y)
            val out = Bitmap.createBitmap(scaled, x, y, cw, ch)
            if (out.width != tw || out.height != th) {
                val filled = Bitmap.createScaledBitmap(out, tw, th, true)
                if (filled !== out) out.recycle()
                if (scaled !== src && scaled !== filled) scaled.recycle()
                return filled
            }
            if (scaled !== src && scaled !== out) scaled.recycle()
            return out
        }

        fun placeOverlay(overlay: Bitmap, tw: Int, th: Int, xShift: Float): Bitmap {
            val canvas = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(canvas)
            val scale = th / overlay.height.toFloat()
            val nw = maxOf(1, (overlay.width * scale).toInt())
            val nh = maxOf(1, (overlay.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(overlay, nw, nh, true)
            val x = ((tw - nw) / 2f + tw * xShift).toInt()
            val y = th - nh
            c.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
            scaled.recycle()
            return canvas
        }

        fun overlayRgba(base: Bitmap, overlay: Bitmap): Bitmap {
            val out = base.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(out).drawBitmap(overlay, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            return out
        }
    }
}
