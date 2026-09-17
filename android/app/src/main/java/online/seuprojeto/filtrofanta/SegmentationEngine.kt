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
            val a = FloatArray(flat.size)
            for (i in flat.indices) {
                var v = flat[i].coerceIn(0f, 1f)
                if (v < 0.05f) v = 0f
                a[i] = v
            }
            val dil = FloatArray(a.size)
            max3(a, dil, w, h)
            val clo = FloatArray(a.size)
            min3(dil, clo, w, h)
            val blur = FloatArray(clo.size)
            box3(clo, blur, w, h)
            box3(blur, clo, w, h)
            for (i in clo.indices) {
                clo[i] = ((clo[i] - 0.08f) / 0.84f).coerceIn(0f, 1f)
            }
            return clo
        }

        private fun max3(src: FloatArray, dst: FloatArray, w: Int, h: Int) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var m = 0f
                    for (dy in -1..1) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -1..1) {
                            val xx = (x + dx).coerceIn(0, w - 1)
                            m = maxOf(m, src[yy * w + xx])
                        }
                    }
                    dst[y * w + x] = m
                }
            }
        }

        private fun min3(src: FloatArray, dst: FloatArray, w: Int, h: Int) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var m = 1f
                    for (dy in -1..1) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -1..1) {
                            val xx = (x + dx).coerceIn(0, w - 1)
                            m = minOf(m, src[yy * w + xx])
                        }
                    }
                    dst[y * w + x] = m
                }
            }
        }

        private fun box3(src: FloatArray, dst: FloatArray, w: Int, h: Int) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (dy in -1..1) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -1..1) {
                            val xx = (x + dx).coerceIn(0, w - 1)
                            sum += src[yy * w + xx]
                        }
                    }
                    dst[y * w + x] = sum / 9f
                }
            }
        }

        fun sampleBilinear(mask: FloatArray, mw: Int, mh: Int, fx: Float, fy: Float): Float {
            val x = fx.coerceIn(0f, mw - 1f)
            val y = fy.coerceIn(0f, mh - 1f)
            val x0 = x.toInt()
            val y0 = y.toInt()
            val x1 = minOf(x0 + 1, mw - 1)
            val y1 = minOf(y0 + 1, mh - 1)
            val tx = x - x0
            val ty = y - y0
            val v00 = mask[y0 * mw + x0]
            val v10 = mask[y0 * mw + x1]
            val v01 = mask[y1 * mw + x0]
            val v11 = mask[y1 * mw + x1]
            val a = v00 + (v10 - v00) * tx
            val b = v01 + (v11 - v01) * tx
            return a + (b - a) * ty
        }

        fun toGray(b: Int, g: Int, r: Int): Int {
            val y = (0.114f * b + 0.587f * g + 0.299f * r).toInt().coerceIn(0, 255)
            return Color.rgb(y, y, y)
        }
    }
}
