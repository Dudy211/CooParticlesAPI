 package cn.coostack.cooparticlesapi.display

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType

data class CooLayeredRenderTypeDescriptor(
    val name: String,
    val layers: List<CooRenderTypeDescriptor>
)

class CooLayeredRenderType(
    val name: String,
    val layers: List<RenderType>
) {
    fun consumer(bufferSource: MultiBufferSource): VertexConsumer {
        return LayeredVertexConsumer(layers.map(bufferSource::getBuffer))
    }
}

class LayeredVertexConsumer(
    private val delegates: List<VertexConsumer>
) : VertexConsumer {
    init {
        require(delegates.isNotEmpty()) { "layered vertex consumer requires at least one delegate" }
    }

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
        delegates.forEach { it.vertex(x, y, z) }
        return this
    }

    override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        delegates.forEach { it.color(red, green, blue, alpha) }
        return this
    }

    override fun uv(u: Float, v: Float): VertexConsumer {
        delegates.forEach { it.uv(u, v) }
        return this
    }

    override fun overlayCoords(u: Int, v: Int): VertexConsumer {
        delegates.forEach { it.overlayCoords(u, v) }
        return this
    }

    override fun uv2(u: Int, v: Int): VertexConsumer {
        delegates.forEach { it.uv2(u, v) }
        return this
    }

    override fun normal(normalX: Float, normalY: Float, normalZ: Float): VertexConsumer {
        delegates.forEach { it.normal(normalX, normalY, normalZ) }
        return this
    }

    override fun endVertex() {
        delegates.forEach { it.endVertex() }
    }

    override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) {
        delegates.forEach { it.defaultColor(red, green, blue, alpha) }
    }

    override fun unsetDefaultColor() {
        delegates.forEach { it.unsetDefaultColor() }
    }
}
