package com.gallr.app

import com.gallr.app.share.ExhibitionStoryShareConfig
import com.gallr.app.share.ExhibitionStoryShareContent
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.KtorCoverImageDownloader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
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

private const val APP_STORE_URL = "https://apps.apple.com/app/gallr/id6760855059"

private val coverImageDownloader = KtorCoverImageDownloader.ktor()

actual fun createShareHandler(): ShareHandler = object : ShareHandler {
    override fun shareApp() {
        val text = "Check out gallr \u2014 $APP_STORE_URL"
        val controller = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        @Suppress("DEPRECATION")
        val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootVC?.presentViewController(controller, animated = true, completion = null)
    }

    override suspend fun shareExhibition(exhibition: Exhibition, lang: AppLanguage) {
        val content = ExhibitionStoryShareContent.from(exhibition, lang)
        val imageBytes = content.coverImageUrl?.let { coverImageDownloader.download(it) }
        // UIKit (UIView/UIGraphics/present) must run on the main thread; the
        // download above suspends and may resume off-main, so re-confine here.
        withContext(Dispatchers.Main) {
            val image = drawExhibitionStoryCard(content, imageBytes) ?: return@withContext
            val controller = UIActivityViewController(
                activityItems = listOf(image),
                applicationActivities = null,
            )
            // Note: we intentionally do not set an email "subject". The KVC hack
            // `controller.setValue(..., forKey = "subject")` no longer resolves under
            // the Xcode 26 SDK via Kotlin/Native, and the subject only affects the
            // Mail share target — the image share works without it.
            @Suppress("DEPRECATION")
            val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
            rootVC?.presentViewController(controller, animated = true, completion = null)
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

    val brand = label("gallr", 34.0, UIColor(white = 1.0, alpha = 0.45), lines = 1)
    brand.textAlignment = platform.UIKit.NSTextAlignmentCenter
    brand.setFrame(CGRectMake(0.0, (config.cardHeightPx - config.safeBottomPx - 86).toDouble(), config.cardWidthPx.toDouble(), 48.0))
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
