package cn.coostack.cooparticlesapi.network.packet.server

import cn.coostack.cooparticlesapi.CooParticlesConstants
import net.minecraft.network.FriendlyByteBuf
import cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec
import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

object PacketClearClientStateS2C : CustomPacketPayload {
    private val identifierID =
        ResourceLocation.fromNamespaceAndPath(CooParticlesConstants.MOD_ID, "clear_client_state")

    val payloadID = CustomPacketPayload.Type<PacketClearClientStateS2C>(identifierID)
    val CODEC: StreamCodec<FriendlyByteBuf, PacketClearClientStateS2C> =
        CustomPacketPayload.codec({ _, _ -> }, { _ -> PacketClearClientStateS2C })

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
        return payloadID
    }
}
