package cn.coostack.cooparticlesapi.cparticle.force

/**
 * 粒子力场「资源」的基类。
 *
 * 上游缺失文件，依据引用点反推补写：
 * - `CParticleEmitterBridge.kt:415` → `ArrayList<CParticleForceResource>()`
 *   `resources.add(force.resource)`（Texture / FluidFlow 两种 resource 都加进来）
 * - `CParticleSystemReuseKey(resources: List<CParticleForceResource>)`
 *
 * 语义：一个 Resource 代表「纹理/流体」这类需要绑定到 GPU 采样单元的**外部资源**，
 * 力场指令通过 slot 号引用它（见 `CParticleForceResourceTable.slotFor`）。
 *
 * 绑定时需要 `bindCompute(textureUnit)` / `resetCompute()`（见 CParticleGpuSimulator）。
 */
interface CParticleForceResource {

    /**
     * 把该资源绑定到一个 compute shader 的纹理单元。
     *
     * @param textureUnit GL 纹理单元号（>= 0）
     */
    fun bindCompute(textureUnit: Int)

    /**
     * 解除绑定（绘制完成后调用）。
     */
    fun resetCompute()

    /**
     * 资源唯一标识；用于去重与缓存。
     * 同一资源重复引用时应当返回相等值。
     */
    fun resourceKey(): Any
}
