package online.seuprojeto.filtrofanta

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.min
import kotlin.math.roundToInt

/** `.how { width: min(92vw, 520px) }` — maxWidth no LinearLayout match_parent é ignorado. */
class HowPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val maxCssPx: Int = resources.getDimensionPixelSize(R.dimen.how_max_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val cap = min(maxCssPx, (screenW * 0.92f).roundToInt())
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (available > 0) min(cap, available) else cap
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }
}
