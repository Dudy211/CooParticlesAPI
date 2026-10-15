package cn.coostack.cooparticlesapi.network.packet.server

import cn.coostack.cooparticlesapi.CooParticlesConstants
import net.minecraft.network.FriendlyByteBuf
import cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec
import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

class PacketKeyBindingCountdownS2C(val key: ResourceLocation, val cd: Int) : CustomPacketPayload {
    companion object {
        private val identifierID =
            ResourceLocation.fromNamespaceAndPath(CooParticlesConstants.MOD_ID, "key_binding_countdown")
        val payloadID = CustomPacketPayload.Type<PacketKeyBindingCountdownS2C>(identifierID)
        val CODEC: StreamCodec<FriendlyByteBuf, PacketKeyBindingCountdownS2C> =
            CustomPacketPayload.codec({ packet, buf ->
                buf.writeResourceLocation(packet.key)
                buf.writeInt(packet.cd)
            }, { buf ->
                PacketKeyBindingCountdownS2C(buf.readResourceLocation(), buf.readInt())
            })
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
        return payloadID
    }
}