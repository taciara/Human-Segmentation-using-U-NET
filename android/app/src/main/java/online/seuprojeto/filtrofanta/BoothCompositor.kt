package online.seuprojeto.filtrofanta

import android.graphics.Bitmap
import android.graphics.Color

class BoothCompositor(private val assets: BoothAssets) {
    private var sceneCache: Bitmap? = null

    fun compose(frame: Bitmap, mask: FloatArray, maskW: Int, maskH: Int): Bitmap {
        val tw = BoothAssets.VIEW_W
        val th = BoothAssets.VIEW_H
        val cropped = BoothAssets.coverCrop(frame, tw, th)
        val sceneBg = sceneCache ?: assets.loadScene(tw, th).also { sceneCache = it }

        val alpha = SegmentationEngine.refineAlpha(mask, maskW, maskH)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(tw * th)
        cropped.getPixels(pixels, 0, tw, 0, 0, tw, th)
        val bgPixels = IntArray(tw * th)
        sceneBg.getPixels(bgPixels, 0, tw, 0, 0, tw, th)

        for (y in 0 until th) {
            for (x in 0 until tw) {
                val i = y * tw + x
                val a = SegmentationEngine.sampleAlpha(alpha, maskW, maskH, x, y, tw, th)
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
        cropped.recycle()
        return out
    }

    fun release() {
        sceneCache?.recycle()
        sceneCache = null
    }
}
