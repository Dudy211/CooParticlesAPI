package cn.coostack.cooparticlesapi.renderer.terrain

import cn.coostack.cooparticlesapi.renderer.pipeline.CooEffectUvMode
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.core.BlockPos

/**
 * 1.20.1 移植版本。
 *
 * 改动：
 *   - VertexConsumer 的方法名 1.20.1 是 vertex/color/uv/uv2/overlayCoords/normal
 *   - vertex/normal 的坐标参数是 **Double**
 *   - 内部缓存的 x/y/z 是 Float（用于 UV 计算），与接口参数分开
 */
internal class CooEffectUvVertexConsumer(
    private val delegate: VertexConsumer,
    private val mode: CooEffectUvMode,
    private val blockPos: BlockPos,
    activatedAt: Long,
) : VertexConsumer {

    /** 写入顶点光照通道的绝对生效 tick。 */
    private val activatedAt = activatedAt


    private var x = 0F
    private var y = 0F
    private var z = 0F
    private var baseU = 0F
    private var baseV = 0F

    override fun vertex(px: Double, py: Double, pz: Double): VertexConsumer = apply {
        this.x = px.toFloat()
        this.y = py.toFloat()
        this.z = pz.toFloat()
        delegate.vertex(px, py, pz)
    }

    override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = apply {
        delegate.color(red, green, blue, alpha)
    }

    override fun uv(u: Float, v: Float): VertexConsumer = apply {
        this.baseU = u
        this.baseV = v
        delegate.uv(u, v)
    }

    override fun overlayCoords(u: Int, v: Int): VertexConsumer = apply {
        delegate.overlayCoords(u, v)
    }

    override fun uv2(u: Int, v: Int): VertexConsumer = apply {
        delegate.uv2(u, v)
    }

    override fun normal(nx: Float, ny: Float, nz: Float): VertexConsumer = apply {
        val effectUv = CooEffectUvResolver.resolve(
            mode = mode,
            blockPos = blockPos,
            x = this.x,
            y = this.y,
            z = this.z,
            baseU = baseU,
            baseV = baseV,
            normalX = nx,
            normalY = ny,
            normalZ = nz,
        )
        delegate.overlayCoords(
            CooEffectUvResolver.pack(effectUv.u),
            CooEffectUvResolver.pack(effectUv.v),
        )
        delegate.normal(nx, ny, nz)
    }

    override fun endVertex() {
        delegate.endVertex()
    }

    override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) {
        delegate.defaultColor(red, green, blue, alpha)
    }

    override fun unsetDefaultColor() {
        delegate.unsetDefaultColor()
    }
}
