package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import cn.coostack.cooparticlesapi.network.CooNetworkRegistry
import cn.coostack.cooparticlesapi.network.packet.api.envelope.CooPacketEnvelopeS2C
import cn.coostack.cooparticlesapi.network.packet.compat.ForgePacketChannels
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos

/**
 * Forge 1.20.1 的服务端网络实现。
 *
 * ⚠️ **两类包走不同路径**（同 [ForgeClientNetworking] 的说明）：
 * - **A 类**（继承 `CooPacket`）→ 已是 `CooPacketEnvelopeS2C` → envelope 通道
 * - **B 类**（自带 codec，如 `PacketParticleS2C` / `PacketParticleEmittersS2C` …）
 *   → **[ForgePacketChannels] 直通**（encode 成字节后塞进同一条 envelope 通道，
 *     客户端 `CooClientPacketManager` 解不出来时会回退到直通分发表）
 *
 * ⚠️ 以前只 `as? CooPacketEnvelopeS2C` 会把**所有 B 类 S2C 包静默丢掉** ——
 * 而**粒子/发射器/组合/实体/音效同步全是 B 类**，所以粒子根本到不了客户端。
 */
class ForgeServerNetworking : ServerNetworking {

    override fun send(packet: CustomPacketPayload, to: ServerPlayer) {
        if (ForgePacketChannels.sendToPlayer(packet, to)) return
        (packet as? CooPacketEnvelopeS2C)?.let { CooNetworkRegistry.sendToPlayer(it, to) }
    }

    override fun sendAllPlayers(packet: CustomPacketPayload) {
        if (ForgePacketChannels.sendToAll(packet)) return
        (packet as? CooPacketEnvelopeS2C)?.let { CooNetworkRegistry.sendToAll(it) }
    }

    override fun sendToPlayersTrackingChunk(
        world: ServerLevel,
        chunk: ChunkPos,
        packet: CustomPacketPayload,
    ) {
        if (ForgePacketChannels.sendToTrackingChunk(packet, world, chunk)) return
        (packet as? CooPacketEnvelopeS2C)?.let {
            CooNetworkRegistry.sendToTrackingChunk(world, chunk, it)
        }
    }

    override fun sendToWorld(world: ServerLevel, packet: CustomPacketPayload) {
        if (ForgePacketChannels.sendToDimension(packet, world)) return
        (packet as? CooPacketEnvelopeS2C)?.let { CooNetworkRegistry.sendToDimension(world, it) }
    }
}
