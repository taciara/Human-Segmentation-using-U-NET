package online.seuprojeto.filtrofanta

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/** Viewfinder `.viewfinder { aspect-ratio: 4 / 5 }` */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var height = if (width > 0) width * 5 / 4 else 0
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (heightMode != MeasureSpec.UNSPECIFIED && maxHeight > 0) {
            height = min(height, maxHeight)
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
