package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.world.level.Level

/**
 * 1.20.1 兼容层：TooltipContext。
 *
 * 1.20.5+ 的 `appendHoverText` 签名：
 *   `appendHoverText(stack, TooltipContext, tooltipComponents, tooltipFlag)`
 *
 * 1.20.1 的签名：
 *   `appendHoverText(stack, Level?, tooltipComponents, tooltipFlag)`
 *   —— 第二个参数是「可空的 Level」而不是 TooltipContext。
 *
 * 这里提供 TooltipContext 类型 + 到 Level 的桥接。
 */
interface TooltipContext {
    /** 对应的 Level（可能为 null，表示仅客户端静态展示） */
    val level: net.minecraft.world.level.Level?

    companion object {
        /** 无世界上下文 */
        @JvmStatic
        val EMPTY: TooltipContext = object : TooltipContext {
            override val level: net.minecraft.world.level.Level? = null
        }

        /** 包装一个 Level */
        @JvmStatic
        fun of(level: net.minecraft.world.level.Level?): TooltipContext =
            object : TooltipContext {
                override val level: net.minecraft.world.level.Level? = level
            }
    }
}
