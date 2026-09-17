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
        val pixels = IntArray(tw * th)
        cropped.getPixels(pixels, 0, tw, 0, 0, tw, th)
        val bgPixels = IntArray(tw * th)
        sceneBg.getPixels(bgPixels, 0, tw, 0, 0, tw, th)
        val xScale = if (tw <= 1) 1f else (maskW - 1f) / (tw - 1f)
        val yScale = if (th <= 1) 1f else (maskH - 1f) / (th - 1f)
        val zoom = BoothAssets.PERSON_ZOOM
        // Ancorado embaixo (pés), não no centro: a pessoa encolhe "afundando"
        // no quadro, sobrando espaço em cima pro Ghostface (tamanho e posição
        // fixos, perto do topo) aparecer claramente atrás dela — em vez de as
        // duas cabeças ficarem na mesma altura, do lado uma da outra.
        val cx = tw / 2f
        val cy = th.toFloat()
        val outPixels = IntArray(tw * th)
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val i = y * tw + x
                val bg = bgPixels[i]
                // Mapeamento inverso: encolhe a pessoa em torno do centro do
                // quadro sem tocar no crop da câmera (puro array de pixels,
                // sem Canvas/Shader — evita o bug de listras do coverCrop).
                val sx = (x - cx) / zoom + cx
                val sy = (y - cy) / zoom + cy
                if (sx < 0f || sy < 0f || sx > tw - 1f || sy > th - 1f) {
                    outPixels[i] = bg
                    continue
                }
                val ix = sx.toInt().coerceIn(0, tw - 1)
                val iy = sy.toInt().coerceIn(0, th - 1)
                val a = SegmentationEngine.sampleBilinear(alpha, maskW, maskH, ix * xScale, iy * yScale)
                if (a <= 0.02f) {
                    outPixels[i] = bg
                    continue
                }
                val p = pixels[iy * tw + ix]
                val gray = SegmentationEngine.toGray(Color.blue(p), Color.green(p), Color.red(p))
                val nr = (Color.red(gray) * a + Color.red(bg) * (1 - a)).toInt()
                val ng = (Color.green(gray) * a + Color.green(bg) * (1 - a)).toInt()
                val nb = (Color.blue(gray) * a + Color.blue(bg) * (1 - a)).toInt()
                outPixels[i] = Color.rgb(nr, ng, nb)
            }
        }
        val result = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, tw, 0, 0, tw, th)
        return result
    }

    fun release() {
        sceneCache?.recycle()
        sceneCache = null
    }
}
