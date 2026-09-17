package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * `.drip-bottom` (bg_orange cover) + `.drip-wrap::after` (bg_2):
 * background-repeat: no-repeat;
 * background-size: auto;
 * background-position: center top;
 */
class DripBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    init {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.bg_orange)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        })
        addView(DripOverlayView(context))
    }

    private class DripOverlayView(context: Context) : ImageView(context) {
        private val drip: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_drip_overlay)

        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setWillNotDraw(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = drip ?: return
            val iw = d.intrinsicWidth.coerceAtLeast(1)
            val ih = d.intrinsicHeight.coerceAtLeast(1)
            val vw = width.toFloat()
            val x = (vw - iw) / 2f
            val y = 0f
            canvas.save()
            canvas.translate(x, y)
            d.setBounds(0, 0, iw, ih)
            d.draw(canvas)
            canvas.restore()
        }
    }
}
