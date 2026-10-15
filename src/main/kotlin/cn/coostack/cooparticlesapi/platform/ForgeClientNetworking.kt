package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import cn.coostack.cooparticlesapi.network.CooNetworkRegistry
import cn.coostack.cooparticlesapi.network.packet.api.envelope.CooPacketEnvelopeC2S
import cn.coostack.cooparticlesapi.network.packet.compat.ForgePacketChannels

/**
 * Forge 1.20.1 的客户端网络实现（客户端 → 服务端）。
 *
 * ⚠️ **两类包走不同路径**（上游在 1.20.5+ 上是 PacketDistributor 一把梭，
 * 因为那里每个 payload 都有自己的通道；1.20.1 只有 SimpleChannel，必须分开）：
 *
 * | 包类型 | 走法 |
 * |---|---|
 * | **A 类**（继承 `CooPacket`） | `CooClientPacketManager` 已自行 encode 成字节 → `CooPacketEnvelopeC2S` → envelope 通道 |
 * | **B 类**（自带 codec，如 `PacketKeyActionC2S`） | **[ForgePacketChannels.sendToServer] 直通通道** |
 *
 * ⚠️ 关键：**不能**像以前那样只 `as? CooPacketEnvelopeC2S` —— 那会把 B 类包
 * **静默丢掉**（强转失败 → `?.let` 什么也不做），
 * 而 `PacketKeyActionC2S`（蓄力）正是 B 类。
 */
class ForgeClientNetworking : ClientNetworking {

    override fun send(packet: CustomPacketPayload) {

        if (ForgePacketChannels.sendToServer(packet)) {
            return
        }

        (packet as? CooPacketEnvelopeC2S)?.let { CooNetworkRegistry.sendToServer(it) }
    }
}
