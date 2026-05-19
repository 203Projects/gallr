package com.gallr.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.FileProvider
import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.KtorCoverImageDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private var shareContext: Context? = null

private val coverImageDownloader = KtorCoverImageDownloader.ktor()

fun initShareHandler(context: Context) {
    shareContext = context.applicationContext
}

actual fun createShareHandler(): ShareHandler = object : ShareHandler {
    override fun shareApp() {
        val context = checkNotNull(shareContext) {
            "ShareHandler not initialized. Call initShareHandler(context) in MainActivity.onCreate()."
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out gallr \u2014 https://play.google.com/store/apps/details?id=com.gallr.app",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    override suspend fun shareExhibition(exhibition: Exhibition, lang: AppLanguage) {
        val context = checkNotNull(shareContext) {
            "ShareHandler not initialized. Call initShareHandler(context) in MainActivity.onCreate()."
        }
        val content = ExhibitionStoryShareContent.from(exhibition, lang)
        val imageBytes = content.coverImageUrl?.let { coverImageDownloader.download(it) }
        val bitmap = drawExhibitionStoryCard(content, imageBytes)
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").also { it.mkdirs() }
            File(dir, "gallr-exhibition-${exhibition.id}.png").also { out ->
                out.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}

private fun drawExhibitionStoryCard(
    content: ExhibitionStoryShareContent,
    imageBytes: ByteArray?,
): Bitmap {
    val config = ExhibitionStoryShareConfig
    val bitmap = Bitmap.createBitmap(config.cardWidthPx, config.cardHeightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(
        0f,
        0f,
        config.cardWidthPx.toFloat(),
        config.cardHeightPx.toFloat(),
        intArrayOf(Color.rgb(28, 28, 28), Color.BLACK),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, config.cardWidthPx.toFloat(), config.cardHeightPx.toFloat(), paint)
    paint.shader = null

    val imageLeft = config.sideMarginPx
    val imageTop = config.safeTopPx + 140
    val imageRect = RectF(
        imageLeft.toFloat(),
        imageTop.toFloat(),
        (imageLeft + config.imageSizePx).toFloat(),
        (imageTop + config.imageSizePx).toFloat(),
    )

    paint.color = Color.rgb(10, 10, 10)
    paint.style = Paint.Style.FILL
    canvas.drawRect(imageRect, paint)

    imageBytes
        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        ?.let { cover ->
            val src = centeredSquareCrop(cover)
            canvas.drawBitmap(cover, src, imageRect, null)
            cover.recycle()
        }

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1f
    paint.color = Color.argb(51, 255, 255, 255)
    canvas.drawRect(imageRect, paint)

    val textX = config.sideMarginPx.toFloat()
    var textY = imageRect.bottom + config.textTopGapPx + 40
    paint.style = Paint.Style.FILL
    paint.shader = null
    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)

    paint.color = Color.WHITE
    paint.textSize = 44f
    drawMultilineText(canvas, content.title, textX, textY, paint, maxLines = 2, lineHeight = 56f, maxWidth = config.imageSizePx)
    textY += if (content.title.length > 22) 112f else 56f

    paint.color = Color.argb(128, 255, 255, 255)
    paint.textSize = 28f
    canvas.drawText(content.venue.take(38), textX, textY + 24f, paint)

    paint.color = Color.argb(31, 255, 255, 255)
    paint.strokeWidth = 1f
    canvas.drawLine(textX, textY + 64f, textX + 360f, textY + 64f, paint)

    paint.style = Paint.Style.FILL
    paint.color = Color.argb(115, 255, 255, 255)
    paint.textSize = 26f
    canvas.drawText(content.dateRange, textX, textY + 108f, paint)

    paint.color = Color.argb(115, 255, 255, 255)
    paint.textSize = 34f
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText("gallr", config.cardWidthPx / 2f, (config.cardHeightPx - config.safeBottomPx - 48).toFloat(), paint)
    paint.textAlign = Paint.Align.LEFT

    return bitmap
}

private fun centeredSquareCrop(bitmap: Bitmap): Rect {
    val size = minOf(bitmap.width, bitmap.height)
    val left = (bitmap.width - size) / 2
    val top = (bitmap.height - size) / 2
    return Rect(left, top, left + size, top + size)
}

private fun drawMultilineText(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
    maxLines: Int,
    lineHeight: Float,
    maxWidth: Int,
) {
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
            current = candidate
        } else {
            lines += current
            current = word
        }
        if (lines.size == maxLines) break
    }
    if (lines.size < maxLines && current.isNotEmpty()) lines += current
    lines.take(maxLines).forEachIndexed { index, line ->
        val suffix = if (index == maxLines - 1 && lines.size == maxLines && words.joinToString(" ").length > line.length) "…" else ""
        canvas.drawText((line + suffix).take(42), x, y + index * lineHeight, paint)
    }
}
