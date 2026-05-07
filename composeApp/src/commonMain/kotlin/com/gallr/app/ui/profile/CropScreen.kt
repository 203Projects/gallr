package com.gallr.app.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gallr.shared.data.model.AppLanguage
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CropScreen(
    imageBitmap: ImageBitmap,
    lang: AppLanguage,
    onConfirm: (IntOffset, IntSize) -> Unit,
    onCancel: () -> Unit,
) {
    var containerSize by remember { mutableStateOf(Size.Zero) }

    // The crop circle diameter = min(width, height) * 0.85
    val cropDiameter = min(containerSize.width, containerSize.height) * 0.85f

    // Image display: scale so the image fills the crop area initially
    val imgW = imageBitmap.width.toFloat()
    val imgH = imageBitmap.height.toFloat()

    // Base scale: image fills the crop circle (short side matches crop diameter)
    val baseScale = remember(containerSize, imageBitmap) {
        if (containerSize == Size.Zero || imgW == 0f || imgH == 0f) 1f
        else cropDiameter / min(imgW, imgH)
    }

    var scale by remember(baseScale) { mutableFloatStateOf(baseScale) }
    var offset by remember(baseScale) { mutableStateOf(Offset.Zero) }

    // Clamp offset so the image always covers the crop circle
    fun clampOffset(off: Offset, s: Float): Offset {
        val scaledW = imgW * s
        val scaledH = imgH * s
        val cx = containerSize.width / 2f
        val cy = containerSize.height / 2f
        val r = cropDiameter / 2f

        // Image center = container center + offset
        // Image left edge = cx + off.x - scaledW/2  must be <= cx - r
        // Image right edge = cx + off.x + scaledW/2 must be >= cx + r
        val maxOffX = scaledW / 2f - r
        val maxOffY = scaledH / 2f - r

        return Offset(
            x = off.x.coerceIn(-maxOffX, maxOffX),
            y = off.y.coerceIn(-maxOffY, maxOffY),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it.toSize() },
    ) {
        // Gesture area: pan + pinch-to-zoom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(baseScale) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(baseScale, baseScale * 4f)
                        val newOffset = offset + pan
                        scale = newScale
                        offset = clampOffset(newOffset, newScale)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Draw image
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val scaledW = imgW * scale
                val scaledH = imgH * scale

                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(
                        x = (cx - scaledW / 2f + offset.x).roundToInt(),
                        y = (cy - scaledH / 2f + offset.y).roundToInt(),
                    ),
                    dstSize = IntSize(scaledW.roundToInt(), scaledH.roundToInt()),
                )
            }

            // Semi-transparent overlay with circle cutout
            if (containerSize != Size.Zero) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = cropDiameter / 2f

                    val circlePath = Path().apply {
                        addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                    }

                    clipPath(circlePath, clipOp = ClipOp.Difference) {
                        drawRect(Color.Black.copy(alpha = 0.6f))
                    }

                    // Circle border
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f),
                        radius = r,
                        center = Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "취소"
                        AppLanguage.EN -> "Cancel"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(
                onClick = {
                    // Calculate crop region in original image coordinates
                    val cx = containerSize.width / 2f
                    val cy = containerSize.height / 2f
                    val r = cropDiameter / 2f

                    // Top-left of crop circle in screen coords
                    val cropScreenLeft = cx - r
                    val cropScreenTop = cy - r

                    // Image top-left in screen coords
                    val imgScreenLeft = cx - (imgW * scale) / 2f + offset.x
                    val imgScreenTop = cy - (imgH * scale) / 2f + offset.y

                    // Crop region relative to image, in original pixel coords
                    val srcX = ((cropScreenLeft - imgScreenLeft) / scale).roundToInt().coerceAtLeast(0)
                    val srcY = ((cropScreenTop - imgScreenTop) / scale).roundToInt().coerceAtLeast(0)
                    val srcSize = (cropDiameter / scale).roundToInt()
                        .coerceAtMost(imageBitmap.width - srcX)
                        .coerceAtMost(imageBitmap.height - srcY)

                    onConfirm(IntOffset(srcX, srcY), IntSize(srcSize, srcSize))
                },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = when (lang) {
                        AppLanguage.KO -> "완료"
                        AppLanguage.EN -> "Done"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
