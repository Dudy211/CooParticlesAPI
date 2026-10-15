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

class PacketParticleBatchS2C(
    val type: ParticleOptions,
    val positions: List<Vec3>,
    val velocity: Vec3,
) : CustomPacketPayload {
    init {
        require(positions.size <= MAX_PARTICLES) { "particle batch exceeds $MAX_PARTICLES entries" }
    }

    companion object {
        const val MAX_PARTICLES = 4096

        private val identifierID = ResourceLocation.fromNamespaceAndPath(CooParticlesConstants.MOD_ID, "particle_batch")
        val payloadID = CustomPacketPayload.Type<PacketParticleBatchS2C>(identifierID)
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PacketParticleBatchS2C> =
            CustomPacketPayload.codec({ packet, buf ->
                particleOptionsStreamCodec.encode(buf, packet.type)
                buf.writeVec3(packet.velocity)
                buf.writeVarInt(packet.positions.size)
                packet.positions.forEach { buf.writeVec3(it) }
            }, { buf ->
                val type = particleOptionsStreamCodec.decode(buf)
                val velocity = buf.readVec3()
                val size = buf.readVarInt()
                require(size in 0..MAX_PARTICLES) { "invalid particle batch size: $size" }
                val positions = List(size) { buf.readVec3() }
                PacketParticleBatchS2C(type, positions, velocity)
            })
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = payloadID
}
