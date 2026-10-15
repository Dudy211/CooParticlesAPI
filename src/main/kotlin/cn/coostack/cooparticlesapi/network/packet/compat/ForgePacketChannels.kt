package cn.coostack.cooparticlesapi.network.packet.compat

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.compat.mc1201.CustomPacketPayload
import cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec
import cn.coostack.cooparticlesapi.network.packet.api.CooPacketKind
import cn.coostack.cooparticlesapi.network.packet.api.envelope.CooPacketEnvelopeS2C
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

/**
 * ★★★ Forge 1.20.1 的「直通包」支持 —— 上游**第二类包**（自带 codec + 独立 Handler）的收发。
 *
 * <h2>背景：上游有两类包，用两套机制</h2>
 *
 * 上游跑在 1.20.5+ NeoForge 上，那里**每个 payload 自己注册 codec**
 * （`CooParticlesAPINeoModInitListener.onPacketReceiverInit` 里逐个建通道，共 20 条）。
 * 所以 `PacketDistributor.sendToXxx(player, packet)` **可以直接发业务包本身**。
 *
 * | | 形态 | 我们移植时 |
 * |---|---|---|
 * | **第一类** | 继承 `CooPacket` + `@CooAutoRegister`，由 `CooPacketRegistry` 反射编解码 | ✅ envelope 中转，正确 |
 * | **第二类** | 不继承 `CooPacket`，自带 `payloadID` + `CODEC` + 独立 Handler | ❌ **一条通道都没建** |
 *
 * 第二类包含 **`PacketKeyActionC2S`（蓄力就靠它）** 和**粒子/实体/音效同步那 18 个 S2C 包**。
 * 而且失败是**静默的**：`Forge*Networking` 里的 `as? CooPacketEnvelopeXxx` 强转失败 →
 * `?.let` 什么也不做 → 包被丢掉。
 *
 * <h2>做法：两条「字节直通」通道，而不是手写 20 条</h2>
 *
 * - **S2C**：这类包的**解码最终都归到 `CooPacketRegistry`**（「id → 编解码器」全局表），
 *   所以**复用已有 `CooPacketEnvelopeS2C` 通道**即可 —— 先 encode 成字节塞进信封，
 *   客户端 `CooClientPacketManager.handleS2C` 会照常 `decode` + 派发 `onClientReceive`。
 *   **客户端一行都不用改。**
 * - **C2S**：只有 `PacketKeyActionC2S` 一个，加一条 [ForgeDirectPayloadC2S] 通道，
 *   服务端按 id 转给 [`cn.coostack.cooparticlesapi.network.packet.server.listener.ServerKeyActionHandler`]。
 *
 * 这样以后上游再加这一类包，只要在下面的表里补一行。
 */
object ForgePacketChannels {



    /** 直通 S2C：payload id → CODEC */
    private val s2cCodecs: MutableMap<ResourceLocation, Any> = LinkedHashMap()

    /** 直通 C2S：payload id → CODEC */
    private val c2sCodecs: MutableMap<ResourceLocation, Any> = LinkedHashMap()

    /** 直通 C2S：payload id → 接收器（(payload, context) -> Unit） */
    private val c2sReceivers: MutableMap<ResourceLocation, (CustomPacketPayload, ServerPlayer) -> Unit> =
        LinkedHashMap()

    /** 直通 S2C：payload id → 客户端接收器 */
    private val s2cReceivers: MutableMap<ResourceLocation, (CustomPacketPayload) -> Unit> = LinkedHashMap()

    private var registered = false

    /**
     * 初始化登记表 —— 与上游 `onPacketReceiverInit` 的 20 条注册**一一对应**。
     *
     * 由 `CooParticlesAPIForge` 在 mod 构造期调用（必须在任何收包之前）。
     */
    @JvmStatic
    fun register() {
        if (registered) return
        registered = true


        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketRenderEntityS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketRenderEntityS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketRendererPostEffectS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketRendererPostEffectS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleGroupS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleGroupS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleEmittersS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleEmittersS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketDataHolderS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketDataHolderS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionStateS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionStateS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionRotateS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionRotateS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleStyleS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleStyleS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketParticleBatchS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketParticleBatchS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketCameraShakeS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketCameraShakeS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketClearClientStateS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketClearClientStateS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityStateS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityStateS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketKeyBindingCountdownS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketKeyBindingCountdownS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketSoundLoopS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketSoundLoopS2C.CODEC
        s2cCodecs[cn.coostack.cooparticlesapi.network.packet.server.PacketSoundInstanceS2C.payloadID.id] =
            cn.coostack.cooparticlesapi.network.packet.server.PacketSoundInstanceS2C.CODEC


        val keyActionId = cn.coostack.cooparticlesapi.network.packet.client.PacketKeyActionC2S.payloadID.id
        c2sCodecs[keyActionId] = cn.coostack.cooparticlesapi.network.packet.client.PacketKeyActionC2S.CODEC
        c2sReceivers[keyActionId] = { payload, sender ->
            cn.coostack.cooparticlesapi.network.packet.server.listener.ServerKeyActionHandler.receive(
                payload as cn.coostack.cooparticlesapi.network.packet.client.PacketKeyActionC2S,
                cn.coostack.cooparticlesapi.platform.network.ForgeServerContext(sender),
            )
        }

        CooParticlesConstants.logger.info(
            "Forge 直通包通道已登记: S2C {} 个 / C2S {} 个",
            s2cCodecs.size, c2sCodecs.size,
        )
    }

    /**
     * 登记 18 个 S2C 包对应的**客户端接收器** —— 与上游
     * `CooParticlesAPINeoModInitListener.onPacketReceiverInit` 里的 lambda 一一对应。
     *
     * 只在客户端环境调用（接收器全是客户端类）。
     */
    @JvmStatic
    fun registerClientReceivers() {
        if (s2cReceivers.isNotEmpty()) return
        val ctx = { cn.coostack.cooparticlesapi.platform.network.ForgeClientContext() }
        fun reg(
            id: ResourceLocation,
            h: (CustomPacketPayload) -> Unit,
        ) {
            s2cReceivers[id] = h
        }

        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketRenderEntityS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientRenderEntityPacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketRenderEntityS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketRendererPostEffectS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientRendererPostEffectHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketRendererPostEffectS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleGroupS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticleGroupPacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleGroupS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleEmittersS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticleEmittersPacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleEmittersS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketDataHolderS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientDataHolderPacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketDataHolderS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticleCompositionHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionStateS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticleCompositionStateHandler
                .receive(
                    it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionStateS2C, ctx()
                )
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionRotateS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticleCompositionRotateHandler
                .receive(
                    it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionRotateS2C, ctx()
                )
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleStyleS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticleStylePacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleStyleS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticlePacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketParticleBatchS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientParticlePacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketParticleBatchS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketCameraShakeS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientCameraShakeHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketCameraShakeS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketClearClientStateS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientClearStateHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketClearClientStateS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientDisplayEntityPacketHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityStateS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientDisplayEntityStateHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketDisplayEntityStateS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketKeyBindingCountdownS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientKeyBindingCountdownHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketKeyBindingCountdownS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketSoundLoopS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientSoundLoopHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketSoundLoopS2C, ctx())
        }
        reg(cn.coostack.cooparticlesapi.network.packet.server.PacketSoundInstanceS2C.payloadID.id) {
            cn.coostack.cooparticlesapi.network.packet.client.listener.ClientSoundInstanceHandler
                .receive(it as cn.coostack.cooparticlesapi.network.packet.server.PacketSoundInstanceS2C, ctx())
        }

        CooParticlesConstants.logger.info("Forge 直通 S2C 接收器已登记: {} 个", s2cReceivers.size)
    }

    /**
     * 客户端收到直通 S2C 包：按 id 解码并交给接收器。
     *
     * 由 `CooClientPacketManager.handleS2CInternal` 在「A 类解不出来」时回退调用。
     *
     * @return true = 这个 id 属于直通包（已处理或已报错）
     */
    @JvmStatic
    fun handleS2C(id: ResourceLocation, data: ByteArray): Boolean {
        val codec = s2cCodecs[id] ?: return false
        val receiver = s2cReceivers[id] ?: return false
        @Suppress("UNCHECKED_CAST")
        val payload = runCatching {
            decodeFromBytes(codec as StreamCodec<FriendlyByteBuf, Any>, data)
        }.getOrElse {
            CooParticlesConstants.logger.error("直通 S2C 包解码失败: {}", id, it)
            return true
        }
        runCatching { receiver(payload as CustomPacketPayload) }
            .onFailure { CooParticlesConstants.logger.error("直通 S2C 包处理失败: {}", id, it) }
        return true
    }



    /**
     * 发送一个**第二类** S2C 包（服务端 → 客户端）。
     *
     * @return true = 已按直通路径投递；false = 不是已知的直通包（调用方走原逻辑）
     */
    @JvmStatic
    fun sendToPlayer(packet: CustomPacketPayload, player: ServerPlayer): Boolean {
        val envelope = toEnvelope(packet) ?: return false
        cn.coostack.cooparticlesapi.network.CooNetworkRegistry.sendToPlayer(envelope, player)
        return true
    }

    @JvmStatic
    fun sendToAll(packet: CustomPacketPayload): Boolean {
        val envelope = toEnvelope(packet) ?: return false
        cn.coostack.cooparticlesapi.network.CooNetworkRegistry.sendToAll(envelope)
        return true
    }

    @JvmStatic
    fun sendToTrackingChunk(
        packet: CustomPacketPayload,
        world: net.minecraft.server.level.ServerLevel,
        chunk: net.minecraft.world.level.ChunkPos,
    ): Boolean {
        val envelope = toEnvelope(packet) ?: return false
        cn.coostack.cooparticlesapi.network.CooNetworkRegistry.sendToTrackingChunk(world, chunk, envelope)
        return true
    }

    @JvmStatic
    fun sendToDimension(packet: CustomPacketPayload, world: net.minecraft.server.level.ServerLevel): Boolean {
        val envelope = toEnvelope(packet) ?: return false
        cn.coostack.cooparticlesapi.network.CooNetworkRegistry.sendToDimension(world, envelope)
        return true
    }

    /** 客户端 → 服务端（第二类 C2S） */
    @JvmStatic
    fun sendToServer(packet: CustomPacketPayload): Boolean {
        val id = packet.type().id
        val codec = c2sCodecs[id] ?: return false
        @Suppress("UNCHECKED_CAST")
        val bytes = encodeToBytes(codec as StreamCodec<FriendlyByteBuf, Any>, packet)
        cn.coostack.cooparticlesapi.network.CooNetworkRegistry.sendDirectToServer(
            ForgeDirectPayloadC2S(id, bytes)
        )
        return true
    }

    /** 服务端收到直通 C2S：按 id 分派给接收器 */
    @JvmStatic
    fun handleC2S(id: ResourceLocation, data: ByteArray, sender: ServerPlayer) {
        val codec = c2sCodecs[id]
        val receiver = c2sReceivers[id]
        if (codec == null || receiver == null) {
            CooParticlesConstants.logger.warn("收到未知的直通 C2S 包: {}", id)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val payload = runCatching {
            decodeFromBytes(codec as StreamCodec<FriendlyByteBuf, Any>, data)
        }.getOrElse {
            CooParticlesConstants.logger.error("直通 C2S 包解码失败: {}", id, it)
            return
        }
        receiver(payload as CustomPacketPayload, sender)
    }



    @Suppress("UNCHECKED_CAST")
    private fun toEnvelope(packet: CustomPacketPayload): CooPacketEnvelopeS2C? {
        val id = packet.type().id
        val codec = s2cCodecs[id] ?: return null
        return runCatching {
            CooPacketEnvelopeS2C(
                kindId = CooPacketKind.NORMAL.id,
                packetId = id,
                correlationId = 0L,
                timeoutTicks = 0,
                data = encodeToBytes(codec as StreamCodec<FriendlyByteBuf, Any>, packet),
            )
        }.getOrElse {
            CooParticlesConstants.logger.error("直通 S2C 包编码失败: {}", id, it)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun encodeToBytes(codec: StreamCodec<FriendlyByteBuf, Any>, packet: Any): ByteArray {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        codec.encode(buf, packet)
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        buf.release()
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeFromBytes(codec: StreamCodec<FriendlyByteBuf, Any>, data: ByteArray): Any {
        val buf = FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data))
        return try {
            codec.decode(buf)
        } finally {
            buf.release()
        }
    }
}
