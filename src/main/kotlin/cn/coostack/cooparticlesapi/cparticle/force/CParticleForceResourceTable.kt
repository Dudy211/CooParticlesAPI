package cn.coostack.cooparticlesapi.cparticle.force

/**
 * 力场资源槽位表。
 *
 * 上游缺失文件，依据引用点反推补写：
 * - `CParticleSystem.kt:110`  → `CParticleForceResourceTable()`（无参构造）
 * - `CParticleSystem.kt:1059` → `.clear()`
 * - `CParticleSystem.kt:1118` → `.slotFor(force.resource)`
 * - `CParticleGpuSimulator:183` → `.textureBindings()` / `.fluidBindings()`
 * - `CParticleGpuSimulator:250` → `CParticleForceResourceTable.MAX_TEXTURE_RESOURCES`
 * - `CParticleCpuSimulator:589` → `.textureBinding(slot)`（返回可采样的绑定）
 * - `CParticleCpuSimulator:615` → `.fluidBinding(slot)`
 */
class CParticleForceResourceTable {

    private val textureSlots = LinkedHashMap<CParticleTextureResource, Int>()
    private val fluidSlots = LinkedHashMap<CParticleFluidResource, Int>()

    /** 取得资源 slot 号；首次出现则分配 */
    fun slotFor(resource: CParticleForceResource): Int = when (resource) {
        is CParticleTextureResource -> textureSlots.getOrPut(resource) { textureSlots.size }
        is CParticleFluidResource -> fluidSlots.getOrPut(resource) { fluidSlots.size }
        else -> 0
    }

    /** 纹理绑定的有序列表（下标 = slot 号） */
    fun textureBindings(): List<CParticleForceResource> = textureSlots.keys.toList()

    /** 流体绑定的有序列表（下标 = slot 号） */
    fun fluidBindings(): List<CParticleForceResource> = fluidSlots.keys.toList()

    /**
     * 按 slot 取纹理绑定（CPU 模拟器采样用）。
     *
     * 引用：`CParticleCpuSimulator.kt:589`
     *   `val binding = resourceTable.textureBinding(commands[b + 3].toInt())`
     *   `binding.sampleTexture(px, py, pz, resourceSample)`
     */
    fun textureBinding(slot: Int): CParticleForceBinding {
        val key = textureSlots.entries.elementAtOrNull(slot)?.key
        return CParticleForceBinding(key?.toString() ?: "empty", slot)
    }

    /**
     * 按 slot 取流体绑定。
     *
     * 引用：`CParticleCpuSimulator.kt:615`
     *   `val binding = resourceTable.fluidBinding(commands[b + 2].toInt())`
     *   `binding.sampleFluid(px, py, pz, resourceSample)`
     */
    fun fluidBinding(slot: Int): CParticleForceBinding {
        val key = fluidSlots.entries.elementAtOrNull(slot)?.key
        return CParticleForceBinding(key?.toString() ?: "empty", slot)
    }

    /** 清空 */
    fun clear() {
        textureSlots.clear()
        fluidSlots.clear()
    }

    val textureCount: Int get() = textureSlots.size
    val fluidCount: Int get() = fluidSlots.size

    companion object {
        /** 纹理占用的纹理单元上限；流体紧接其后编号 */
        const val MAX_TEXTURE_RESOURCES: Int = 8
    }
}

/**
 * CPU 侧的力场资源采样绑定。
 *
 * 无 GL 上下文时返回默认值（不产生力），
 * 这样 CPU 模拟路径不会因为缺资源而崩溃。
 */
class CParticleForceBinding(
    val name: String,
    val slot: Int,
) {
    /** 采样纹理力（写入 4 分量：fx, fy, fz, w） */
    fun sampleTexture(px: Double, py: Double, pz: Double, out: FloatArray) {
        if (out.size >= 3) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
        }
        if (out.size >= 4) out[3] = 0f
    }

    /** 采样流体速度与密度 */
    fun sampleFluid(px: Double, py: Double, pz: Double, out: FloatArray) {
        if (out.size >= 3) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
        }
        if (out.size >= 4) out[3] = 1f
    }
}
