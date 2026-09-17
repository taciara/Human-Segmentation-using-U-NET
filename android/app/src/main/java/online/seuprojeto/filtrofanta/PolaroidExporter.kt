package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
object PolaroidExporter {
    /** Monta JPEG final: papel Polaroid + foto 4:5 + logo (como no photobooth web). */
    fun wrapShot(context: Context, inner: Bitmap): Bitmap {
        val pad = 36
        val footer = 220
        val innerW = 840
        val innerH = (innerW * 5 / 4)
        val cardW = innerW + pad * 2
        val cardH = pad + innerH + footer
        val card = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        c.drawColor(Color.rgb(230, 223, 210))
        val scaled = Bitmap.createScaledBitmap(inner, innerW, innerH, true)
        c.drawBitmap(scaled, pad.toFloat(), pad.toFloat(), null)
        scaled.recycle()
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.logo_fanta)
        if (logo != null) {
            val lw = (innerW * 0.58f).toInt()
            val lh = maxOf(1, logo.height * lw / maxOf(1, logo.width))
            val logoR = Bitmap.createScaledBitmap(logo, lw, lh, true)
            val lx = (cardW - lw) / 2f
            val ly = pad + innerH + (footer - lh) / 2f
            c.drawBitmap(logoR, lx, ly, Paint(Paint.ANTI_ALIAS_FLAG))
            if (logoR !== logo) logoR.recycle()
        }
        return card
    }
}
