package cn.coostack.cooparticlesapi.network

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.network.packet.api.CooClientPacketManager
import cn.coostack.cooparticlesapi.network.packet.api.CooServerPacketManager
import cn.coostack.cooparticlesapi.network.packet.api.envelope.CooPacketEnvelopeC2S
import cn.coostack.cooparticlesapi.network.packet.api.envelope.CooPacketEnvelopeS2C
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import java.util.function.Supplier

/**
 * Forge 1.20.1 的网络层。
 *
 * 上游用 1.20.5+ 的 `CustomPacketPayload` + `PayloadTypeRegistry`；1.20.1 没有，
 * 只有 Forge 的 SimpleChannel。
 *
 * 方案：**只开两个 envelope 通道**做中转：
 *   id=0  S2C —— 承载所有服务端 → 客户端的包
 *   id=1  C2S —— 承载所有客户端 → 服务端的包
 *
 * 业务包本身的反射编解码由上游 `CooPacketRegistry` 负责，这里只搬字节流。
 * 收包后交给上游的 `handleS2C` / `handleC2S`，保持与 Fabric/Neo 相同的接入点。
 */
object CooNetworkRegistry {

    private const val PROTOCOL_VERSION = "1"

    @JvmStatic
    val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation(CooParticlesConstants.MOD_ID, "main"),
        { PROTOCOL_VERSION },
        { it == PROTOCOL_VERSION },
        { it == PROTOCOL_VERSION },
    )

    private var registered = false

    /** 注册通道。由 Forge 入口在 common setup 时调用。 */
    @JvmStatic
    fun register() {
        if (registered) return
        registered = true


        CHANNEL.messageBuilder(CooPacketEnvelopeS2C::class.java, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet, buf ->
                buf.writeVarInt(packet.kindId)
                buf.writeResourceLocation(packet.packetId)
                buf.writeLong(packet.correlationId)
                buf.writeVarInt(packet.timeoutTicks)
                buf.writeByteArray(packet.data)
            }
            .decoder { buf ->
                CooPacketEnvelopeS2C(
                    kindId = buf.readVarInt(),
                    packetId = buf.readResourceLocation(),
                    correlationId = buf.readLong(),
                    timeoutTicks = buf.readVarInt(),
                    data = buf.readByteArray(),
                )
            }
            .consumerMainThread { packet, ctx -> handleS2C(packet, ctx) }
            .add()


        CHANNEL.messageBuilder(CooPacketEnvelopeC2S::class.java, 1, NetworkDirection.PLAY_TO_SERVER)
            .encoder { packet, buf ->
                buf.writeVarInt(packet.kindId)
                buf.writeResourceLocation(packet.packetId)
                buf.writeLong(packet.correlationId)
                buf.writeVarInt(packet.timeoutTicks)
                buf.writeByteArray(packet.data)
            }
            .decoder { buf ->
                CooPacketEnvelopeC2S(
                    kindId = buf.readVarInt(),
                    packetId = buf.readResourceLocation(),
                    correlationId = buf.readLong(),
                    timeoutTicks = buf.readVarInt(),
                    data = buf.readByteArray(),
                )
            }
            .consumerMainThread { packet, ctx -> handleC2S(packet, ctx) }
            .add()






        CHANNEL.messageBuilder(
            cn.coostack.cooparticlesapi.network.packet.compat.ForgeDirectPayloadC2S::class.java,
            2,
            NetworkDirection.PLAY_TO_SERVER,
        )
            .encoder { packet, buf ->
                cn.coostack.cooparticlesapi.network.packet.compat.ForgeDirectPayloadC2S.encode(packet, buf)
            }
            .decoder { buf ->
                cn.coostack.cooparticlesapi.network.packet.compat.ForgeDirectPayloadC2S.decode(buf)
            }
            .consumerMainThread { packet, ctx ->
                val context = ctx.get()
                val sender = context.sender
                if (sender != null) {
                    runCatching {
                        cn.coostack.cooparticlesapi.network.packet.compat.ForgePacketChannels
                            .handleC2S(packet.id, packet.data, sender)
                    }.onFailure {
                        CooParticlesConstants.logger.error("处理直通 C2S 包失败: {}", packet.id, it)
                    }
                }
                context.packetHandled = true
            }
            .add()

        CooParticlesConstants.logger.info("CooParticlesAPI 网络通道注册完成 (Forge SimpleChannel)")
    }



    private fun handleS2C(packet: CooPacketEnvelopeS2C, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        runCatching { CooClientPacketManager.handleS2C(packet) }
            .onFailure { CooParticlesConstants.logger.error("处理 S2C 包失败: {}", packet.packetId, it) }
        context.packetHandled = true
    }

    private fun handleC2S(packet: CooPacketEnvelopeC2S, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender
        if (sender == null) {
            CooParticlesConstants.logger.warn("C2S 包无发送者: {}", packet.packetId)
            context.packetHandled = true
            return
        }
        runCatching { CooServerPacketManager.handleC2S(packet, sender) }
            .onFailure { CooParticlesConstants.logger.error("处理 C2S 包失败: {}", packet.packetId, it) }
        context.packetHandled = true
    }



    @JvmStatic
    fun sendToPlayer(payload: CooPacketEnvelopeS2C, player: ServerPlayer) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, payload)
    }

    @JvmStatic
    fun sendToAll(payload: CooPacketEnvelopeS2C) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), payload)
    }

    @JvmStatic
    fun sendToTrackingChunk(world: ServerLevel, chunk: ChunkPos, payload: CooPacketEnvelopeS2C) {
        CHANNEL.send(
            PacketDistributor.TRACKING_CHUNK.with { world.getChunk(chunk.x, chunk.z) },
            payload,
        )
    }

    @JvmStatic
    fun sendToDimension(world: ServerLevel, payload: CooPacketEnvelopeS2C) {
        CHANNEL.send(PacketDistributor.DIMENSION.with { world.dimension() }, payload)
    }



    @JvmStatic
    fun sendToServer(payload: CooPacketEnvelopeC2S) {
        CHANNEL.sendToServer(payload)
    }

    /**
     * ★ 直通 C2S（B 类包，如 `PacketKeyActionC2S`）—— 走 id=2 通道。
     *
     * 上游在 1.20.5+ 上是 `PacketDistributor.sendToServer(packet)` 直接发；
     * 1.20.1 只能把它 encode 成「id + bytes」后走我们自己的直通通道。
     */
    @JvmStatic
    fun sendDirectToServer(payload: cn.coostack.cooparticlesapi.network.packet.compat.ForgeDirectPayloadC2S) {
        CHANNEL.sendToServer(payload)
    }
}
