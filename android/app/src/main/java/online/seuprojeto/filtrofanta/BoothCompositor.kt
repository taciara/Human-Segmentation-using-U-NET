package online.seuprojeto.filtrofanta

import android.graphics.Bitmap
import android.graphics.Color
import java.util.concurrent.locks.ReentrantLock

class BoothCompositor(private val assets: BoothAssets) {
    private val sceneCache = HashMap<String, Bitmap>()
    private val sceneLock = ReentrantLock()

    fun compose(
        cropped: Bitmap,
        mask: FloatArray,
        maskW: Int,
        maskH: Int,
        highQuality: Boolean,
        personBw: Boolean,
    ): Bitmap {
        sceneLock.lock()
        try {
            return composeLocked(cropped, mask, maskW, maskH, highQuality, personBw)
        } finally {
            sceneLock.unlock()
        }
    }

    private fun composeLocked(
        cropped: Bitmap,
        mask: FloatArray,
        maskW: Int,
        maskH: Int,
        highQuality: Boolean,
        personBw: Boolean,
    ): Bitmap {
        val tw = cropped.width
        val th = cropped.height
        val sceneBg = sceneFor(tw, th, personBw)
        val alpha = if (highQuality) {
            val full = if (maskW == tw && maskH == th) {
                mask
            } else {
                SegmentationEngine.upsampleBilinear(mask, maskW, maskH, tw, th)
            }
            SegmentationEngine.refineAlphaStill(full, tw, th)
        } else {
            val refined = SegmentationEngine.refineAlphaPreview(mask, maskW, maskH)
            if (maskW == tw && maskH == th) {
                refined
            } else {
                SegmentationEngine.upsampleBilinear(refined, maskW, maskH, tw, th)
            }
        }
        val alphaW = tw
        val alphaH = th
        val pixels = IntArray(tw * th)
        cropped.getPixels(pixels, 0, tw, 0, 0, tw, th)
        val bgPixels = IntArray(tw * th)
        sceneBg.getPixels(bgPixels, 0, tw, 0, 0, tw, th)
        val xScale = if (tw <= 1) 1f else (alphaW - 1f) / (tw - 1f)
        val yScale = if (th <= 1) 1f else (alphaH - 1f) / (th - 1f)
        val zoom = BoothAssets.PERSON_ZOOM
        val cx = tw / 2f
        val cy = th.toFloat()
        val outPixels = IntArray(tw * th)
        val lerpPerson = highQuality
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val i = y * tw + x
                val bg = bgPixels[i]
                val sx = (x - cx) / zoom + cx
                val sy = (y - cy) / zoom + cy
                if (sx < 0f || sy < 0f || sx > tw - 1f || sy > th - 1f) {
                    outPixels[i] = bg
                    continue
                }
                val ix = sx.toInt().coerceIn(0, tw - 1)
                val iy = sy.toInt().coerceIn(0, th - 1)
                val a = SegmentationEngine.sampleBilinear(alpha, alphaW, alphaH, sx * xScale, sy * yScale)
                if (a <= 0.02f) {
                    outPixels[i] = bg
                    continue
                }
                val p = if (lerpPerson) {
                    val ix1 = minOf(ix + 1, tw - 1)
                    val iy1 = minOf(iy + 1, th - 1)
                    lerpPixel(
                        pixels[iy * tw + ix],
                        pixels[iy * tw + ix1],
                        pixels[iy1 * tw + ix],
                        pixels[iy1 * tw + ix1],
                        sx - ix,
                        sy - iy,
                    )
                } else {
                    pixels[iy * tw + ix]
                }
                var fg = if (personBw) toGray(p) else p
                if (highQuality && a in 0.12f..0.94f) {
                    fg = suppressLightFringe(fg, bg, a)
                }
                if (a >= 0.985f) {
                    outPixels[i] = fg
                    continue
                }
                val ia = 1f - a
                outPixels[i] = Color.rgb(
                    (Color.red(fg) * a + Color.red(bg) * ia).toInt(),
                    (Color.green(fg) * a + Color.green(bg) * ia).toInt(),
                    (Color.blue(fg) * a + Color.blue(bg) * ia).toInt(),
                )
            }
        }
        val config = if (highQuality) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val result = Bitmap.createBitmap(tw, th, config)
        result.setPixels(outPixels, 0, tw, 0, 0, tw, th)
        return result
    }

    fun release() {
        invalidateScenes()
        assets.clearSceneCache()
    }

    fun invalidateScenes() {
        sceneLock.lock()
        try {
            sceneCache.values.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            sceneCache.clear()
        } finally {
            sceneLock.unlock()
        }
    }

    private fun sceneFor(w: Int, h: Int, personBw: Boolean): Bitmap {
        val key = "$w:$h:$personBw"
        sceneCache[key]?.let { return it }
        val master = assets.loadScene(w, h, personBw)
        val copy = master.copy(master.config ?: Bitmap.Config.ARGB_8888, true)
        sceneCache[key] = copy
        return copy
    }

    private fun toGray(rgb: Int): Int {
        val y = (
            0.299f * Color.red(rgb) +
                0.587f * Color.green(rgb) +
                0.114f * Color.blue(rgb)
            ).toInt().coerceIn(0, 255)
        return Color.rgb(y, y, y)
    }

    /** Reduz halo claro (cabelo/fundo claro) na faixa semi-transparente. */
    private fun suppressLightFringe(fg: Int, bg: Int, alpha: Float): Int {
        val edge = ((0.94f - alpha) / 0.82f).coerceIn(0f, 1f)
        if (edge <= 0.01f) return fg
        val lumFg = 0.299f * Color.red(fg) + 0.587f * Color.green(fg) + 0.114f * Color.blue(fg)
        val lumBg = 0.299f * Color.red(bg) + 0.587f * Color.green(bg) + 0.114f * Color.blue(bg)
        val spill = (lumFg - lumBg - 14f).coerceAtLeast(0f) / 255f
        if (spill <= 0f) return fg
        val pull = (spill * edge * 0.85f).coerceIn(0f, 0.55f)
        fun ch(f: Int, b: Int) = (f + ((b - f) * pull).toInt()).coerceIn(0, 255)
        return Color.rgb(ch(Color.red(fg), Color.red(bg)), ch(Color.green(fg), Color.green(bg)), ch(Color.blue(fg), Color.blue(bg)))
    }

    private fun lerpPixel(p00: Int, p10: Int, p01: Int, p11: Int, tx: Float, ty: Float): Int {
        fun ch(p: Int, shift: Int) = (p ushr shift) and 0xff
        fun mix(a: Int, b: Int, t: Float): Float = a + (b - a) * t
        fun axis(shift: Int): Int {
            val a = mix(ch(p00, shift), ch(p10, shift), tx)
            val b = mix(ch(p01, shift), ch(p11, shift), tx)
            return (a + (b - a) * ty).toInt().coerceIn(0, 255)
        }
        return Color.rgb(axis(16), axis(8), axis(0))
    }
}
