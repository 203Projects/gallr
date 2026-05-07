package com.gallr.app.ui.tabs.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.util.parseHexColor
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import NMapsMap.NMFCameraPosition
import NMapsMap.NMFCameraUpdate
import NMapsMap.NMFMapView
import NMapsMap.NMFMarker
import NMapsMap.NMFMyPositionMode
import NMapsMap.NMFOverlayImage
import NMapsMap.NMGLatLng
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIScreen
import platform.UIKit.UIView

private const val SEOUL_LAT = 37.5665
private const val SEOUL_LNG = 126.9780
private const val INITIAL_ZOOM = 10.0

// Default pin color when no event is linked or the hex is malformed.
// #FF5400 with alpha 0xFF — matches the Android ACCENT_ARGB.
private const val ACCENT_ARGB: Int = 0xFFFF5400.toInt()

private fun Int.rgbComponents(): Triple<Double, Double, Double> {
    val r = ((this shr 16) and 0xFF) / 255.0
    val g = ((this shr 8) and 0xFF) / 255.0
    val b = (this and 0xFF) / 255.0
    return Triple(r, g, b)
}

private fun ExhibitionMapPin.brandColorArgb(): Int? =
    brandColorHex?.let { parseHexColor(it)?.toInt() }

@OptIn(ExperimentalForeignApi::class)
private class NMFMapContainerView @OverrideInit constructor(
    frame: CValue<CGRect>,
) : UIView(frame = frame) {

    override fun layoutSubviews() {
        super.layoutSubviews()
        val b = bounds
        val nonZero = b.useContents { size.width > 0.0 && size.height > 0.0 }
        if (nonZero) {
            subviews.forEach { (it as? UIView)?.setFrame(b) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createMarkerImage(red: Double, green: Double, blue: Double): UIImage {
    val w = 32.0
    val h = 44.0
    val radius = w / 2.0

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, UIScreen.mainScreen.scale)

    val color = UIColor(red = red, green = green, blue = blue, alpha = 1.0)

    // Circle head
    color.setFill()
    val circle = UIBezierPath.bezierPathWithOvalInRect(CGRectMake(0.0, 0.0, w, w))
    circle.fill()

    // Pointed tail
    val tail = UIBezierPath()
    tail.moveToPoint(CGPointMake(0.0, radius))
    tail.addLineToPoint(CGPointMake(radius, h))
    tail.addLineToPoint(CGPointMake(w, radius))
    tail.closePath()
    tail.fill()

    // White inner dot
    UIColor.whiteColor.setFill()
    val dot = UIBezierPath.bezierPathWithOvalInRect(
        CGRectMake(radius - radius * 0.35, radius - radius * 0.35, radius * 0.7, radius * 0.7)
    )
    dot.fill()

    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image!!
}

// CGPoint helper
@OptIn(ExperimentalForeignApi::class)
private fun CGPointMake(x: Double, y: Double) = platform.CoreGraphics.CGPointMake(x, y)

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier,
    enableUserLocation: Boolean,
    initialCenter: Coordinates?,
) {
    val activeMarkers = remember { mutableListOf<NMFMarker>() }
    val mapRef = remember { arrayOfNulls<NMFMapView>(1) }
    val imageCache = remember { mutableMapOf<Int, NMFOverlayImage>() }

    Box(modifier = modifier) {
        UIKitView(
            factory = {
                val screenBounds = UIScreen.mainScreen.bounds
                val mapView = NMFMapView(frame = screenBounds)
                val target = initialCenter
                    ?.let { NMGLatLng.latLngWithLat(it.latitude, lng = it.longitude) }
                    ?: NMGLatLng.latLngWithLat(SEOUL_LAT, lng = SEOUL_LNG)
                val cameraPosition = NMFCameraPosition.cameraPosition(target, zoom = INITIAL_ZOOM)
                mapView.moveCamera(NMFCameraUpdate.cameraUpdateWithPosition(cameraPosition))
                if (enableUserLocation) {
                    // NMFMyPositionMode: 0=Disabled, 1=Normal, 2=Direction, 3=Compass
                    mapView.positionMode = 1u  // Normal — shows location dot, doesn't track
                }
                mapRef[0] = mapView

                val container = NMFMapContainerView(frame = screenBounds)
                container.addSubview(mapView)
                container
            },
            modifier = Modifier.fillMaxSize(),
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
            ),
            update = { _ ->
                val mapView = mapRef[0] ?: return@UIKitView
                activeMarkers.forEach { it.mapView = null }
                activeMarkers.clear()
                locations.forEach { location ->
                    // Mixed-event locations (multiple pins at same coords with different eventIds):
                    // the first pin's color wins. Tap opens the existing bottom sheet which lists
                    // every pin individually, so no information is lost.
                    val pinColorArgb = location.pins.firstOrNull()?.brandColorArgb() ?: ACCENT_ARGB
                    val image = imageCache.getOrPut(pinColorArgb) {
                        val (r, g, b) = pinColorArgb.rgbComponents()
                        NMFOverlayImage.overlayImageWithImage(createMarkerImage(r, g, b))
                    }
                    val marker = NMFMarker()
                    marker.position = NMGLatLng.latLngWithLat(location.latitude, lng = location.longitude)
                    marker.captionText = location.label
                    marker.iconImage = image
                    marker.touchHandler = { _ ->
                        onLocationTap(location)
                        true
                    }
                    marker.mapView = mapView
                    activeMarkers.add(marker)
                }
            },
        )
    }
}
