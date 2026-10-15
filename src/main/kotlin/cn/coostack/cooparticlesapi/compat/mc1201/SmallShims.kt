package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.vertex.VertexConsumer

/**
 * 1.20.1 兼容层：集中放小体量的 API 补齐。
 *
 * ⚠ 注意：这些方法必须**返回 VertexConsumer**，
 * 否则链式调用（.vertexAt().normalAt().setLightCompat().vertexAt()...）会断掉。
 */

/**
 * 1.21 的 VertexConsumer.setLight(int packedLight)。
 * 1.20.1 用 uv2(u, v) 处理光照贴图坐标。
 * packed light = (blockLight << 16) | skyLight
 */
fun VertexConsumer.setLightCompat(packedLight: Int): VertexConsumer =
    this.uv2(packedLight and 0xFFFF, (packedLight shr 16) and 0xFFFF)
