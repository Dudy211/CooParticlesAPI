package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.item.ItemStack

import net.minecraft.core.RegistryAccess
import io.netty.buffer.ByteBuf

/**
 * 1.20.1 兼容层：模拟 1.20.5+ 的 RegistryFriendlyByteBuf。
 *
 * 1.20.1 没有这个类。它的作用是「带注册表上下文的缓冲」，
 * 用于 ItemStack 等需要注册表才能序列化的类型。
 *
 * 在 1.20.1 里 ItemStack 直接 writeItem/readItem 就能用，
 * 所以这里只需提供一个能承载同样读写能力的实现。
 */
open class RegistryFriendlyByteBuf(
    buf: ByteBuf,
    val registryAccess: RegistryAccess,
) : FriendlyByteBuf(buf) {

    companion object {
        @JvmStatic
        fun create(buf: FriendlyByteBuf, access: RegistryAccess): RegistryFriendlyByteBuf =
            RegistryFriendlyByteBuf(buf, access)
    }
}
