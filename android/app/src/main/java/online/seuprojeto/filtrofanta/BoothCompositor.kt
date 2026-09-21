package online.seuprojeto.filtrofanta

import android.graphics.Bitmap
import android.graphics.Color

class BoothCompositor(private val assets: BoothAssets) {
    private val sceneCache = HashMap<String, Bitmap>()

    fun compose(
        cropped: Bitmap,
        mask: FloatArray,
        maskW: Int,
        maskH: Int,
        highQuality: Boolean,
    ): Bitmap {
        val tw = cropped.width
        val th = cropped.height
        val sceneBg = sceneFor(tw, th)
        val alpha = SegmentationEngine.refineAlpha(mask, maskW, maskH)
        val pixels = IntArray(tw * th)
        cropped.getPixels(pixels, 0, tw, 0, 0, tw, th)
        val bgPixels = IntArray(tw * th)
        sceneBg.getPixels(bgPixels, 0, tw, 0, 0, tw, th)
        val xScale = if (tw <= 1) 1f else (maskW - 1f) / (tw - 1f)
        val yScale = if (th <= 1) 1f else (maskH - 1f) / (th - 1f)
        val zoom = BoothAssets.PERSON_ZOOM
        val cx = tw / 2f
        val cy = th.toFloat()
        val outPixels = IntArray(tw * th)
        val needLerp = highQuality
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
                val a = SegmentationEngine.sampleBilinear(alpha, maskW, maskH, sx * xScale, sy * yScale)
                if (a <= 0.02f) {
                    outPixels[i] = bg
                    continue
                }
                val p = if (needLerp) {
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
                if (a >= 0.995f) {
                    outPixels[i] = p
                    continue
                }
                val ia = 1f - a
                outPixels[i] = Color.rgb(
                    (Color.red(p) * a + Color.red(bg) * ia).toInt(),
                    (Color.green(p) * a + Color.green(bg) * ia).toInt(),
                    (Color.blue(p) * a + Color.blue(bg) * ia).toInt(),
                )
            }
        }
        if (highQuality) sharpenPerson(outPixels, bgPixels, tw, th)
        val result = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, tw, 0, 0, tw, th)
        return result
    }

    fun release() {
        sceneCache.values.forEach { it.recycle() }
        sceneCache.clear()
    }

    private fun sceneFor(w: Int, h: Int): Bitmap {
        val key = "$w:$h"
        sceneCache[key]?.let { return it }
        return assets.loadScene(w, h).also { sceneCache[key] = it }
    }

    private fun sharpenPerson(px: IntArray, bg: IntArray, w: Int, h: Int) {
        val src = px.copyOf()
        val amount = 0.16f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (px[i] == bg[i]) continue
                if (src[(y - 1) * w + x] == bg[(y - 1) * w + x]) continue
                if (src[(y + 1) * w + x] == bg[(y + 1) * w + x]) continue
                if (src[y * w + x - 1] == bg[y * w + x - 1]) continue
                if (src[y * w + x + 1] == bg[y * w + x + 1]) continue
                val c = src[i]
                val up = src[(y - 1) * w + x]
                val dn = src[(y + 1) * w + x]
                val lf = src[y * w + x - 1]
                val rt = src[y * w + x + 1]
                fun ch(p: Int, s: Int) = (p ushr s) and 0xff
                fun sharp(s: Int): Int {
                    val v = ch(c, s) + amount * (
                        4 * ch(c, s) - ch(up, s) - ch(dn, s) - ch(lf, s) - ch(rt, s)
                    )
                    return v.toInt().coerceIn(0, 255)
                }
                px[i] = Color.rgb(sharp(16), sharp(8), sharp(0))
            }
        }
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
