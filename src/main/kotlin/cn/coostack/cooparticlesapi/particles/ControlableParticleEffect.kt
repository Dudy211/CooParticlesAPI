package cn.coostack.cooparticlesapi.particles

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.Unpooled
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.network.FriendlyByteBuf
import cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec
import java.util.UUID

/**
 * 1.20.1 移植改动：
 * 1.20.1 的 ParticleOptions 接口要求 writeToNetwork(FriendlyByteBuf) 与 writeToString()，
 * 而 1.21.1 已移除（改用 StreamCodec）。
 * 在这里给出默认实现，126 个子类无需改动。
 */
abstract class ControlableParticleEffect(var controlUUID: UUID, val faceToPlayer: Boolean = true) : ParticleOptions {

    abstract fun getPacketCodec(): StreamCodec<FriendlyByteBuf, out ControlableParticleEffect>

    abstract fun clone(): ControlableParticleEffect



    /** 1.20.1: ParticleOptions.writeToNetwork */
    override fun writeToNetwork(buffer: FriendlyByteBuf) {
        @Suppress("UNCHECKED_CAST")
        (getPacketCodec() as StreamCodec<FriendlyByteBuf, ControlableParticleEffect>)
            .encode(buffer, this)
    }

    /** 1.20.1: ParticleOptions.writeToString —— 仅用于调试输出 */
    override fun writeToString(): String = buildString {
        append("ControlableParticleEffect{")
        append("uuid=").append(controlUUID)
        append(", faceToPlayer=").append(faceToPlayer)
        append('}')
    }
}
