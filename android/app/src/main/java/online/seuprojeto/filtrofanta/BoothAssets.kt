package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache

class BoothAssets(context: Context, private val imageConfig: BoothImageConfig) {
    private val app = context.applicationContext
    private val cache = LruCache<String, Bitmap>(4)

    fun clearSceneCache() {
        val snapshot = cache.snapshot()
        for (bmp in snapshot.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        cache.evictAll()
    }

    fun loadScene(w: Int, h: Int, personBw: Boolean): Bitmap {
        val rev = imageConfig.revisionToken()
        val key = "scene:$w:$h:$personBw:$rev"
        cache.get(key)?.let { return it }
        val bgPath = if (personBw) BG_LOCKER_BW else BG_LOCKER_COLOR
        val background = imageConfig.decodeBackground(personBw, w, h)
            ?: decodeAsset("backgrounds/$bgPath", w, h)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var scene = background.copy(Bitmap.Config.ARGB_8888, true)
        val characterRaw = imageConfig.decodeCharacter()
            ?: decodeAsset("overlays/personagem.webp", 0, 0, alpha = true)
        if (characterRaw != null) {
            val placed = placeOverlay(characterRaw, w, h, xShift = CHARACTER_X_SHIFT)
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
        private const val BG_LOCKER_COLOR = "fundo_lockeroom_color.webp"
        private const val BG_LOCKER_BW = "fundo_lockeroom_pb.webp"
        const val CHARACTER_X_SHIFT = 0.16f

        /** Preview ao vivo (leve). A foto final usa VIEW_W/H. */
        const val PREVIEW_W = 480
        const val PREVIEW_H = 600
        const val VIEW_W = 840
        const val VIEW_H = 1050

        /**
         * Zoom da câmera no visor 4:5, aplicado ANTES da segmentação (o mask
         * já nasce alinhado com essa imagem, sem precisar de nenhum cálculo
         * de alinhamento depois). >1 corta mais a cena (zoom in, pessoa
         * maior); <1 mostra mais cena ao redor (zoom out, pessoa menor),
         * replicando a borda da câmera em vez de esticar a imagem — 0.83 =
         * pessoa ~30% menor, testado e sem cortar nem esticar nada.
         */
        const val CAMERA_ZOOM = 1f

        /**
         * Encolhe só a silhueta da pessoa, ancorada embaixo (BoothCompositor
         * usa cy = th, não th/2), pra sobrar espaço em cima pro Ghostface —
         * que continua com o mesmo tamanho e posição fixos de sempre.
         * 0.7 = pessoa com ~70% do tamanho atual.
         */
        const val PERSON_ZOOM = 1f

        private data class CropTransform(
            val padX: Int,
            val padY: Int,
            val paddedW: Int,
            val paddedH: Int,
            val scale: Float,
        )

        private fun computeCropTransform(fw: Int, fh: Int, tw: Int, th: Int, zoom: Float): CropTransform {
            var padX = 0
            var padY = 0
            var pw = maxOf(1, fw)
            var ph = maxOf(1, fh)
            if (zoom < 1f && zoom > 0f) {
                val zoomOut = 1f / zoom
                padX = ((pw * (zoomOut - 1f)) / 2f).toInt().coerceAtLeast(0)
                padY = ((ph * (zoomOut - 1f)) / 2f).toInt().coerceAtLeast(0)
                pw += padX * 2
                ph += padY * 2
            }
            val baseScale = maxOf(tw.toFloat() / pw, th.toFloat() / ph)
            val scale = if (zoom > 1f) baseScale * zoom else baseScale
            return CropTransform(padX, padY, pw, ph, scale)
        }

        /** Estende os pixels da borda pra fora (equivalente a cv2.BORDER_REPLICATE), sem esticar a imagem. */
        private fun padReplicate(src: Bitmap, padX: Int, padY: Int): Bitmap {
            if (padX <= 0 && padY <= 0) return src
            val pw = src.width + padX * 2
            val ph = src.height + padY * 2
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply { setTranslate(padX.toFloat(), padY.toFloat()) })
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
            c.drawRect(0f, 0f, pw.toFloat(), ph.toFloat(), paint)
            return out
        }

        fun coverSize(srcW: Int, srcH: Int): Pair<Int, Int> {
            var th = maxOf(1, srcH)
            var tw = th * 4 / 5
            if (tw > srcW) {
                tw = maxOf(1, srcW)
                th = tw * 5 / 4
            }
            return tw to th
        }

        fun coverCrop(src: Bitmap, tw: Int, th: Int, zoom: Float = 1f): Bitmap {
            val t = computeCropTransform(src.width, src.height, tw, th, zoom)
            val padded = padReplicate(src, t.padX, t.padY)
            val nw = maxOf(1, (t.paddedW * t.scale).toInt())
            val nh = maxOf(1, (t.paddedH * t.scale).toInt())
            val scaled = Bitmap.createScaledBitmap(padded, nw, nh, true)
            if (padded !== src) padded.recycle()
            val x = maxOf(0, (nw - tw) / 2).coerceAtMost(maxOf(0, nw - tw))
            val y = maxOf(0, (nh - th) / 2).coerceAtMost(maxOf(0, nh - th))
            val cw = tw.coerceAtMost(scaled.width - x)
            val ch = th.coerceAtMost(scaled.height - y)
            val out = Bitmap.createBitmap(scaled, x, y, cw, ch)
            if (scaled !== out) scaled.recycle()
            return out
        }

        fun placeOverlay(overlay: Bitmap, tw: Int, th: Int, xShift: Float): Bitmap {
            val canvas = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(canvas)
            val scale = (th * 0.95f) / overlay.height
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
