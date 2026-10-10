package com.gallr.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.graphics.PathParser
import com.gallr.app.share.ExhibitionQr
import com.gallr.app.share.ExhibitionStoryCardPalette
import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.app.share.PosterPalette
import com.gallr.app.share.StoryCardColors
import com.gallr.app.share.StoryCardImage
import com.gallr.app.share.exhibitionStoryTextLayout
import com.gallr.app.share.qrModulePx
import com.gallr.app.share.storyCardColors
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
    theme: ExhibitionStoryCardPalette,
): Bitmap {
    val config = ExhibitionStoryShareConfig
    val width = config.CARD_WIDTH_PX.toFloat()
    val height = config.CARD_HEIGHT_PX.toFloat()
    val cover = imageBytes?.let { decodeCoverBitmap(it, config.IMAGE_SIZE_PX) }
    val coverCrop = cover?.let(::centeredSquareCrop)
    val poster = cover?.let { posterPalette(it, checkNotNull(coverCrop)) } ?: PosterPalette.FALLBACK
    val colors = storyCardColors(theme, poster)

    val bitmap = Bitmap.createBitmap(config.CARD_WIDTH_PX, config.CARD_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(0f, 0f, 0f, height, colors.paperTop, colors.paperBottom, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
    paint.shader = null

    val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val textX = config.SIDE_MARGIN_PX.toFloat()

    content.status?.let { status ->
        drawStatusChip(
            canvas,
            status.text,
            colors,
            colors.statusDot(status.emphasized),
            medium,
        )
    }

    val imageRect =
        RectF(
            textX,
            config.IMAGE_TOP_PX.toFloat(),
            textX + config.IMAGE_SIZE_PX,
            (config.IMAGE_TOP_PX + config.IMAGE_SIZE_PX).toFloat(),
        )
    paint.color = colors.placeholder
    paint.setShadowLayer(
        config.IMAGE_SHADOW_BLUR_PX.toFloat(),
        0f,
        config.IMAGE_SHADOW_OFFSET_Y_PX.toFloat(),
        0x2E000000,
    )
    canvas.drawRect(imageRect, paint)
    paint.clearShadowLayer()
    if (cover != null) {
        canvas.drawBitmap(cover, coverCrop, imageRect, Paint(Paint.FILTER_BITMAP_FLAG))
        cover.recycle()
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1f
    paint.color = colors.frame
    canvas.drawRect(imageRect, paint)
    paint.style = Paint.Style.FILL

    val titlePaint = textPaint(colors.title, config.TITLE_FONT_SIZE_PX, medium)
    val venuePaint = textPaint(colors.secondary, config.VENUE_FONT_SIZE_PX, medium)
    val textLayout =
        exhibitionStoryTextLayout(
            content = content,
            measureTitle = titlePaint::measureText,
            measureVenue = venuePaint::measureText,
        )
    val titleBaseline = config.TITLE_TOP_PX - titlePaint.fontMetrics.ascent
    textLayout.titleLines.forEachIndexed { index, line ->
        canvas.drawText(line, textX, titleBaseline + index * config.TITLE_LINE_HEIGHT_PX, titlePaint)
    }
    canvas.drawText(textLayout.venue, textX, config.VENUE_TOP_PX - venuePaint.fontMetrics.ascent, venuePaint)

    paint.color = colors.divider
    canvas.drawRect(
        textX,
        config.DIVIDER_TOP_PX.toFloat(),
        textX + config.DIVIDER_WIDTH_PX,
        config.DIVIDER_TOP_PX + 2f,
        paint,
    )

    val datePaint = textPaint(colors.title, config.DATE_FONT_SIZE_PX, regular)
    canvas.drawText(content.dateRange, textX, config.DATE_TOP_PX - datePaint.fontMetrics.ascent, datePaint)

    val detailPaint = textPaint(colors.secondary, config.DETAIL_FONT_SIZE_PX, regular)
    content.detailLine?.let {
        canvas.drawText(it, textX, config.DETAIL_TOP_PX - detailPaint.fontMetrics.ascent, detailPaint)
    }

    val swatchTop = config.DETAIL_TOP_PX + (config.DETAIL_HEIGHT_PX - config.SWATCH_SIZE_PX) / 2f
    var swatchX =
        width - textX - poster.qrColors.size * (config.SWATCH_SIZE_PX + config.SWATCH_GAP_PX) + config.SWATCH_GAP_PX
    poster.qrColors.forEach { swatch ->
        paint.color = swatch
        canvas.drawRect(swatchX, swatchTop, swatchX + config.SWATCH_SIZE_PX, swatchTop + config.SWATCH_SIZE_PX, paint)
        swatchX += config.SWATCH_SIZE_PX + config.SWATCH_GAP_PX
    }

    content.webUrl?.let { url -> drawQr(canvas, ExhibitionQr.encode(url, poster.qrColors), colors.qrTile) }

    drawBrand(canvas, textX, colors.title, regular)
    val captionPaint = textPaint(colors.secondary, config.CAPTION_FONT_SIZE_PX, regular)
    val captionBaseline = config.CAPTION_TOP_PX - captionPaint.fontMetrics.ascent
    canvas.drawText(content.qrCaption, textX, captionBaseline, captionPaint)
    canvas.drawText(config.CAPTION_URL_TEXT, textX, captionBaseline + config.CAPTION_LINE_HEIGHT_PX, captionPaint)

    return bitmap
}

private fun textPaint(
    color: Int,
    sizePx: Int,
    typeface: Typeface,
) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = sizePx.toFloat()
    this.typeface = typeface
}

private fun posterPalette(
    cover: Bitmap,
    crop: Rect,
): PosterPalette {
    val size = PosterPalette.SAMPLE_SIZE
    val sample = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(sample).drawBitmap(cover, crop, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
    val argb = IntArray(size * size)
    sample.getPixels(argb, 0, size, 0, 0, size, size)
    sample.recycle()
    val rgba = ByteArray(argb.size * 4)
    argb.forEachIndexed { index, pixel ->
        rgba[index * 4] = (pixel shr 16).toByte()
        rgba[index * 4 + 1] = (pixel shr 8).toByte()
        rgba[index * 4 + 2] = pixel.toByte()
        rgba[index * 4 + 3] = (pixel ushr 24).toByte()
    }
    return PosterPalette.fromRgba(rgba)
}

private fun drawStatusChip(
    canvas: Canvas,
    text: String,
    colors: StoryCardColors,
    dotColor: Int,
    typeface: Typeface,
) {
    val config = ExhibitionStoryShareConfig
    val textPaint = textPaint(colors.title, config.STATUS_FONT_SIZE_PX, typeface)
    val dotSpace = config.STATUS_DOT_RADIUS_PX * 2 + config.STATUS_DOT_GAP_PX
    val chipWidth = config.STATUS_PADDING_PX * 2 + dotSpace + textPaint.measureText(text)
    val left = config.SIDE_MARGIN_PX.toFloat()
    val top = config.STATUS_TOP_PX.toFloat()
    val radius = config.STATUS_HEIGHT_PX / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.statusBackground }
    canvas.drawRoundRect(RectF(left, top, left + chipWidth, top + config.STATUS_HEIGHT_PX), radius, radius, paint)
    paint.color = dotColor
    canvas.drawCircle(
        left + config.STATUS_PADDING_PX + config.STATUS_DOT_RADIUS_PX,
        top + radius,
        config.STATUS_DOT_RADIUS_PX.toFloat(),
        paint,
    )
    val metrics = textPaint.fontMetrics
    val baseline = top + radius - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText(text, left + config.STATUS_PADDING_PX + dotSpace, baseline, textPaint)
}

private fun drawQr(
    canvas: Canvas,
    qr: ExhibitionQr,
    tileColor: Int,
) {
    val config = ExhibitionStoryShareConfig
    val modulePx = qrModulePx(qr.size)
    val box = qr.size * modulePx
    val left = config.CARD_WIDTH_PX - config.SIDE_MARGIN_PX - box
    val top = config.CARD_HEIGHT_PX - config.SAFE_BOTTOM_PX - box
    val radius = config.QR_CORNER_RADIUS_PX.toFloat()
    val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileColor }
    canvas.drawRoundRect(
        RectF(left.toFloat(), top.toFloat(), (left + box).toFloat(), (top + box).toFloat()),
        radius,
        radius,
        tile,
    )
    // No anti-aliasing: whole-pixel modules keep edges crisp for scanners.
    val module = Paint()
    for (row in 0 until qr.size) {
        for (col in 0 until qr.size) {
            val color = qr.colorAt(row, col) ?: continue
            module.color = color
            val x = left + col * modulePx
            val y = top + row * modulePx
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + modulePx).toFloat(), (y + modulePx).toFloat(), module)
        }
    }
}

private fun drawBrand(
    canvas: Canvas,
    left: Float,
    color: Int,
    typeface: Typeface,
) {
    val config = ExhibitionStoryShareConfig
    val markSizePx = config.BRAND_MARK_SIZE_PX.toFloat()
    val paint = textPaint(color, config.BRAND_FONT_SIZE_PX, typeface)
    val baselineY = config.BRAND_TOP_PX - paint.fontMetrics.ascent
    val capHeight = config.BRAND_FONT_SIZE_PX * 0.72f
    val markTopY = baselineY - capHeight - (markSizePx - capHeight) / 2f
    val markPath =
        PathParser.createPathFromPathData(ARCH_PIN_PATH_DATA).apply {
            fillType = android.graphics.Path.FillType.EVEN_ODD
            transform(
                Matrix().apply {
                    postScale(markSizePx / ARCH_PIN_VIEWPORT, markSizePx / ARCH_PIN_VIEWPORT)
                    postTranslate(left, markTopY)
                },
            )
        }
    canvas.drawPath(markPath, paint)
    canvas.drawText("gallr", left + markSizePx + config.BRAND_GAP_PX, baselineY, paint)
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
