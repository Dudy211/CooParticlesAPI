package cn.coostack.cooparticlesapi.compat.mc1201

/**
 * 1.20.1 兼容层：1.20.5+ 才有 DeltaTracker（替代 float partialTick）。
 *
 * 1.20.1 里 partialTick 是裸 float 直接传的。
 * 这里提供同名类型 + 转换，让上游代码能编译。
 */
fun interface DeltaTracker {
    fun getGameTimeDeltaPartialTick(isIgnored: Boolean): Float

    companion object {
        /** 用固定 partialTick 构造（1.20.1 常见场景） */
        @JvmStatic
        fun of(partialTick: Float): DeltaTracker = DeltaTracker { partialTick }

        @JvmStatic
        fun of(partialTick: () -> Float): DeltaTracker = DeltaTracker { partialTick() }
    }
}
