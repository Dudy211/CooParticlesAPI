package cn.coostack.cooparticlesapi.cparticle.force

/**
 * 流体型力场资源（供 [CParticleForce.FluidFlow] 使用）。
 *
 * 上游缺失文件，依据引用点反推：
 * - `CParticleForce.kt:465` → `class FluidFlow(var resource: CParticleFluidResource, ...)`
 * - `CParticleEmitterBridge.kt:414` → `LinkedHashMap<CParticleFluidResource, Int>()`
 *
 * 语义：指向一个流体速度场（通常由流体模组或内置场提供），
 * 粒子在其中受流速、密度影响。
 */
class CParticleFluidResource(
    /** 流体来源标识（原版流体 id，或模组自定义 id） */
    val fluidId: String,
) : CParticleForceResource {

    private var boundUnit: Int = -1

    override fun bindCompute(textureUnit: Int) {
        boundUnit = textureUnit
    }

    override fun resetCompute() {
        boundUnit = -1
    }

    override fun resourceKey(): Any = fluidId

    override fun equals(other: Any?): Boolean =
        other is CParticleFluidResource && other.fluidId == fluidId

    override fun hashCode(): Int = fluidId.hashCode()

    override fun toString(): String = "CParticleFluidResource($fluidId)"
}
