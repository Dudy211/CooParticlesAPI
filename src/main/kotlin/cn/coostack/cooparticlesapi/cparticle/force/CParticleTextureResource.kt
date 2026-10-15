package cn.coostack.cooparticlesapi.cparticle.force

import net.minecraft.resources.ResourceLocation

/**
 * 纹理型力场资源（供 [CParticleForce.Texture] 使用）。
 *
 * 上游缺失文件，依据引用点反推：
 * - `CParticleForce.kt:450` → `class Texture(var resource: CParticleTextureResource, ...)`
 * - `CParticleEmitterBridge.kt:413` → `LinkedHashMap<CParticleTextureResource, Int>()`（要求正确的 equals/hashCode）
 *
 * 语义：指向一张纹理（如流体速度场 / 噪声矢量图），
 * 供 GPU 在 compute shader 里采样，产生方向力。
 */
class CParticleTextureResource(
    /** 纹理位置（assets/<ns>/textures/<path>.png） */
    val location: ResourceLocation,
) : CParticleForceResource {

    private var boundUnit: Int = -1

    override fun bindCompute(textureUnit: Int) {


        boundUnit = textureUnit
    }

    override fun resetCompute() {
        boundUnit = -1
    }

    override fun resourceKey(): Any = location

    override fun equals(other: Any?): Boolean =
        other is CParticleTextureResource && other.location == location

    override fun hashCode(): Int = location.hashCode()

    override fun toString(): String = "CParticleTextureResource($location)"
}
