package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * CSS `.polaroid` — papel #e6dfd2, manchas em SRC_OVER (o Multiply do Android
 * escurecia o cartão inteiro) e grão suave.
 */
class PolaroidPaperDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paper = Paint().apply { color = Color.parseColor("#E6DFD2") }
    private val stain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.parseColor("#CFC6B6")
    }
    private val noisePaint = Paint().apply { alpha = 28 }
    private val noiseShader = lazy {
        android.graphics.BitmapShader(noiseTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        val r = bounds
        if (r.isEmpty) return

        canvas.drawRect(r, paper)

        // radial-gradient ellipses — mesma rgba do CSS, composição normal
        blob(canvas, r, 0.16f, 0.92f, 0.55f, Color.argb(56, 72, 48, 28))
        blob(canvas, r, 0.86f, 0.96f, 0.50f, Color.argb(46, 50, 38, 28))
        blob(canvas, r, 0.50f, 0.08f, 0.62f, Color.argb(90, 255, 252, 245))

        noisePaint.shader = noiseShader.value
        canvas.drawRect(r, noisePaint)
        noisePaint.shader = null

        inset(canvas, r)
        after(canvas, r)

        canvas.drawRect(
            r.left + borderPaint.strokeWidth / 2f,
            r.top + borderPaint.strokeWidth / 2f,
            r.right - borderPaint.strokeWidth / 2f,
            r.bottom - borderPaint.strokeWidth / 2f,
            borderPaint,
        )
    }

    private fun blob(canvas: Canvas, r: Rect, fx: Float, fy: Float, reach: Float, color: Int) {
        stain.shader = RadialGradient(
            r.left + r.width() * fx,
            r.top + r.height() * fy,
            max(r.width(), r.height()) * reach,
            intArrayOf(color, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, stain)
        stain.shader = null
    }

    private fun inset(canvas: Canvas, r: Rect) {
        stain.shader = LinearGradient(
            0f, r.top.toFloat(), 0f, r.top + 8f * density,
            Color.argb(89, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.top + 8f * density, stain)
        stain.shader = LinearGradient(
            0f, r.bottom - 34f * density, 0f, r.bottom.toFloat(),
            Color.TRANSPARENT, Color.argb(41, 62, 42, 24), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r.left.toFloat(), r.bottom - 34f * density, r.right.toFloat(), r.bottom.toFloat(), stain)
        val side = 22f * density
        stain.shader = LinearGradient(
            r.left.toFloat(), 0f, r.left + side, 0f,
            Color.argb(26, 40, 30, 20), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.left + side, r.bottom.toFloat(), stain)
        stain.shader = LinearGradient(
            r.right - side, 0f, r.right.toFloat(), 0f,
            Color.TRANSPARENT, Color.argb(26, 40, 30, 20), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r.right - side, r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), stain)
        stain.shader = null
    }

    /** `.polaroid::after` */
    private fun after(canvas: Canvas, r: Rect) {
        stain.shader = RadialGradient(
            r.exactCenterX(), r.bottom.toFloat(), r.width() * 0.6f,
            intArrayOf(Color.argb(46, 40, 28, 16), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, stain)
        stain.shader = RadialGradient(
            r.left + r.width() * 0.08f, r.bottom - r.height() * 0.04f, 28f * density,
            intArrayOf(Color.argb(71, 30, 22, 14), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, stain)
        stain.shader = RadialGradient(
            r.right - r.width() * 0.06f, r.bottom - r.height() * 0.03f, 32f * density,
            intArrayOf(Color.argb(56, 30, 22, 14), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, stain)
        stain.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private var cachedNoise: Bitmap? = null

        fun resetNoiseCache() {
            cachedNoise = null
        }

        private fun noiseTile(): Bitmap {
            cachedNoise?.let { return it }
            val size = 180
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val rnd = java.util.Random(7)
            val pixels = IntArray(size * size)
            for (i in pixels.indices) {
                val v = (128 + rnd.nextInt(17) - 8).coerceIn(0, 255)
                pixels[i] = Color.argb(255, v, v, v)
            }
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            cachedNoise = bmp
            return bmp
        }
    }
}
