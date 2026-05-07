package com.gallr.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

// Module-level strong reference. PHPickerViewController.delegate is weak,
// so without an explicit strong reference the Kotlin/Native GC collects the
// delegate before the user picks a photo. Released after picker completes.
private var activePickerDelegate: NSObject? = null

@Composable
actual fun rememberImagePicker(onImagePicked: (ByteArray?) -> Unit): () -> Unit {
    val currentCallback = rememberUpdatedState(onImagePicked)

    return {
        val callback = currentCallback.value
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)

        // Anonymous object: Kotlin/Native registers ObjC protocol conformance
        // correctly for anonymous objects implementing ObjC protocols.
        val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)

                val result = didFinishPicking.firstOrNull() as? PHPickerResult
                if (result == null) {
                    activePickerDelegate = null
                    dispatch_async(dispatch_get_main_queue()) { callback(null) }
                    return
                }
                val provider: NSItemProvider = result.itemProvider
                if (provider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier)) {
                    provider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, _ ->
                        // Release strong reference now that async load is done
                        activePickerDelegate = null
                        dispatch_async(dispatch_get_main_queue()) {
                            // Return raw image bytes — crop screen handles compression
                            callback(data?.toByteArray())
                        }
                    }
                } else {
                    activePickerDelegate = null
                    dispatch_async(dispatch_get_main_queue()) { callback(null) }
                }
            }
        }

        activePickerDelegate = delegate
        picker.delegate = delegate

        // Find the topmost view controller to present from
        var rootVC: platform.UIKit.UIViewController? = null
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

        if (rootVC != null) {
            var topVC = rootVC
            while (topVC?.presentedViewController != null) {
                topVC = topVC.presentedViewController
            }
            topVC?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = this.length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}
