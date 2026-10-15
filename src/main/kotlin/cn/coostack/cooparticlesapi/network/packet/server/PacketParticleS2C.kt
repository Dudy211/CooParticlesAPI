package cn.coostack.cooparticlesapi.network.packet.server
import cn.coostack.cooparticlesapi.compat.mc1201.particleOptionsStreamCodec
import cn.coostack.cooparticlesapi.compat.mc1201.writeVec3
import cn.coostack.cooparticlesapi.compat.mc1201.readVec3

import cn.coostack.cooparticlesapi.CooParticlesConstants
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import cn.coostack.cooparticlesapi.compat.mc1201.RegistryFriendlyByteBuf
import cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec
import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 *
 */
class PacketParticleS2C(
    val type: ParticleOptions,
    val pos: Vec3,
    val velocity: Vec3,
) : CustomPacketPayload {
    companion object {
        private val identifierID = ResourceLocation.fromNamespaceAndPath(CooParticlesConstants.MOD_ID, "particle")
        val payloadID = CustomPacketPayload.Type<PacketParticleS2C>(identifierID)
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PacketParticleS2C> =
            CustomPacketPayload.codec({ packet, buf ->
                buf.writeVec3(packet.pos)
                buf.writeVec3(packet.velocity)
                particleOptionsStreamCodec.encode(buf, packet.type)
            }, { buf ->
                val pos = buf.readVec3()
                val velocity = buf.readVec3()
                val type = particleOptionsStreamCodec.decode(buf)
                return@codec PacketParticleS2C(type, pos, velocity)
            })

    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload?> {
        return payloadID
    }
}
