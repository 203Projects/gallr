package com.gallr.app

import com.gallr.app.share.ExhibitionQr
import com.gallr.app.share.ExhibitionStoryCardPalette
import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.app.share.PosterPalette
import com.gallr.app.share.StoryCardColors
import com.gallr.app.share.StoryCardImage
import com.gallr.app.share.exhibitionStoryTextLayout
import com.gallr.app.share.mixArgb
import com.gallr.app.share.qrModulePx
import com.gallr.app.share.storyCardColors
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.KtorCoverImageDownloader
import com.gallr.shared.observability.AppLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSItemProvider
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.LinkPresentation.LPLinkMetadata
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.kCAFillRuleEvenOdd
import platform.QuartzCore.kCAFilterNearest
import platform.UIKit.NSLineBreakByClipping
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.UIActivityItemSourceProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIFontWeightSemibold
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UIRectFill
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

private const val APP_STORE_URL = "https://apps.apple.com/app/gallr/id6760855059"

private val shareHandlerLog = AppLog.tagged("ShareHandler")
private var activeStoryFile: String? = null

actual fun createShareHandler(): ShareHandler =
    object : ShareHandler {
        override fun shareApp() {
            val text = "Check out gallr \u2014 $APP_STORE_URL"
            dispatch_async(dispatch_get_main_queue()) {
                val controller =
                    UIActivityViewController(
                        activityItems = listOf(text),
                        applicationActivities = null,
                    )
                presentActivityController(controller)
                    .onFailure { shareHandlerLog.warn("share_app", it) }
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        override suspend fun renderExhibitionStoryCard(
            exhibition: Exhibition,
            lang: AppLanguage,
            palette: ExhibitionStoryCardPalette,
        ): StoryCardImage {
            val content = ExhibitionStoryShareContent.from(exhibition, lang)
            val imageBytes = content.coverImageUrl?.let { downloadCoverImage(it) }
            // UIKit (UIView/UIGraphics/present) must run on the main thread; the
            // download above suspends and may resume off-main, so re-confine here.
            return withContext(Dispatchers.Main) {
                val image = checkNotNull(drawExhibitionStoryCard(content, imageBytes, palette))
                val data = checkNotNull(UIImagePNGRepresentation(image))
                val png = ByteArray(data.length.toInt())
                check(png.isNotEmpty())
                png.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
                val path = NSTemporaryDirectory() + "gallr-story-" + NSUUID().UUIDString + ".png"
                check(data.writeToFile(path, atomically = true))
                pruneStoryFiles(path)
                StoryCardImage(png, content.shareDescriptor, path)
            }
        }

        @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
        override fun shareStoryCard(
            card: StoryCardImage,
            onDismiss: () -> Unit,
            onPresented: () -> Unit,
        ) {
            // Preview actions originate on the main dispatcher. Do not enqueue a later
            // presentation that could outlive this screen.
            check(platform.Foundation.NSThread.isMainThread)
            val presenter = checkNotNull(topmostViewController())
            if (presenter is UIActivityViewController) {
                onDismiss()
                return
            }
            val path = checkNotNull(card.filePath)
            val source =
                StoryCardItemSource(NSURL.fileURLWithPath(path), card.shareDescriptor)
            var finished = false
            val finish = {
                if (!finished) {
                    finished = true
                    if (activeStoryFile == path) activeStoryFile = null
                    onDismiss()
                }
            }
            val controller = StoryCardActivityController(listOf(source), finish)
            controller.completionWithItemsHandler = { _, _, _, error ->
                if (error != null) shareHandlerLog.warn("share_exhibition")
                finish()
            }
            controller.anchorPopover(presenter)
            activeStoryFile = path
            presenter.presentViewController(controller, animated = true, completion = null)
            onPresented()
        }
    }

private class StoryCardActivityController(
    items: List<Any>,
    private val onClosed: () -> Unit,
) : UIActivityViewController(activityItems = items, applicationActivities = null) {
    // Compact-sheet outside dismissal does not consistently call the activity
    // completion handler. Observe actual controller dismissal as well.
    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (presentingViewController == null || isBeingDismissed()) onClosed()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class StoryCardItemSource(
    private val file: NSURL,
    private val descriptor: String,
) : NSObject(),
    UIActivityItemSourceProtocol {
    override fun activityViewControllerPlaceholderItem(activityViewController: UIActivityViewController): Any = file

    override fun activityViewController(
        activityViewController: UIActivityViewController,
        itemForActivityType: String?,
    ): Any = file

    override fun activityViewControllerLinkMetadata(
        activityViewController: UIActivityViewController,
    ): objcnames.classes.LPLinkMetadata? =
        LPLinkMetadata().apply {
            title = descriptor
            val provider = NSItemProvider(contentsOfURL = file)
            imageProvider = provider
            iconProvider = provider
        } as objcnames.classes.LPLinkMetadata
}

@OptIn(ExperimentalForeignApi::class)
private fun pruneStoryFiles(currentPath: String) {
    val manager = NSFileManager.defaultManager
    val directory = NSTemporaryDirectory()
    val paths =
        manager
            .contentsOfDirectoryAtPath(directory, null)
            ?.filterIsInstance<String>()
            ?.filter { it.startsWith("gallr-story-") && it.endsWith(".png") }
            ?.map { directory + it }
            ?.sortedByDescending {
                (manager.attributesOfItemAtPath(it, null)?.get(NSFileModificationDate) as? NSDate)
                    ?.timeIntervalSince1970 ?: 0.0
            } ?: return
    paths.filter { it != currentPath && it != activeStoryFile }.drop(3).forEach {
        if (!manager.removeItemAtPath(it, null)) shareHandlerLog.warn("prune_story_card")
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

private fun topmostViewController(): UIViewController? {
    var rootVC: UIViewController? = null
    for (scene in UIApplication.sharedApplication.connectedScenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        for (window in windowScene.windows) {
            val win = window as? UIWindow ?: continue
            if (win.isKeyWindow()) {
                rootVC = win.rootViewController
                break
            }
        }
        if (rootVC != null) break
    }

    var topVC = rootVC
    while (topVC?.presentedViewController != null) {
        topVC = topVC.presentedViewController
    }
    return topVC
}

private fun presentActivityController(controller: UIActivityViewController): Result<Unit> =
    runCatching {
        val presenter = checkNotNull(topmostViewController())
        controller.anchorPopover(presenter)
        presenter.presentViewController(controller, animated = true, completion = null)
    }

@OptIn(ExperimentalForeignApi::class)
private fun UIActivityViewController.anchorPopover(presenter: UIViewController) {
    popoverPresentationController?.let { popover ->
        popover.sourceView = presenter.view
        val bounds = presenter.view.bounds
        popover.sourceRect =
            bounds.useContents {
                CGRectMake(size.width / 2.0, size.height / 2.0, 0.0, 0.0)
            }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun drawExhibitionStoryCard(
    content: ExhibitionStoryShareContent,
    imageBytes: ByteArray?,
    theme: ExhibitionStoryCardPalette,
): UIImage? {
    val config = ExhibitionStoryShareConfig
    val width = config.CARD_WIDTH_PX.toDouble()
    val height = config.CARD_HEIGHT_PX.toDouble()
    val side = config.SIDE_MARGIN_PX.toDouble()
    val cover = imageBytes?.toThumbnail(config.IMAGE_SIZE_PX)
    val poster = cover?.posterPalette() ?: PosterPalette.FALLBACK
    val colors = storyCardColors(theme, poster)
    val qrImage = content.webUrl?.let { qrImage(ExhibitionQr.encode(it, poster.qrColors)) }

    val view = UIView(frame = CGRectMake(0.0, 0.0, width, height))
    view.addSubview(UIImageView(frame = view.bounds).apply { image = paperImage(colors.paperTop, colors.paperBottom) })

    content.status?.let { status ->
        view.addSubview(
            statusChip(status.text, colors, colors.statusDot(status.emphasized)),
        )
    }

    val imageFrame =
        CGRectMake(
            side,
            config.IMAGE_TOP_PX.toDouble(),
            config.IMAGE_SIZE_PX.toDouble(),
            config.IMAGE_SIZE_PX.toDouble(),
        )
    val imageShadow =
        UIView(frame = imageFrame).apply {
            backgroundColor = colors.placeholder.toUIColor()
            layer.shadowColor = UIColor.blackColor.CGColor
            layer.shadowOpacity = 0.18f
            layer.shadowRadius = config.IMAGE_SHADOW_BLUR_PX / 2.0
            layer.shadowOffset = CGSizeMake(0.0, config.IMAGE_SHADOW_OFFSET_Y_PX.toDouble())
            layer.shadowPath = UIBezierPath.bezierPathWithRect(bounds).CGPath
        }
    view.addSubview(imageShadow)
    val imageView = UIImageView(frame = imageFrame)
    imageView.backgroundColor = colors.placeholder.toUIColor()
    imageView.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
    imageView.clipsToBounds = true
    cover?.let { imageView.image = it }
    imageView.layer.borderWidth = 1.0
    imageView.layer.borderColor = colors.frame.toUIColor().CGColor
    view.addSubview(imageView)

    val textLayout =
        exhibitionStoryTextLayout(
            content = content,
            measureTitle = { text ->
                measureLabelWidth(text, config.TITLE_FONT_SIZE_PX.toDouble(), UIFontWeightMedium)
            },
            measureVenue = { text ->
                measureLabelWidth(text, config.VENUE_FONT_SIZE_PX.toDouble(), UIFontWeightMedium)
            },
        )
    val title =
        label(
            textLayout.titleLines.joinToString("\n"),
            config.TITLE_FONT_SIZE_PX.toDouble(),
            colors.title.toUIColor(),
            lines = textLayout.titleLines.size.toLong(),
            weight = UIFontWeightMedium,
        ).apply {
            lineBreakMode = NSLineBreakByClipping
        }
    title.setFrame(
        CGRectMake(
            side,
            config.TITLE_TOP_PX.toDouble(),
            config.IMAGE_SIZE_PX.toDouble(),
            (config.TITLE_LINE_HEIGHT_PX * textLayout.titleLines.size).toDouble(),
        ),
    )
    view.addSubview(title)

    view.addTextLine(
        textLayout.venue,
        config.VENUE_TOP_PX,
        config.VENUE_FONT_SIZE_PX,
        config.VENUE_HEIGHT_PX,
        colors.secondary,
        UIFontWeightMedium,
    )

    view.addSubview(
        UIView(
            frame = CGRectMake(side, config.DIVIDER_TOP_PX.toDouble(), config.DIVIDER_WIDTH_PX.toDouble(), 2.0),
        ).apply {
            backgroundColor = colors.divider.toUIColor()
        },
    )

    view.addTextLine(
        content.dateRange,
        config.DATE_TOP_PX,
        config.DATE_FONT_SIZE_PX,
        config.DATE_HEIGHT_PX,
        colors.title,
    )
    content.detailLine?.let {
        view.addTextLine(
            it,
            config.DETAIL_TOP_PX,
            config.DETAIL_FONT_SIZE_PX,
            config.DETAIL_HEIGHT_PX,
            colors.secondary,
        )
    }

    val swatchStep = (config.SWATCH_SIZE_PX + config.SWATCH_GAP_PX).toDouble()
    val swatchTop = config.DETAIL_TOP_PX + (config.DETAIL_HEIGHT_PX - config.SWATCH_SIZE_PX) / 2.0
    var swatchX = width - side - poster.qrColors.size * swatchStep + config.SWATCH_GAP_PX
    poster.qrColors.forEach { swatch ->
        view.addSubview(
            UIView(
                frame =
                    CGRectMake(
                        swatchX,
                        swatchTop,
                        config.SWATCH_SIZE_PX.toDouble(),
                        config.SWATCH_SIZE_PX.toDouble(),
                    ),
            ).apply {
                backgroundColor = swatch.toUIColor()
            },
        )
        swatchX += swatchStep
    }

    qrImage?.let { image ->
        val box = image.size.useContents { this.width }
        view.addSubview(
            UIImageView(frame = CGRectMake(width - side - box, height - config.SAFE_BOTTOM_PX - box, box, box)).apply {
                this.image = image
                backgroundColor = colors.qrTile.toUIColor()
                layer.cornerRadius = config.QR_CORNER_RADIUS_PX.toDouble()
                layer.magnificationFilter = kCAFilterNearest
                clipsToBounds = true
            },
        )
    }

    view.addBrand(side, colors.title)
    view.addTextLine(
        content.qrCaption,
        config.CAPTION_TOP_PX,
        config.CAPTION_FONT_SIZE_PX,
        config.CAPTION_LINE_HEIGHT_PX,
        colors.secondary,
    )
    view.addTextLine(
        config.CAPTION_URL_TEXT,
        config.CAPTION_TOP_PX + config.CAPTION_LINE_HEIGHT_PX,
        config.CAPTION_FONT_SIZE_PX,
        config.CAPTION_LINE_HEIGHT_PX,
        colors.secondary,
    )

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), true, 1.0)
    view.drawViewHierarchyInRect(view.bounds, afterScreenUpdates = true)
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

@OptIn(ExperimentalForeignApi::class)
private fun UIView.addTextLine(
    text: String,
    top: Int,
    fontSize: Int,
    lineHeight: Int,
    color: Int,
    weight: Double = UIFontWeightRegular,
) {
    val config = ExhibitionStoryShareConfig
    addSubview(
        label(text, fontSize.toDouble(), color.toUIColor(), lines = 1, weight = weight).apply {
            setFrame(
                CGRectMake(
                    config.SIDE_MARGIN_PX.toDouble(),
                    top.toDouble(),
                    config.IMAGE_SIZE_PX.toDouble(),
                    lineHeight.toDouble(),
                ),
            )
        },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun statusChip(
    text: String,
    colors: StoryCardColors,
    dotColor: Int,
): UIView {
    val config = ExhibitionStoryShareConfig
    val dotSize = config.STATUS_DOT_RADIUS_PX * 2.0
    val textLeft = config.STATUS_PADDING_PX + dotSize + config.STATUS_DOT_GAP_PX
    val textWidth = measureLabelWidth(text, config.STATUS_FONT_SIZE_PX.toDouble(), UIFontWeightSemibold).toDouble()
    val height = config.STATUS_HEIGHT_PX.toDouble()
    val chip =
        UIView(
            frame =
                CGRectMake(
                    config.SIDE_MARGIN_PX.toDouble(),
                    config.STATUS_TOP_PX.toDouble(),
                    textLeft + textWidth + config.STATUS_PADDING_PX,
                    height,
                ),
        ).apply {
            backgroundColor = colors.statusBackground.toUIColor()
            layer.cornerRadius = height / 2.0
        }
    chip.addSubview(
        UIView(
            frame = CGRectMake(config.STATUS_PADDING_PX.toDouble(), (height - dotSize) / 2.0, dotSize, dotSize),
        ).apply {
            backgroundColor = dotColor.toUIColor()
            layer.cornerRadius = dotSize / 2.0
        },
    )
    chip.addSubview(
        label(
            text,
            config.STATUS_FONT_SIZE_PX.toDouble(),
            colors.title.toUIColor(),
            lines = 1,
            weight = UIFontWeightSemibold,
        ).apply {
            setFrame(CGRectMake(textLeft, 0.0, textWidth + 4.0, height))
        },
    )
    return chip
}

@OptIn(ExperimentalForeignApi::class)
private fun UIView.addBrand(
    left: Double,
    color: Int,
) {
    val config = ExhibitionStoryShareConfig
    val markSize = config.BRAND_MARK_SIZE_PX.toDouble()
    val top = config.BRAND_TOP_PX.toDouble()
    val markView = UIView(frame = CGRectMake(left, top + (config.BRAND_HEIGHT_PX - markSize) / 2.0, markSize, markSize))
    val shape = CAShapeLayer()
    shape.frame = markView.bounds
    shape.path = archPinBezier(markSize).CGPath
    shape.fillColor = color.toUIColor().CGColor
    shape.fillRule = kCAFillRuleEvenOdd
    markView.layer.addSublayer(shape)
    addSubview(markView)
    addSubview(
        label("gallr", config.BRAND_FONT_SIZE_PX.toDouble(), color.toUIColor(), lines = 1).apply {
            setFrame(
                CGRectMake(
                    left + markSize + config.BRAND_GAP_PX,
                    top,
                    config.IMAGE_SIZE_PX.toDouble(),
                    config.BRAND_HEIGHT_PX.toDouble(),
                ),
            )
        },
    )
}

/** Vertical paper gradient, one row at a time (CAGradientLayer can't take Kotlin CGColor lists). */
@OptIn(ExperimentalForeignApi::class)
private fun paperImage(
    top: Int,
    bottom: Int,
): UIImage? {
    val config = ExhibitionStoryShareConfig
    val width = config.CARD_WIDTH_PX.toDouble()
    val rows = config.CARD_HEIGHT_PX
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, rows.toDouble()), true, 1.0)
    for (row in 0 until rows) {
        mixArgb(top, bottom, row / (rows - 1).toDouble()).toUIColor().setFill()
        UIRectFill(CGRectMake(0.0, row.toDouble(), width, 1.0))
    }
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

/** Whole-pixel modules at 1:1 scale so the exported QR edges stay crisp for scanners. */
@OptIn(ExperimentalForeignApi::class)
private fun qrImage(qr: ExhibitionQr): UIImage? {
    val modulePx = qrModulePx(qr.size).toDouble()
    val box = qr.size * modulePx
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(box, box), false, 1.0)
    for (row in 0 until qr.size) {
        for (col in 0 until qr.size) {
            val color = qr.colorAt(row, col) ?: continue
            color.toUIColor().setFill()
            UIRectFill(CGRectMake(col * modulePx, row * modulePx, modulePx, modulePx))
        }
    }
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

/** Aspect-fill the poster into a small RGBA buffer and derive its palette. */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.posterPalette(): PosterPalette {
    val image = CGImage ?: return PosterPalette.FALLBACK
    val size = PosterPalette.SAMPLE_SIZE
    val rgba = ByteArray(size * size * 4)
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    rgba.usePinned { pinned ->
        val context =
            CGBitmapContextCreate(
                pinned.addressOf(0),
                size.toULong(),
                size.toULong(),
                8u,
                (size * 4).toULong(),
                colorSpace,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
        val sourceWidth = CGImageGetWidth(image).toDouble()
        val sourceHeight = CGImageGetHeight(image).toDouble()
        val scale = size / minOf(sourceWidth, sourceHeight)
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        CGContextDrawImage(
            context,
            CGRectMake((size - drawWidth) / 2.0, (size - drawHeight) / 2.0, drawWidth, drawHeight),
            image,
        )
        CGContextRelease(context)
    }
    CGColorSpaceRelease(colorSpace)
    return PosterPalette.fromRgba(rgba)
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun ByteArray.toThumbnail(maxPixelSize: Int): UIImage? {
    if (isEmpty()) return null
    val data =
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    val image = UIImage.imageWithData(data) ?: return null
    return image.imageByPreparingThumbnailOfSize(
        CGSizeMake(maxPixelSize.toDouble(), maxPixelSize.toDouble()),
    )
}

private fun label(
    text: String,
    size: Double,
    color: UIColor,
    lines: Long,
    weight: Double = UIFontWeightRegular,
): UILabel =
    UILabel().apply {
        this.text = text
        this.textColor = color
        this.font = UIFont.systemFontOfSize(size, weight)
        this.numberOfLines = lines
        this.lineBreakMode = NSLineBreakByTruncatingTail
    }

private fun Int.toUIColor(): UIColor =
    UIColor(
        red = ((this ushr 16) and 0xFF) / 255.0,
        green = ((this ushr 8) and 0xFF) / 255.0,
        blue = (this and 0xFF) / 255.0,
        alpha = ((this ushr 24) and 0xFF) / 255.0,
    )

@OptIn(ExperimentalForeignApi::class)
private fun measureLabelWidth(
    text: String,
    fontSize: Double,
    weight: Double = UIFontWeightRegular,
): Float =
    label(text, fontSize, UIColor.whiteColor, lines = 1, weight = weight)
        .sizeThatFits(CGSizeMake(Double.MAX_VALUE, fontSize * 2.0))
        .useContents { width.toFloat() }

@OptIn(ExperimentalForeignApi::class)
private fun archPinBezier(size: Double): UIBezierPath {
    val scale = size / 100.0

    fun point(
        x: Double,
        y: Double,
    ) = CGPointMake(x * scale, y * scale)

    return UIBezierPath().apply {
        moveToPoint(point(50.0, 90.0))
        addCurveToPoint(
            endPoint = point(14.0, 48.0),
            controlPoint1 = point(30.0, 78.0),
            controlPoint2 = point(14.0, 64.0),
        )
        addCurveToPoint(
            endPoint = point(50.0, 12.0),
            controlPoint1 = point(14.0, 28.1177),
            controlPoint2 = point(30.1177, 12.0),
        )
        addCurveToPoint(
            endPoint = point(86.0, 48.0),
            controlPoint1 = point(69.8823, 12.0),
            controlPoint2 = point(86.0, 28.1177),
        )
        addCurveToPoint(
            endPoint = point(50.0, 90.0),
            controlPoint1 = point(86.0, 64.0),
            controlPoint2 = point(70.0, 78.0),
        )
        closePath()

        moveToPoint(point(50.0, 82.0))
        addCurveToPoint(
            endPoint = point(24.0, 48.0),
            controlPoint1 = point(35.0, 71.0),
            controlPoint2 = point(24.0, 60.0),
        )
        addCurveToPoint(
            endPoint = point(50.0, 22.0),
            controlPoint1 = point(24.0, 33.6406),
            controlPoint2 = point(35.6406, 22.0),
        )
        addCurveToPoint(
            endPoint = point(76.0, 48.0),
            controlPoint1 = point(64.3594, 22.0),
            controlPoint2 = point(76.0, 33.6406),
        )
        addCurveToPoint(
            endPoint = point(50.0, 82.0),
            controlPoint1 = point(76.0, 60.0),
            controlPoint2 = point(65.0, 71.0),
        )
        closePath()
    }
}
