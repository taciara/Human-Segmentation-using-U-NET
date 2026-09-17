package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

class SegmentationEngine(context: Context) {
    private val segmenter: ImageSegmenter

    data class PersonMask(val data: FloatArray, val width: Int, val height: Int)

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("selfie_segmenter.tflite")
            .build()
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .build()
        segmenter = ImageSegmenter.createFromOptions(context, options)
    }

    fun personMask(source: Bitmap): PersonMask {
        val mpImage = BitmapImageBuilder(source).build()
        val result = segmenter.segment(mpImage)
        val masks = result.confidenceMasks().orElse(null)
        require(!masks.isNullOrEmpty()) { "Segmentação sem máscara" }
        val maskImage = masks[0]
        val w = maskImage.width
        val h = maskImage.height
        val buffer = ByteBufferExtractor.extract(maskImage).duplicate()
        buffer.order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        val arr = FloatArray(w * h)
        floats.get(arr)
        return PersonMask(arr, w, h)
    }

    fun close() {
        segmenter.close()
    }

    companion object {
        fun refineAlpha(flat: FloatArray, w: Int, h: Int): FloatArray {
            val out = FloatArray(flat.size)
            for (i in flat.indices) {
                var v = flat[i].coerceIn(0f, 1f)
                if (v < 0.05f) v = 0f
                out[i] = v
            }
            val tmp = out.copyOf()
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    var sum = 0f
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            sum += tmp[(y + dy) * w + (x + dx)]
                        }
                    }
                    out[y * w + x] = sum / 9f
                }
            }
            for (i in out.indices) {
                out[i] = ((out[i] - 0.08f) / 0.84f).coerceIn(0f, 1f)
            }
            return out
        }

        fun sampleAlpha(mask: FloatArray, mw: Int, mh: Int, x: Int, y: Int, tw: Int, th: Int): Float {
            val sx = (x.toFloat() / tw * mw).toInt().coerceIn(0, mw - 1)
            val sy = (y.toFloat() / th * mh).toInt().coerceIn(0, mh - 1)
            return mask[sy * mw + sx]
        }

        fun toGray(b: Int, g: Int, r: Int): Int {
            val y = (0.114f * b + 0.587f * g + 0.299f * r).toInt().coerceIn(0, 255)
            return Color.rgb(y, y, y)
        }
    }
}
