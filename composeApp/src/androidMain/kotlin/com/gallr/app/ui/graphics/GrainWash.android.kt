package com.gallr.app.ui.graphics

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import com.gallr.shared.observability.AppLog

private val grainWashLog = AppLog.tagged("GrainWash")

/** AGSL runs from Android 13; older devices draw the plain fade. */
internal actual fun grainWashShaderBrush(spec: GrainWashSpec): Brush? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runtimeShaderBrush(spec)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun runtimeShaderBrush(spec: GrainWashSpec): Brush? {
    val shader =
        try {
            RuntimeShader(GRAIN_WASH_SHADER)
        } catch (error: IllegalArgumentException) {
            grainWashLog.warn("grain_wash_shader_compile", error)
            return null
        }
    shader.setFloatUniform("washColor", spec.color.red, spec.color.green, spec.color.blue, spec.color.alpha)
    shader.setFloatUniform("start", spec.start)
    shader.setFloatUniform("end", spec.end)
    shader.setFloatUniform("strength", spec.strength)
    shader.setFloatUniform("grain", spec.grain)
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            shader.setFloatUniform("resolution", size.width, size.height)
            return shader
        }
    }
}
