package cn.coostack.cooparticlesapi.platform.network

import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

/**
 * Forge 1.20.1 的服务端包上下文 —— 对应上游 `NeoForgeServerContext`。
 *
 * ⚠️ 同 [ForgeClientContext]：上游在平台侧目录，移植时没搬。
 * 缺了它，`ServerKeyActionHandler`（蓄力的服务端半边）拿到 context 也用不了。
 *
 * 1.20.1 的 SimpleChannel 回调里能直接拿到 `NetworkEvent.Context`
 * （→ `sender`），所以这里直接持有 [ServerPlayer]。
 */
class ForgeServerContext(
    private val sender: ServerPlayer,
) : ServerContext {

    override fun player(): Player = sender

    override fun server(): MinecraftServer = sender.server

    override fun reply(packet: CustomPacketPayload) {


        cn.coostack.cooparticlesapi.network.packet.compat.ForgePacketChannels.sendToPlayer(packet, sender)
    }
}
