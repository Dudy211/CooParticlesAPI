package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos

interface ClientNetworking {

    fun send(packet: CustomPacketPayload)

}