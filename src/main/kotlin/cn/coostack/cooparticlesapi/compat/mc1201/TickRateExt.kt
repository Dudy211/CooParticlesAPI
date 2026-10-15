package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.world.level.Level

/**
 * 给 Level 补 tickRateManager()（1.20.5+ API）
 */
fun Level.tickRateManager(): TickRateManagerCompat = TickRateManagerCompat()
