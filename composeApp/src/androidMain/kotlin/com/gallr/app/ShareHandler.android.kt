package com.gallr.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.graphics.PathParser
import com.gallr.app.share.ExhibitionStoryCardPalette
import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.app.share.StoryCardImage
import com.gallr.app.share.brandGroupStartX
import com.gallr.app.share.exhibitionStoryTextLayout
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.KtorCoverImageDownloader
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private var shareContext: Context? = null
private var shareLauncher: ActivityResultLauncher<Intent>? = null
private var sheetCompletion: (() -> Unit)? = null
private const val SHARE_CACHE_FILE_LIMIT = 4

private val shareHandlerLog = AppLog.tagged("ShareHandler")

fun initShareHandler(activity: ComponentActivity) {
    shareContext = activity.applicationContext
    sheetCompletion?.invoke()
    sheetCompletion = null
    shareLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val completion = sheetCompletion
            sheetCompletion = null
            completion?.invoke()
        }
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

        override suspend fun renderExhibitionStoryCard(
            exhibition: Exhibition,
            lang: AppLanguage,
            palette: ExhibitionStoryCardPalette,
        ): StoryCardImage {
            val context = checkNotNull(shareContext)
            val content = ExhibitionStoryShareContent.from(exhibition, lang)
            val imageBytes = content.coverImageUrl?.let { downloadCoverImage(it) }
            return withContext(Dispatchers.IO) {
                val bitmap = drawExhibitionStoryCard(content, imageBytes, palette)
                val png =
                    try {
                        ByteArrayOutputStream().use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                            stream.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                val dir = File(context.cacheDir, "share").also { it.mkdirs() }
                val file = File.createTempFile("gallr-exhibition-", ".png", dir)
                file.writeBytes(png)
                pruneShareCache(dir, file)
                StoryCardImage(png, content.shareDescriptor, file.absolutePath)
            }
        }

        override fun shareStoryCard(
            card: StoryCardImage,
            onDismiss: () -> Unit,
            onPresented: () -> Unit,
        ) {
            if (sheetCompletion != null) {
                onDismiss()
                return
            }
            val context = checkNotNull(shareContext)
            val launcher = checkNotNull(shareLauncher)
            val file = File(checkNotNull(card.filePath))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            try {
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, card.shareDescriptor)
                        putExtra(Intent.EXTRA_TITLE, card.shareDescriptor)
                        clipData = ClipData.newUri(context.contentResolver, card.shareDescriptor, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser =
                    Intent.createChooser(intent, card.shareDescriptor).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                sheetCompletion = onDismiss
                launcher.launch(chooser)
                onPresented()
            } catch (_: ActivityNotFoundException) {
                sheetCompletion = null
                onDismiss()
            } catch (error: Exception) {
                sheetCompletion = null
                throw error
            }
        }
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
    palette: ExhibitionStoryCardPalette,
): Bitmap {
    val config = ExhibitionStoryShareConfig
    val bitmap = Bitmap.createBitmap(config.CARD_WIDTH_PX, config.CARD_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = palette.background
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

    paint.color = palette.placeholder
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
    paint.color = palette.frame
    canvas.drawRect(imageRect, paint)

    val textX = config.SIDE_MARGIN_PX.toFloat()
    paint.style = Paint.Style.FILL
    paint.typeface =
        android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)

    val titlePaint =
        Paint(paint).apply {
            color = palette.title
            textSize = config.TITLE_FONT_SIZE_PX.toFloat()
        }
    val venuePaint =
        Paint(paint).apply {
            color = palette.secondary
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

    paint.color = palette.divider
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
            color = palette.secondary
            textSize = config.DATE_FONT_SIZE_PX.toFloat()
        }
    val dateBaseline = config.DATE_TOP_PX - datePaint.fontMetrics.ascent
    canvas.drawText(content.dateRange, textX, dateBaseline, datePaint)

    val brandText = "gallr"
    val markSizePx = config.BRAND_MARK_SIZE_PX.toFloat()
    val gapPx = config.BRAND_GAP_PX.toFloat()

    paint.color = palette.secondary
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
