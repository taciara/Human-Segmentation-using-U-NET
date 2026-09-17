package online.seuprojeto.filtrofanta

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.min
import kotlin.math.roundToInt

class PolaroidFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = VERTICAL
        background = PolaroidPaperDrawable(context)
        val pad = (14f * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, 0)
        clipToPadding = false
        clipChildren = false
        clipToOutline = false
        elevation = 12f * resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val screenW = resources.displayMetrics.widthPixels
        val target = (screenW * WIDTH_FRACTION).roundToInt()
        val width = if (available > 0) min(target, available) else target
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    companion object {
        private const val WIDTH_FRACTION = 0.75f
    }
}
