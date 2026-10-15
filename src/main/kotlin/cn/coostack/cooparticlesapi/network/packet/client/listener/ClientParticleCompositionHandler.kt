package cn.coostack.cooparticlesapi.network.packet.client.listener

import cn.coostack.cooparticlesapi.compat.mc1201.registryAccess
import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.network.packet.server.PacketParticleCompositionS2C
import cn.coostack.cooparticlesapi.network.particle.composition.ParticleComposition
import cn.coostack.cooparticlesapi.network.particle.composition.manager.ParticleCompositionManager
import cn.coostack.cooparticlesapi.platform.network.ClientContext
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import cn.coostack.cooparticlesapi.compat.mc1201.RegistryFriendlyByteBuf
import java.util.concurrent.ConcurrentHashMap

object ClientParticleCompositionHandler {
    private val missingCodecWarnings = ConcurrentHashMap.newKeySet<String>()
    private val identityMismatchWarnings = ConcurrentHashMap.newKeySet<String>()

    fun receive(
        payload: PacketParticleCompositionS2C,
        context: ClientContext
    ) {
        context.client().execute {
            apply(payload, context)
        }
    }

    private fun apply(payload: PacketParticleCompositionS2C, context: ClientContext) {
        val distanceRemove = payload.distanceRemove

        var new: ParticleComposition? = null
        val old = ParticleCompositionManager.clientView[payload.uuid]
        if (!distanceRemove) {

            new = decodeData(payload) ?: return
            if (new.controlUUID != payload.uuid && identityMismatchWarnings.add(payload.type)) {
                CooParticlesConstants.logger.warn(
                    "Composition 网络 UUID 不一致，已采用数据包 UUID: type={}, packet={}, decoded={}",
                    payload.type,
                    payload.uuid,
                    new.controlUUID,
                )
            }
            new.controlUUID = payload.uuid
            new.world = context.player().level()
        }
        if (old == null && !distanceRemove) {
            ParticleCompositionManager.addClient(new!!)

            return
        }
        val current = old ?: return

        if (distanceRemove) {
            current.remove()
        } else {
            if (payload.recreate || current.canceled || !current.displayed) {
                current.clear(true)
                ParticleCompositionManager.addClient(new!!)
                return
            }

            current.update(new!!)
        }
    }

    private fun decodeData(payload: PacketParticleCompositionS2C): ParticleComposition? {
        val data = payload.data
        val type = payload.type
        val codec = ParticleCompositionManager.registeredTypes[type] ?: run {
            if (missingCodecWarnings.add(type)) {
                CooParticlesConstants.logger.warn(
                    "收到未注册的 Composition codec: type={}, uuid={}",
                    type,
                    payload.uuid,
                )
            }
            return null
        }
        val buffer = RegistryFriendlyByteBuf(
            Unpooled.wrappedBuffer(data),
            Minecraft.getInstance().player!!.registryAccess()
        )
        return try {
            codec.decode(buffer)
        } finally {
            buffer.release()
        }
    }

}
