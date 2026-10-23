package online.seuprojeto.filtrofanta

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Viewfinder `.viewfinder { aspect-ratio: 4 / 5 }` */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).let { if (it > 0) it else suggestedMinimumWidth }
        var height = if (width > 0) width * 5 / 4 else 0
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (heightMode != MeasureSpec.UNSPECIFIED && maxH > 0 && height > maxH) {
            height = maxH
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
