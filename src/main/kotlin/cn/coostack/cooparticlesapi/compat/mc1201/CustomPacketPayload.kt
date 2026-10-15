package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

/**
 * 1.20.1 兼容层：模拟 1.20.5+ 的 CustomPacketPayload。
 *
 * ⚠️ 这个 shim 只解决「编译期」问题，让上游代码能通过编译。
 * 1.20.1 没有原生的 payload 注册/收发体系（那是 1.20.5+ 的），
 * 实际的网络收发必须走 Forge 的 SimpleChannel。
 */
interface CustomPacketPayload {

    /**
     * payload 类型标识。
     *
     * 注意上游写的是 `Type<out CustomPacketPayload?>`（可空），
     * 所以这里返回可空泛型以匹配。
     */
    fun type(): Type<out CustomPacketPayload?>

    /** payload 类型（原版是 record Type(ResourceLocation id)） */
    class Type<T : CustomPacketPayload?> @JvmOverloads constructor(val id: ResourceLocation)

    companion object {
        /**
         * 构造 payload codec。
         * 原版签名是 encoder(T, B) —— **值在前、缓冲在后**。
         */
        @JvmStatic
        fun <B : FriendlyByteBuf, T : CustomPacketPayload> codec(
            encoder: StreamMemberEncoder<B, T>,
            decoder: StreamDecoder<B, T>,
        ): StreamCodec<B, T> = StreamCodec.of(
            { buf, value -> encoder.encode(value, buf) },
            { buf -> decoder.decode(buf) },
        )
    }

    /** 值在前的编码器（原版语义） */
    fun interface StreamMemberEncoder<B : FriendlyByteBuf, T> {
        fun encode(value: T, buffer: B)
    }

    fun interface StreamDecoder<B : FriendlyByteBuf, T> {
        fun decode(buffer: B): T
    }
}
