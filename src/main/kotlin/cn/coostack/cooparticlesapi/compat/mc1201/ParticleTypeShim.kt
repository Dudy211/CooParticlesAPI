package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf

/**
 * 1.20.1 兼容层：粒子编解码。
 *
 * ⚠ 1.20.1 的 `ParticleOptions.Deserializer` 只有
 *   `fromNetwork(type, buf)` / `fromCommand(type, reader)`，
 * **没有 write** —— 写入要调 `ParticleOptions.writeToNetwork(buf)`。
 */
@JvmField
val particleOptionsStreamCodec: StreamCodec<FriendlyByteBuf, ParticleOptions> =
    StreamCodec.of(
        { buf, options ->

            buf.writeVarInt(BuiltInRegistries.PARTICLE_TYPE.getId(options.type))
            options.writeToNetwork(buf)
        },
        { buf ->
            val id = buf.readVarInt()
            val type = BuiltInRegistries.PARTICLE_TYPE.byId(id)
                ?: throw IllegalStateException("Unknown particle type id: $id")
            @Suppress("UNCHECKED_CAST")
            val t = type as ParticleType<ParticleOptions>
            t.deserializer.fromNetwork(t, buf)
        },
    )

/** 任意 ParticleType 的编解码器 */
@Suppress("UNCHECKED_CAST")
fun particleTypeStreamCodec(type: ParticleType<*>): StreamCodec<FriendlyByteBuf, ParticleOptions> {
    val t = type as ParticleType<ParticleOptions>
    return StreamCodec.of(
        { buf, options -> options.writeToNetwork(buf) },
        { buf -> t.deserializer.fromNetwork(t, buf) },
    )
}

/** 扩展属性形式（Kotlin receiver 可用时） */
val ParticleType<*>.STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleOptions>
    get() = particleTypeStreamCodec(this)

/** 便捷：从 buf 读 ParticleType */
fun readParticleType(buf: FriendlyByteBuf): ParticleType<*> =
    BuiltInRegistries.PARTICLE_TYPE.byId(buf.readVarInt())
        ?: throw IllegalStateException("Unknown particle type id in buffer")
