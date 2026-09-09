package com.gallr.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.FileProvider
import androidx.core.graphics.PathParser
import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.app.share.brandGroupStartX
import com.gallr.app.share.exhibitionStoryTextLayout
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.KtorCoverImageDownloader
import com.gallr.shared.observability.AppLog
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private var shareContext: Context? = null
private const val SHARE_CACHE_FILE_LIMIT = 4

private val shareHandlerLog = AppLog.tagged("ShareHandler")

fun initShareHandler(context: Context) {
    shareContext = context.applicationContext
}

actual fun createShareHandler(): ShareHandler =
    object : ShareHandler {
        override fun shareApp() {
            val context =
                checkNotNull(shareContext) {
                    "ShareHandler not initialized. Call initShareHandler(context) in MainActivity.onCreate()."
                }
            runCatching {
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Check out gallr \u2014 https://play.google.com/store/apps/details?id=com.gallr.app",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                val chooser =
                    Intent.createChooser(intent, null).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(chooser)
            }.onFailure { shareHandlerLog.warn("share_app", it) }
        }

        override suspend fun shareExhibition(
            exhibition: Exhibition,
            lang: AppLanguage,
        ): Result<Unit> =
            runSuspendCatching {
                val context =
                    checkNotNull(shareContext) {
                        "ShareHandler not initialized. Call initShareHandler(context) in MainActivity.onCreate()."
                    }
                val content = ExhibitionStoryShareContent.from(exhibition, lang)
                val imageBytes = content.coverImageUrl?.let { downloadCoverImage(it) }
                val bitmap = drawExhibitionStoryCard(content, imageBytes)
                // exhibition.id is a Supabase UUID, but guard the filename anyway so a
                // malformed id can't traverse paths or make getUriForFile throw.
                val safeId = exhibition.id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
                val file =
                    try {
                        withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "share").also { it.mkdirs() }
                            File(dir, "gallr-exhibition-$safeId.png").also { out ->
                                out.outputStream().use { stream ->
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                        "Story card PNG compression failed"
                                    }
                                }
                                pruneShareCache(dir, out)
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, content.shareDescriptor)
                        putExtra(Intent.EXTRA_TITLE, content.shareDescriptor)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                val chooser =
                    Intent.createChooser(intent, content.shareDescriptor).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(chooser)
            }.onFailure { shareHandlerLog.warn("share_exhibition", it) }
    }

private suspend fun downloadCoverImage(url: String): ByteArray? {
    val downloader = KtorCoverImageDownloader.ktor()
    return try {
        downloader.download(url)
    } finally {
        downloader.close()
    }
}

private fun drawExhibitionStoryCard(
    content: ExhibitionStoryShareContent,
    imageBytes: ByteArray?,
): Bitmap {
    val config = ExhibitionStoryShareConfig
    val bitmap = Bitmap.createBitmap(config.CARD_WIDTH_PX, config.CARD_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = Color.BLACK
    canvas.drawRect(0f, 0f, config.CARD_WIDTH_PX.toFloat(), config.CARD_HEIGHT_PX.toFloat(), paint)

    val imageLeft = config.SIDE_MARGIN_PX
    val imageTop = config.IMAGE_TOP_PX
    val imageRect =
        RectF(
            imageLeft.toFloat(),
            imageTop.toFloat(),
            (imageLeft + config.IMAGE_SIZE_PX).toFloat(),
            (imageTop + config.IMAGE_SIZE_PX).toFloat(),
        )

    paint.color = Color.rgb(10, 10, 10)
    paint.style = Paint.Style.FILL
    canvas.drawRect(imageRect, paint)

    imageBytes
        ?.let { decodeCoverBitmap(it, config.IMAGE_SIZE_PX) }
        ?.let { cover ->
            val src = centeredSquareCrop(cover)
            canvas.drawBitmap(cover, src, imageRect, null)
            cover.recycle()
        }

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1f
    paint.color = Color.argb(51, 255, 255, 255)
    canvas.drawRect(imageRect, paint)

    val textX = config.SIDE_MARGIN_PX.toFloat()
    paint.style = Paint.Style.FILL
    paint.typeface =
        android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)

    val titlePaint =
        Paint(paint).apply {
            color = Color.WHITE
            textSize = config.TITLE_FONT_SIZE_PX.toFloat()
        }
    val venuePaint =
        Paint(paint).apply {
            color = Color.argb(128, 255, 255, 255)
            textSize = config.VENUE_FONT_SIZE_PX.toFloat()
        }
    val textLayout =
        exhibitionStoryTextLayout(
            content = content,
            measureTitle = titlePaint::measureText,
            measureVenue = venuePaint::measureText,
        )
    val titleBaseline = config.TITLE_TOP_PX - titlePaint.fontMetrics.ascent
    textLayout.titleLines.forEachIndexed { index, line ->
        canvas.drawText(
            line,
            textX,
            titleBaseline + index * config.TITLE_LINE_HEIGHT_PX,
            titlePaint,
        )
    }

    val venueBaseline = config.VENUE_TOP_PX - venuePaint.fontMetrics.ascent
    canvas.drawText(textLayout.venue, textX, venueBaseline, venuePaint)

    paint.color = Color.argb(31, 255, 255, 255)
    paint.strokeWidth = 1f
    canvas.drawLine(
        textX,
        config.DIVIDER_TOP_PX.toFloat(),
        textX + config.DIVIDER_WIDTH_PX,
        config.DIVIDER_TOP_PX.toFloat(),
        paint,
    )

    val datePaint =
        Paint(paint).apply {
            style = Paint.Style.FILL
            color = Color.argb(115, 255, 255, 255)
            textSize = config.DATE_FONT_SIZE_PX.toFloat()
        }
    val dateBaseline = config.DATE_TOP_PX - datePaint.fontMetrics.ascent
    canvas.drawText(content.dateRange, textX, dateBaseline, datePaint)

    val brandText = "gallr"
    val markSizePx = config.BRAND_MARK_SIZE_PX.toFloat()
    val gapPx = config.BRAND_GAP_PX.toFloat()

    paint.color = Color.argb(115, 255, 255, 255)
    paint.textSize = config.BRAND_FONT_SIZE_PX.toFloat()
    paint.textAlign = Paint.Align.LEFT
    val baselineY = config.BRAND_TOP_PX - paint.fontMetrics.ascent
    val textWidth = paint.measureText(brandText)
    val startX =
        brandGroupStartX(
            cardWidth = config.CARD_WIDTH_PX,
            markSize = markSizePx,
            gap = gapPx,
            textWidth = textWidth,
        )

    val markPath =
        PathParser.createPathFromPathData(ARCH_PIN_PATH_DATA).apply {
            fillType = android.graphics.Path.FillType.EVEN_ODD
        }
    val capHeight = 34f * 0.72f
    val markTopY = baselineY - capHeight - (markSizePx - capHeight) / 2f
    val matrix =
        Matrix().apply {
            postScale(markSizePx / ARCH_PIN_VIEWPORT, markSizePx / ARCH_PIN_VIEWPORT)
            postTranslate(startX, markTopY)
        }
    markPath.transform(matrix)
    paint.style = Paint.Style.FILL
    canvas.drawPath(markPath, paint)

    canvas.drawText(brandText, startX + markSizePx + gapPx, baselineY, paint)
    paint.textAlign = Paint.Align.LEFT

    return bitmap
}

private const val ARCH_PIN_VIEWPORT = 100f

private const val ARCH_PIN_PATH_DATA =
    "M 50 90 C 30 78 14 64 14 48 A 36 36 0 0 1 86 48 C 86 64 70 78 50 90 Z " +
        "M 50 82 C 35 71 24 60 24 48 A 26 26 0 0 1 76 48 C 76 60 65 71 50 82 Z"

private fun centeredSquareCrop(bitmap: Bitmap): Rect {
    val size = minOf(bitmap.width, bitmap.height)
    val left = (bitmap.width - size) / 2
    val top = (bitmap.height - size) / 2
    return Rect(left, top, left + size, top + size)
}

private fun decodeCoverBitmap(
    imageBytes: ByteArray,
    targetSize: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
        bounds.outHeight / (sampleSize * 2) >= targetSize
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
}

private fun pruneShareCache(
    directory: File,
    currentFile: File,
) {
    directory
        .listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
        ?.sortedWith(compareByDescending<File> { it == currentFile }.thenByDescending { it.lastModified() })
        ?.drop(SHARE_CACHE_FILE_LIMIT)
        ?.forEach { staleFile -> staleFile.delete() }
}
