package cn.coostack.cooparticlesapi.renderer.shader.utils

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.renderer.shader.data.TextureData
import cn.coostack.cooparticlesapi.renderer.shader.glsl.IdentifierShader
import net.minecraft.resources.ResourceLocation
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBImage
import java.nio.ByteBuffer

object GlslUtil {
    /**
     * 从指定来源读取并解析 `readGlslCodeFromJar` 数据；输入必须符合 `GlslUtil` 使用的资源或网络格式。
     *
     * 示例：`readGlslCodeFromJar(name = name)`。
     *
     * @param name 用于查找、绑定或记录目标的名称
     *
     * @return 当前操作计算、更新或查询得到的结果
     */
    fun readGlslCodeFromJar(name: String): String {
        val path = "assets/shaders/$name"
        val stream = this::class.java.classLoader.getResourceAsStream(path) ?: return ""
        return stream.use {
            it.readAllBytes().decodeToString()
        }
    }

    /**
     * 从指定来源读取并解析 `readGlslCodeFromJar` 数据；输入必须符合 `GlslUtil` 使用的资源或网络格式。
     *
     * 示例：`readGlslCodeFromJar(id = id)`。
     *
     * @param id 用于定位目标资源、实体或运行时实例的唯一标识
     *
     * @return 当前操作计算、更新或查询得到的结果
     */
    fun readGlslCodeFromJar(id: ResourceLocation): String {
        val path = "assets/${id.namespace}/shaders/${id.path}"
        val stream = this::class.java.classLoader.getResourceAsStream(path) ?: return ""
        return stream.use {
            it.readAllBytes().decodeToString()
        }
    }

    /**
     * 从指定来源读取并解析 `readTextureFromJar` 数据；输入必须符合 `GlslUtil` 使用的资源或网络格式。
     *
     * 示例：`readTextureFromJar(name = name)`。
     *
     * @param name 用于查找、绑定或记录目标的名称
     *
     * @return 当前操作计算、更新或查询得到的结果
     */
    fun readTextureFromJar(name: String): TextureData {
        val path = "textures/$name"
        return readTexture(path, name)
    }

    /**
     * 从指定来源读取并解析 `readTextureFromJar` 数据；输入必须符合 `GlslUtil` 使用的资源或网络格式。
     *
     * 示例：`readTextureFromJar(id = id)`。
     *
     * @param id 用于定位目标资源、实体或运行时实例的唯一标识
     *
     * @return 当前操作计算、更新或查询得到的结果
     */
    fun readTextureFromJar(id: ResourceLocation): TextureData {
        val path = "assets/${id.namespace}/textures/${id.path}"
        return readTexture(path, id.toString())
    }

    /**
     * 读取并解码纹理,失败时返回 1x1 透明占位图而不是抛异常。
     *
     * ★ 2026-10-20:原来用 `getResourceAsStream(path)!!` 与 `image!!` ——
     * 纹理缺失/损坏时直接 NPE 崩客户端(和 Bug B 的 shader 空串是同类问题)。
     * 这里改成「记一次 warn + 回退占位图」,让渲染继续跑而不是整局崩掉。
     */
    private fun readTexture(path: String, label: String): TextureData {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
        if (stream == null) {
            CooParticlesConstants.logger.warn(
                "Texture not found in jar: {} ({}); using 1x1 transparent placeholder",
                path, label
            )
            return placeholderTexture()
        }
        val bytes = stream.use { it.readAllBytes() }
        val read = ByteBuffer.allocateDirect(bytes.size)
        read.put(bytes)
        read.position(0)
        val w = BufferUtils.createIntBuffer(1)
        val h = BufferUtils.createIntBuffer(1)
        val comp = BufferUtils.createIntBuffer(1)
        val image = STBImage.stbi_load_from_memory(read, w, h, comp, 0)
        if (image == null) {
            CooParticlesConstants.logger.warn(
                "Failed to decode texture: {} ({}); using 1x1 transparent placeholder",
                path, label
            )
            return placeholderTexture()
        }
        return TextureData(image, w.get(), h.get(), comp.get())
    }

    /**
     * 1x1 全透明 RGBA 占位纹理。
     *
     * ⚠️ 必须用 **STBImage 自己分配**的内存 —— `TextureData.release()` 调的是
     * `STBImage.stbi_image_free(buffer)`,对 `BufferUtils.allocateDirect` 的堆内存
     * 调用它是未定义行为(可能堆损坏/崩溃)。这里用内嵌的 1x1 PNG 走
     * `stbi_load_from_memory` 解码,保证 buffer 归属 STB,release() 安全。
     */
    private fun placeholderTexture(): TextureData {
        val buf = ByteBuffer.allocateDirect(EMBEDDED_TRANSPARENT_PNG.size)
        buf.put(EMBEDDED_TRANSPARENT_PNG)
        buf.position(0)
        val w = BufferUtils.createIntBuffer(1)
        val h = BufferUtils.createIntBuffer(1)
        val c = BufferUtils.createIntBuffer(1)
        val image = STBImage.stbi_load_from_memory(buf, w, h, c, 0)
        if (image == null) {

            throw IllegalStateException("STB failed to decode embedded 1x1 transparent PNG")
        }
        return TextureData(image, w.get(), h.get(), c.get())
    }

    /**
     * 内嵌 1x1 全透明 PNG(68 字节),不依赖任何外部资源。
     *
     * 字节由 Python 的 zlib/struct 现场生成并校验过(签名 + IHDR/IDAT/IEND + CRC),
     * 不要手改 —— 改错一个字节就会让 STB 解码失败。
     */
    private val EMBEDDED_TRANSPARENT_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00,
        0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41, 0x54,
        0x78, 0xDA.toByte(), 0x63, 0x60, 0x00, 0x02, 0x00, 0x00, 0x05, 0x00, 0x01,
        0xE9.toByte(), 0xFA.toByte(), 0xDC.toByte(), 0xD8.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )
}