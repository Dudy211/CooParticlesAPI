package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity

import net.minecraft.core.RegistryAccess

/**
 * 1.20.1 兼容层：给 Entity 补 registryAccess()。
 *
 * 1.20.5+ Entity 有 registryAccess()；1.20.1 得走 level().registryAccess()。
 * 上游用法（ClientDisplayEntityPacketHandler.kt:44）：
 *   `Minecraft.getInstance().player!!.registryAccess()`
 */
fun Entity.registryAccess(): RegistryAccess = this.level().registryAccess()
