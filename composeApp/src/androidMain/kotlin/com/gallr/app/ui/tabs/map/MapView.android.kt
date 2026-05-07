package com.gallr.app.ui.tabs.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gallr.shared.data.model.ExhibitionMapPin
import com.gallr.shared.util.parseHexColor
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.compose.ExperimentalNaverMapApi
import com.naver.maps.map.compose.LocationTrackingMode
import com.naver.maps.map.compose.MapProperties
import com.naver.maps.map.compose.Marker
import com.naver.maps.map.compose.NaverMap
import com.naver.maps.map.compose.MarkerState
import com.naver.maps.map.compose.rememberCameraPositionState
import com.naver.maps.map.overlay.OverlayImage

private val SEOUL = LatLng(37.5665, 126.9780)
private const val INITIAL_ZOOM = 10.0
private const val ACCENT_ARGB: Int = 0xFFFF5400.toInt()

/**
 * Creates a simple pin-shaped marker bitmap filled with the given color.
 * Pin shape: circle head with a pointed tail at the bottom.
 */
private fun createMarkerBitmap(colorArgb: Int): Bitmap {
    val w = 48
    val h = 64
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }

    // Circle head
    val radius = w / 2f
    canvas.drawCircle(radius, radius, radius, paint)

    // Pointed tail
    val path = Path().apply {
        moveTo(0f, radius)
        lineTo(radius, h.toFloat())
        lineTo(w.toFloat(), radius)
        close()
    }
    canvas.drawPath(path, paint)

    // White inner dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawCircle(radius, radius, radius * 0.35f, dotPaint)

    return bitmap
}

/**
 * Resolves an ARGB int from a pin's hex brandColor, or null if missing/malformed.
 * parseHexColor returns a Long with 0xFF alpha pre-applied; signed narrowing to Int
 * preserves the bit pattern for Android's Paint.color consumption.
 */
private fun ExhibitionMapPin.brandColorArgb(): Int? =
    brandColorHex?.let { parseHexColor(it)?.toInt() }

@OptIn(ExperimentalNaverMapApi::class)
@Composable
actual fun MapView(
    locations: List<MapLocation>,
    onLocationTap: (MapLocation) -> Unit,
    modifier: Modifier,
    enableUserLocation: Boolean,
    initialCenter: Coordinates?,
) {
    val cameraPositionState = rememberCameraPositionState {
        val target = initialCenter
            ?.let { LatLng(it.latitude, it.longitude) }
            ?: SEOUL
        position = CameraPosition(target, INITIAL_ZOOM)
    }

    val iconCache = remember { mutableMapOf<Int, OverlayImage>() }

    val properties = remember(enableUserLocation) {
        MapProperties(
            locationTrackingMode = if (enableUserLocation) LocationTrackingMode.Follow
                else LocationTrackingMode.None,
        )
    }

    NaverMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = properties,
    ) {
        locations.forEach { location ->
            // Mixed-event locations (multiple pins at same coords with different eventIds):
            // the first pin's color wins. Tap opens the existing bottom sheet which lists
            // every pin individually, so no information is lost.
            val pinColorArgb = location.pins.firstOrNull()?.brandColorArgb() ?: ACCENT_ARGB
            val icon = iconCache.getOrPut(pinColorArgb) {
                OverlayImage.fromBitmap(createMarkerBitmap(pinColorArgb))
            }
            Marker(
                state = MarkerState(position = LatLng(location.latitude, location.longitude)),
                captionText = location.label,
                icon = icon,
                onClick = {
                    onLocationTap(location)
                    true
                },
            )
        }
    }
}
