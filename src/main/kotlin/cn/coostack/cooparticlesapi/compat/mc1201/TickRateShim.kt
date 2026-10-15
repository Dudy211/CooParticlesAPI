package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.server.MinecraftServer

/**
 * 1.20.1 兼容层：模拟 1.20.5+ 的 TickRateManager。
 *
 * 上游有两种访问方式：属性 `isFrozen`、函数 `tickrate()`，两种都要支持。
 */
class TickRateManagerCompat {

    var isFrozen: Boolean = false
    var isSprinting: Boolean = false
    var isSteppingForward: Boolean = false

    /** 函数形式（上游：`tickRateManager().tickrate()`） */
    fun tickrate(): Float = 20.0f

    fun runsNormally(): Boolean = true
    fun nanosecondsPerTick(): Long = 50_000_000L

    fun stopSprinting() {
        isSprinting = false
    }

    fun stopStepping() {
        isSteppingForward = false
    }

    fun requestGameToSprint(tickCount: Int) {
        isSprinting = true
    }

    fun requestGameToStep() {
        isSteppingForward = true
    }
}

/** 给 MinecraftServer 补 tickRateManager()（1.20.5+ API） */
fun MinecraftServer.tickRateManager(): TickRateManagerCompat = TickRateManagerCompat()

/**
 * 1.20.5+ 的 getTickTimesNanos()。1.20.1 有同名字段（private），
 * 靠 accesstransformer 打开后直接读 —— **不要用反射**（reobf 后字段名是 f_129748_，
 * 反射会静默失败并一直返回兜底值）。
 */
fun MinecraftServer.getTickTimesNanos(): LongArray = this.tickTimes.copyOf()

/** 1.20.5+ 的 getAverageTickTimeNanos() */
fun MinecraftServer.getAverageTickTimeNanos(): Long {
    val times = getTickTimesNanos()
    if (times.isEmpty()) return 50_000_000L
    return times.average().toLong()
}
