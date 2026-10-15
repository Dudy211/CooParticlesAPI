package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement

import com.google.common.collect.ImmutableMap

/**
 * 1.20.1 兼容层：VertexFormat.builder() 及元素常量。
 *
 * 差异：
 *   1.21:  `VertexFormat.builder()` → `VertexFormat.Builder`
 *          `VertexFormatElement.POSITION` / `.COLOR` / `.UV0` / `.UV1` / `.UV2` / `.NORMAL`
 *   1.20.1: 没有 builder()；也没有 VertexFormatElement.POSITION，
 *           元素常量在 `DefaultVertexFormat.ELEMENT_POSITION` 等处。
 */

/** 1.21 的 VertexFormat.builder() —— 用 1.20.1 的构造方式实现 */
fun vertexFormatBuilder(): VertexFormatBuilderCompat = VertexFormatBuilderCompat()

/** 模拟 1.21 的 VertexFormat.Builder */
class VertexFormatBuilderCompat internal constructor() {
    private val elements = LinkedHashMap<String, VertexFormatElement>()

    fun add(name: String, element: VertexFormatElement): VertexFormatBuilderCompat = apply {
        elements[name] = element
    }

    fun build(): VertexFormat = VertexFormat(ImmutableMap.copyOf(elements))
}

/** 元素常量别名（1.21 名字 → 1.20.1 实际位置） */
object VertexFormatElementCompat {
    @JvmField val POSITION: VertexFormatElement = DefaultVertexFormat.ELEMENT_POSITION
    @JvmField val COLOR: VertexFormatElement = DefaultVertexFormat.ELEMENT_COLOR
    @JvmField val UV0: VertexFormatElement = DefaultVertexFormat.ELEMENT_UV0
    @JvmField val UV1: VertexFormatElement = DefaultVertexFormat.ELEMENT_UV1
    @JvmField val UV2: VertexFormatElement = DefaultVertexFormat.ELEMENT_UV2
    @JvmField val NORMAL: VertexFormatElement = DefaultVertexFormat.ELEMENT_NORMAL
    @JvmField val PADDING: VertexFormatElement = DefaultVertexFormat.ELEMENT_PADDING
}
