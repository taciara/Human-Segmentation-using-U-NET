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

    /** Cena fixa do evento (Ghostface + cenário). */
    fun loadScene(w: Int, h: Int): Bitmap {
        val key = "scene:$w:$h"
        cache.get(key)?.let { return it }
        val bg = decodeAsset("backgrounds/bg_foto.png", w, h)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cache.put(key, bg)
        return bg
    }

    private fun decodeAsset(path: String, w: Int, h: Int): Bitmap? {
        return try {
            app.assets.open(path).use { stream ->
                val src = BitmapFactory.decodeStream(stream) ?: return null
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

        fun coverCrop(src: Bitmap, tw: Int, th: Int, zoomOut: Float = 1.35f): Bitmap {
            var bmp = src
            if (zoomOut > 1f) {
                val padX = (bmp.width * (zoomOut - 1f) / 2f).toInt()
                val padY = (bmp.height * (zoomOut - 1f) / 2f).toInt()
                val padded = Bitmap.createBitmap(
                    bmp.width + padX * 2,
                    bmp.height + padY * 2,
                    Bitmap.Config.ARGB_8888,
                )
                Canvas(padded).drawBitmap(bmp, padX.toFloat(), padY.toFloat(), null)
                if (padded !== bmp) bmp.recycle()
                bmp = padded
            }
            val scale = maxOf(tw.toFloat() / bmp.width, th.toFloat() / bmp.height)
            val nw = maxOf(1, (bmp.width * scale).toInt())
            val nh = maxOf(1, (bmp.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled !== bmp) bmp.recycle()
            val x = maxOf(0, (nw - tw) / 2)
            val y = maxOf(0, (nh - th) / 2)
            val out = Bitmap.createBitmap(scaled, x, y, tw.coerceAtMost(scaled.width), th.coerceAtMost(scaled.height))
            if (out !== scaled) scaled.recycle()
            return out
        }
    }
}
