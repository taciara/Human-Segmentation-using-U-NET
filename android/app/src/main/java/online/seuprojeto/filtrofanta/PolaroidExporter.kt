package online.seuprojeto.filtrofanta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object PolaroidExporter {
    /** Monta JPEG final: papel Polaroid + foto 4:5 + logo inteiro (sem cortar o pingente). */
    fun wrapShot(context: Context, inner: Bitmap): Bitmap {
        val pad = 36
        val innerW = 840
        val innerH = (innerW * 5 / 4)
        val cardW = innerW + pad * 2
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.logo_fanta)
        val lw = (innerW * 0.58f).toInt()
        val lh = if (logo != null) maxOf(1, logo.height * lw / maxOf(1, logo.width)) else 220
        val overlap = (lh * 0.32f).toInt()
        val footer = lh - overlap + pad
        val cardH = pad + innerH + footer
        val card = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        c.drawColor(Color.rgb(230, 223, 210))
        val scaled = Bitmap.createScaledBitmap(inner, innerW, innerH, true)
        c.drawBitmap(scaled, pad.toFloat(), pad.toFloat(), null)
        scaled.recycle()
        if (logo != null) {
            val logoR = Bitmap.createScaledBitmap(logo, lw, lh, true)
            val lx = (cardW - lw) / 2f
            val ly = (pad + innerH - overlap).toFloat()
            c.drawBitmap(logoR, lx, ly, Paint(Paint.ANTI_ALIAS_FLAG))
            if (logoR !== logo) logoR.recycle()
        }
        return card
    }
}
