package app.pulsecast.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import app.pulsecast.data.Card
import java.io.File

/** Draws a post as a 1080×1350 image (the Instagram portrait size) and opens the share sheet. */
object ShareCard {
    private const val W = 1080
    private const val H = 1350
    private const val PAD = 84f

    fun share(context: Context, card: Card) {
        val bitmap = render(card)
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "pulsecast-${card.id}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${card.headline}\n${card.source.url}\n\nVia PulseCast, AI news in 30 seconds")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun render(card: Card): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#0F1113"))
        val accent = Color.parseColor("#B7F36B")
        val width = (W - 2 * PAD).toInt()

        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 34f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.08f }
        c.drawText("${card.topic.uppercase()}  ·  ${card.source.name.uppercase()}", PAD, 150f, small)

        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F2EE"); textSize = 76f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
        var y = 210f
        y = draw(c, card.headline, head, width, y, 5) + 40f

        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9CBC6"); textSize = 40f }
        y = draw(c, card.summary, body, width, y, 6) + 40f

        if (card.whyItMatters.isNotBlank() && y < H - 360) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D2125") }
            val why = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F2EE"); textSize = 36f }
            val layout = layout("Why it matters: ${card.whyItMatters}", why, width - 64, 4)
            c.drawRoundRect(RectF(PAD, y, W - PAD, y + layout.height + 64f), 28f, 28f, box)
            c.save(); c.translate(PAD + 32f, y + 32f); layout.draw(c); c.restore()
        }

        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 44f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        c.drawText("PulseCast", PAD, H - 90f, brand)
        val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9A9C98"); textSize = 30f }
        c.drawText("AI news in 30 seconds", PAD + brand.measureText("PulseCast") + 24f, H - 92f, tag)
        return bmp
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

    private fun draw(c: Canvas, text: String, paint: TextPaint, width: Int, y: Float, maxLines: Int): Float {
        val l = layout(text, paint, width, maxLines)
        c.save(); c.translate(PAD, y); l.draw(c); c.restore()
        return y + l.height
    }
}
