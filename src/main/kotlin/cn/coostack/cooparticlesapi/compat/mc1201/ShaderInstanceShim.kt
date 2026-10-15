package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.platform.Window
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f

/**
 * 1.20.1 兼容层：ShaderInstance.setDefaultUniforms(...)。
 *
 * 1.21 签名：
 *   `setDefaultUniforms(mode, view: Matrix4f, projection: Matrix4f, window: Window)`
 * 1.20.1 没有公开等价方法，这里写同名 uniform。
 *
 * ⚠ 1.20.1 的 uniform 字段是平台类型（Uniform!），Kotlin 视作可空，需安全调用。
 */
@JvmOverloads
fun ShaderInstance.setDefaultUniforms(
    mode: VertexFormat.Mode,
    view: Matrix4f,
    projection: Matrix4f,
    window: Window? = null,
    texMatrix: Matrix4f? = null,
) {
    runCatching {
        MODEL_VIEW_MATRIX?.let { it.set(view); it.upload() }
        PROJECTION_MATRIX?.let { it.set(projection); it.upload() }
        if (texMatrix != null) TEXTURE_MATRIX?.let { it.set(texMatrix); it.upload() }
        if (window != null) {
            val w = window.width.toFloat()
            val h = window.height.toFloat()
            if (w > 0f && h > 0f) SCREEN_SIZE?.let { it.set(w, h); it.upload() }
        }
    }.onFailure {

    }
}
