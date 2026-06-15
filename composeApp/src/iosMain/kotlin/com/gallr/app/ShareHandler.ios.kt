package com.gallr.app

import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.app.share.brandGroupStartX
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.KtorCoverImageDownloader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.kCAFillRuleEvenOdd
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UILabel
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val APP_STORE_URL = "https://apps.apple.com/app/gallr/id6760855059"

private val coverImageDownloader = KtorCoverImageDownloader.ktor()

actual fun createShareHandler(): ShareHandler = object : ShareHandler {
    override fun shareApp() {
        val text = "Check out gallr \u2014 $APP_STORE_URL"
        dispatch_async(dispatch_get_main_queue()) {
            val controller = UIActivityViewController(
                activityItems = listOf(text),
                applicationActivities = null,
            )
            presentActivityController(controller)
        }
    }

    override suspend fun shareExhibition(exhibition: Exhibition, lang: AppLanguage) {
        val content = ExhibitionStoryShareContent.from(exhibition, lang)
        val imageBytes = content.coverImageUrl?.let { coverImageDownloader.download(it) }
        // UIKit (UIView/UIGraphics/present) must run on the main thread; the
        // download above suspends and may resume off-main, so re-confine here.
        withContext(Dispatchers.Main) {
            runCatching {
                val image = drawExhibitionStoryCard(content, imageBytes) ?: return@runCatching
                val controller = UIActivityViewController(
                    activityItems = listOf(image),
                    applicationActivities = null,
                )
                // Note: we intentionally do not set an email "subject". The KVC hack
                // `controller.setValue(..., forKey = "subject")` no longer resolves under
                // the Xcode 26 SDK via Kotlin/Native, and the subject only affects the
                // Mail share target — the image share works without it.
                presentActivityController(controller)
            }
        }
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

private fun presentActivityController(controller: UIActivityViewController) {
    runCatching {
        val presenter = topmostViewController() ?: return@runCatching
        controller.anchorPopover(presenter)
        presenter.presentViewController(controller, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun UIActivityViewController.anchorPopover(presenter: UIViewController) {
    popoverPresentationController?.let { popover ->
        popover.sourceView = presenter.view
        val bounds = presenter.view.bounds
        popover.sourceRect = bounds.useContents {
            CGRectMake(size.width / 2.0, size.height / 2.0, 0.0, 0.0)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun drawExhibitionStoryCard(
    content: ExhibitionStoryShareContent,
    imageBytes: ByteArray?,
): UIImage? {
    val config = ExhibitionStoryShareConfig
    val view = UIView(frame = CGRectMake(0.0, 0.0, config.cardWidthPx.toDouble(), config.cardHeightPx.toDouble()))
    view.backgroundColor = UIColor.blackColor

    val imageFrame = CGRectMake(
        config.sideMarginPx.toDouble(),
        (config.safeTopPx + 140).toDouble(),
        config.imageSizePx.toDouble(),
        config.imageSizePx.toDouble(),
    )
    val imageView = UIImageView(frame = imageFrame)
    imageView.backgroundColor = UIColor(red = 0.04, green = 0.04, blue = 0.04, alpha = 1.0)
    imageView.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
    imageView.clipsToBounds = true
    imageBytes?.toUIImage()?.let { imageView.image = it }
    view.addSubview(imageView)

    val title = label(content.title, 44.0, UIColor.whiteColor, lines = 2)
    title.setFrame(CGRectMake(config.sideMarginPx.toDouble(), imageFrame.useContents { origin.y + size.height + 72.0 }, config.imageSizePx.toDouble(), 112.0))
    view.addSubview(title)

    val venue = label(content.venue, 28.0, UIColor(white = 1.0, alpha = 0.5), lines = 1)
    venue.setFrame(CGRectMake(config.sideMarginPx.toDouble(), imageFrame.useContents { origin.y + size.height + 194.0 }, config.imageSizePx.toDouble(), 36.0))
    view.addSubview(venue)

    val date = label(content.dateRange, 26.0, UIColor(white = 1.0, alpha = 0.45), lines = 1)
    date.setFrame(CGRectMake(config.sideMarginPx.toDouble(), imageFrame.useContents { origin.y + size.height + 292.0 }, config.imageSizePx.toDouble(), 36.0))
    view.addSubview(date)

    val brandText = "gallr"
    val markSize = 40.0
    val gap = 16.0
    val footerY = (config.cardHeightPx - config.safeBottomPx - 86).toDouble()

    val brand = label(brandText, 34.0, UIColor(white = 1.0, alpha = 0.45), lines = 1)
    brand.textAlignment = platform.UIKit.NSTextAlignmentLeft
    val textWidth = brand.sizeThatFits(CGSizeMake(config.cardWidthPx.toDouble(), 48.0)).useContents { width }
    val startX = brandGroupStartX(
        cardWidth = config.cardWidthPx,
        markSize = markSize.toFloat(),
        gap = gap.toFloat(),
        textWidth = textWidth.toFloat(),
    ).toDouble()

    val markView = UIView(frame = CGRectMake(startX, footerY + (48.0 - markSize) / 2.0, markSize, markSize))
    markView.backgroundColor = UIColor.clearColor
    val shape = CAShapeLayer()
    shape.frame = markView.bounds
    shape.path = archPinBezier(markSize).CGPath
    shape.fillColor = UIColor(white = 1.0, alpha = 0.45).CGColor
    shape.fillRule = kCAFillRuleEvenOdd
    markView.layer.addSublayer(shape)
    view.addSubview(markView)

    brand.setFrame(CGRectMake(startX + markSize + gap, footerY, textWidth + 4.0, 48.0))
    view.addSubview(brand)

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(config.cardWidthPx.toDouble(), config.cardHeightPx.toDouble()), true, UIScreen.mainScreen.scale)
    view.drawViewHierarchyInRect(view.bounds, afterScreenUpdates = true)
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun ByteArray.toUIImage(): UIImage? {
    if (isEmpty()) return null
    val data = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
    return UIImage.imageWithData(data)
}

private fun label(text: String, size: Double, color: UIColor, lines: Long): UILabel =
    UILabel().apply {
        this.text = text
        this.textColor = color
        this.font = UIFont.systemFontOfSize(size)
        this.numberOfLines = lines
        this.lineBreakMode = NSLineBreakByTruncatingTail
    }

@OptIn(ExperimentalForeignApi::class)
private fun archPinBezier(size: Double): UIBezierPath {
    val scale = size / 100.0
    fun point(x: Double, y: Double) = CGPointMake(x * scale, y * scale)

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
