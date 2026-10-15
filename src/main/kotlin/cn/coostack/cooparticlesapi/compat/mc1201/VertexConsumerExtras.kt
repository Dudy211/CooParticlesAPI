package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.vertex.VertexConsumer

/**
 * 1.20.1 兼容层：补 VertexConsumer 的杂项便捷方法。
 */

/**
 * 1.21 的 setWhiteAlpha(int) —— 白色 + 指定 alpha
 */
fun VertexConsumer.setWhiteAlpha(alpha: Int): VertexConsumer =
    this.color(255, 255, 255, alpha)

/**
 * 1.21 的 setOverlay(int packedOverlay) —— 1.20.1 用 overlayCoords(u, v)
 * packed = (u << 16) | v
 */
fun VertexConsumer.setOverlay(packedOverlay: Int): VertexConsumer =
    this.overlayCoords(packedOverlay ushr 16, packedOverlay and 0xFFFF)

/**
 * 1.21 的 setUv1 / setUv2 的兼容别名（1.20.1 已有 overlayCoords / uv2）
 */
fun VertexConsumer.setUv1Compat(u: Int, v: Int): VertexConsumer = this.overlayCoords(u, v)
fun VertexConsumer.setUv2Compat(u: Int, v: Int): VertexConsumer = this.uv2(u, v)
