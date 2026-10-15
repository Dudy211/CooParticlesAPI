package cn.coostack.cooparticlesapi.cparticle.storage

import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL43
import org.lwjgl.system.MemoryUtil
import java.nio.FloatBuffer

/**
 * 命令 SSBO。
 *
 * 上游缺失文件（源码 `cparticle/storage/` 目录里没有此文件），
 * 依据引用点 + 同目录 `CParticleMetadataGlBuffer` 的写法反推补写。
 *
 * 引用：
 * - `CParticleSystem.kt:109`  → `internal val commandGlBuffer = CParticleCommandGlBuffer()`
 * - `CParticleSystem.kt:954`  → `commandGlBuffer.upload(packedCommands, commandCount)`
 * - `CParticleSystem.kt:1156` → `if (!commandGlBuffer.initialized) commandGlBuffer.init()`
 * - `CParticleSystem.kt:1189` → `commandGlBuffer.dispose()`
 * - `CParticleGpuSimulator`   → `system.commandGlBuffer.bindShaderStorage(COMMAND_BUFFER_BINDING)`
 */
class CParticleCommandGlBuffer(capacity: Int = 0) {

    /** GL buffer id；0 表示未初始化 */
    var buffer: Int = 0
        private set

    /** 是否已初始化（引用点：`commandGlBuffer.initialized`） */
    val initialized: Boolean get() = buffer != 0

    private var scratch: FloatBuffer? = null

    /** 容纳的 float 数 */
    var capacity: Int = capacity
        private set

    private fun scratchBuffer(requiredFloats: Int): FloatBuffer {
        val current = scratch
        if (current != null && current.capacity() >= requiredFloats) return current
        current?.let { MemoryUtil.memFree(it) }
        val replacement = MemoryUtil.memAllocFloat(requiredFloats)
        scratch = replacement
        return replacement
    }

    /** 创建/初始化 SSBO */
    fun init() {
        if (buffer != 0) return
        val previous = GL15.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)
        try {
            buffer = GL15.glGenBuffers()
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer)
            GL15.glBufferData(
                GL43.GL_SHADER_STORAGE_BUFFER,
                (capacity.coerceAtLeast(1) * 4).toLong(),
                GL15.GL_DYNAMIC_DRAW,
            )
        } finally {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previous)
        }
    }

    /** 按需扩容 */
    internal fun growTo(newCapacity: Int) {
        if (newCapacity <= capacity) return
        val wasInitialized = initialized
        release()
        capacity = newCapacity
        if (wasInitialized) init()
    }

    /**
     * 上传指令数据。
     *
     * 引用点：`commandGlBuffer.upload(packedCommands, commandCount)`
     *
     * @param data   紧凑排列的指令浮点数据
     * @param count  指令条数（用于校验；实际按 data.size 上传）
     */
    fun upload(data: FloatArray, count: Int) {
        if (data.isEmpty()) return
        if (!initialized) init()
        if (data.size > capacity) {
            capacity = data.size

            val previous = GL15.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)
            try {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer)
                GL15.glBufferData(
                    GL43.GL_SHADER_STORAGE_BUFFER,
                    (data.size * 4).toLong(),
                    GL15.GL_DYNAMIC_DRAW,
                )
            } finally {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previous)
            }
        }

        val scratchBuf = scratchBuffer(data.size)
        scratchBuf.clear()
        scratchBuf.put(data)
        scratchBuf.flip()

        val previous = GL15.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING)
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer)
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, scratchBuf)
        } finally {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previous)
        }
    }

    /** 绑定到 SSBO 绑定点 */
    fun bindShaderStorage(binding: Int) {
        if (!initialized) init()
        GL30_COMPAT.bindBase(binding, buffer)
    }

    /** 释放 GL 资源（保留 capacity） */
    fun release() {
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer)
            buffer = 0
        }
    }

    /** 彻底销毁（含 scratch 内存） */
    fun dispose() {
        release()
        scratch?.let { MemoryUtil.memFree(it) }
        scratch = null
        capacity = 0
    }

    /** GL30 的 glBindBufferBase 包装（避免顶层 import 冲突） */
    private object GL30_COMPAT {
        fun bindBase(binding: Int, buffer: Int) {
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, buffer)
        }
    }
}
