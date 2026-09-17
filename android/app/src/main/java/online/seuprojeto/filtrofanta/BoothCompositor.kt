package online.seuprojeto.filtrofanta

import android.graphics.Bitmap
import android.graphics.Color

class BoothCompositor(private val assets: BoothAssets) {
    private var sceneCache: Bitmap? = null

    fun compose(cropped: Bitmap, mask: FloatArray, maskW: Int, maskH: Int): Bitmap {
        val tw = cropped.width
        val th = cropped.height
        val sceneBg = sceneCache ?: assets.loadScene(tw, th).also { sceneCache = it }
        val alpha = SegmentationEngine.refineAlpha(mask, maskW, maskH)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(tw * th)
        cropped.getPixels(pixels, 0, tw, 0, 0, tw, th)
        val bgPixels = IntArray(tw * th)
        sceneBg.getPixels(bgPixels, 0, tw, 0, 0, tw, th)
        val xScale = if (tw <= 1) 1f else (maskW - 1f) / (tw - 1f)
        val yScale = if (th <= 1) 1f else (maskH - 1f) / (th - 1f)
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val i = y * tw + x
                val a = SegmentationEngine.sampleBilinear(alpha, maskW, maskH, x * xScale, y * yScale)
                val p = pixels[i]
                val gray = SegmentationEngine.toGray(Color.blue(p), Color.green(p), Color.red(p))
                val bg = bgPixels[i]
                val nr = (Color.red(gray) * a + Color.red(bg) * (1 - a)).toInt()
                val ng = (Color.green(gray) * a + Color.green(bg) * (1 - a)).toInt()
                val nb = (Color.blue(gray) * a + Color.blue(bg) * (1 - a)).toInt()
                pixels[i] = Color.rgb(nr, ng, nb)
            }
        }
        out.setPixels(pixels, 0, tw, 0, 0, tw, th)
        return out
    }

    fun release() {
        sceneCache?.recycle()
        sceneCache = null
    }
}
