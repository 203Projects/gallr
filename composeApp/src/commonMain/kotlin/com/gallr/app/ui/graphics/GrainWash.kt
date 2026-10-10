package com.gallr.app.ui.graphics

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A monochrome wash drawn over a cover image: transparent at [start] (a fraction of the height), reaching
 * [strength] at [end] and holding it to the bottom edge, with per-pixel [grain] dithering so the fade reads like
 * printed paper instead of a flat banded gradient. Text placed below [end] keeps its contrast while the picture
 * above stays the subject.
 */
data class GrainWashSpec(
    val color: Color,
    val start: Float,
    val strength: Float,
    val grain: Float,
    val end: Float = 1f,
) {
    init {
        require(start in 0f..1f) { "start must be a fraction of the height" }
        require(end in 0f..1f && end > start) { "end must be a fraction of the height below start" }
        require(strength in 0f..1f) { "strength must be an alpha" }
        require(grain in 0f..1f) { "grain must be an amplitude between 0 and 1" }
    }
}

/**
 * The wash in the AGSL/SkSL subset both platforms run. `coord` is in pixels; the output is premultiplied.
 * The grain is a hash of the pixel position, so it is stable from frame to frame and never animates.
 */
internal const val GRAIN_WASH_SHADER = """
uniform float2 resolution;
uniform float4 washColor;
uniform float start;
uniform float end;
uniform float strength;
uniform float grain;

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 coord) {
    float t = smoothstep(start, end, coord.y / resolution.y);
    float noise = hash(floor(coord)) - 0.5;
    float alpha = clamp(t * strength + noise * grain * t, 0.0, 1.0);
    return half4(washColor.rgb * alpha, alpha);
}
"""

/** The platform's runtime-shader brush for [spec], or null where no runtime shader can run (Android before 13). */
internal expect fun grainWashShaderBrush(spec: GrainWashSpec): Brush?

/** The wash as a brush: the native shader where it runs, otherwise the same fade without grain. */
fun grainWashBrush(spec: GrainWashSpec): Brush =
    grainWashShaderBrush(spec)
        ?: Brush.verticalGradient(
            spec.start to spec.color.copy(alpha = 0f),
            spec.end to spec.color.copy(alpha = spec.strength),
            1f to spec.color.copy(alpha = spec.strength),
        )

/** Draws the content, then the wash over it. */
fun Modifier.grainWash(spec: GrainWashSpec): Modifier =
    drawWithCache {
        val brush = grainWashBrush(spec)
        onDrawWithContent {
            drawContent()
            drawRect(brush)
        }
    }
