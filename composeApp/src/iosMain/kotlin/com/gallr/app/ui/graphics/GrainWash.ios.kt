package com.gallr.app.ui.graphics

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import com.gallr.shared.observability.AppLog
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

private val grainWashLog = AppLog.tagged("GrainWash")

/** Skia compiles the same source as SkSL; a compile failure falls back to the plain fade instead of crashing. */
internal actual fun grainWashShaderBrush(spec: GrainWashSpec): Brush? {
    val effect =
        try {
            RuntimeEffect.makeForShader(GRAIN_WASH_SHADER)
        } catch (error: RuntimeException) {
            grainWashLog.warn("grain_wash_shader_compile", error)
            return null
        }
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader =
            RuntimeShaderBuilder(effect)
                .apply {
                    uniform("resolution", size.width, size.height)
                    uniform("washColor", spec.color.red, spec.color.green, spec.color.blue, spec.color.alpha)
                    uniform("start", spec.start)
                    uniform("strength", spec.strength)
                    uniform("grain", spec.grain)
                }.makeShader()
    }
}
