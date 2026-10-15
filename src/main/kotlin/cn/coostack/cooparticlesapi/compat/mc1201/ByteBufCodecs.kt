package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * 1.20.1 兼容层：模拟 1.20.5+ 的 ByteBufCodecs。
 *
 * 上游用它做基础类型 codec，这里以 FriendlyByteBuf 实现等价功能。
 */
object ByteBufCodecs {

    @JvmField val BOOL: StreamCodec<FriendlyByteBuf, Boolean> =
        StreamCodec.of({ b, v -> b.writeBoolean(v) }, { b -> b.readBoolean() })

    @JvmField val BYTE: StreamCodec<FriendlyByteBuf, Byte> =
        StreamCodec.of({ b, v -> b.writeByte(v.toInt()) }, { b -> b.readByte() })

    @JvmField val SHORT: StreamCodec<FriendlyByteBuf, Short> =
        StreamCodec.of({ b, v -> b.writeShort(v.toInt()) }, { b -> b.readShort() })

    @JvmField val INT: StreamCodec<FriendlyByteBuf, Int> =
        StreamCodec.of({ b, v -> b.writeInt(v) }, { b -> b.readInt() })

    @JvmField val VAR_INT: StreamCodec<FriendlyByteBuf, Int> =
        StreamCodec.of({ b, v -> b.writeVarInt(v) }, { b -> b.readVarInt() })

    @JvmField val LONG: StreamCodec<FriendlyByteBuf, Long> =
        StreamCodec.of({ b, v -> b.writeLong(v) }, { b -> b.readLong() })

    @JvmField val FLOAT: StreamCodec<FriendlyByteBuf, Float> =
        StreamCodec.of({ b, v -> b.writeFloat(v) }, { b -> b.readFloat() })

    @JvmField val DOUBLE: StreamCodec<FriendlyByteBuf, Double> =
        StreamCodec.of({ b, v -> b.writeDouble(v) }, { b -> b.readDouble() })

    @JvmField val STRING_UTF8: StreamCodec<FriendlyByteBuf, String> =
        StreamCodec.of({ b, v -> b.writeUtf(v) }, { b -> b.readUtf() })

    @JvmField val UUID_CODEC: StreamCodec<FriendlyByteBuf, UUID> =
        StreamCodec.of({ b, v -> b.writeUUID(v) }, { b -> b.readUUID() })

    @JvmField val RESOURCE_LOCATION: StreamCodec<FriendlyByteBuf, ResourceLocation> =
        StreamCodec.of(
            { b, v -> b.writeResourceLocation(v) },
            { b -> b.readResourceLocation() },
        )

    /** 数组 / 集合辅助 */
    @JvmStatic
    fun <T> list(codec: StreamCodec<FriendlyByteBuf, T>): StreamCodec<FriendlyByteBuf, List<T>> =
        StreamCodec.of(
            { b, v ->
                b.writeVarInt(v.size)
                v.forEach { codec.encode(b, it) }
            },
            { b ->
                val n = b.readVarInt()
                List(n) { codec.decode(b) }
            },
        )
}
