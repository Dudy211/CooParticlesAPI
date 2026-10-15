package cn.coostack.cooparticlesapi.renderer.terrain

import cn.coostack.cooparticlesapi.compat.mc1201.VertexFormatElementCompat
import cn.coostack.cooparticlesapi.compat.mc1201.vertexFormatBuilder
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement

/** Terrain 顶点格式：图集 UV、效果 UV 和 light UV 使用互不覆盖的元素。 */
internal object CooTerrainVertexFormats {
    @JvmField
    val BLOCK_EFFECT: VertexFormat = vertexFormatBuilder()
        .add("Position", VertexFormatElementCompat.POSITION)
        .add("Color", VertexFormatElementCompat.COLOR)
        .add("BaseUV", VertexFormatElementCompat.UV0)
        .add("EffectUV", VertexFormatElementCompat.UV1)
        .add("LightUV", VertexFormatElementCompat.UV2)
        .add("Normal", VertexFormatElementCompat.NORMAL)
        .build()
}
