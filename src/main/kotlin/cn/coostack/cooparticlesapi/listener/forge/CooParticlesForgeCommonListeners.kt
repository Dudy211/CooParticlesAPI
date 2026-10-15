package cn.coostack.cooparticlesapi.listener.forge

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.event.CooEventBus
import cn.coostack.cooparticlesapi.event.events.entity.EntityPrePlaceBlockEvent
import cn.coostack.cooparticlesapi.event.events.entity.EntityUnloadType
import cn.coostack.cooparticlesapi.event.events.entity.player.PlayerDisconnectEvent
import cn.coostack.cooparticlesapi.event.events.entity.player.ServerPlayerDeathEvent
import cn.coostack.cooparticlesapi.event.events.entity.player.ServerPlayerRespawnEvent
import cn.coostack.cooparticlesapi.network.particle.composition.manager.ParticleCompositionManager
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

/**
 * CooParticlesAPI 的**双端通用**事件监听 —— 对应上游 NeoForge 侧的
 * `listener/PlayerListener.kt`。
 *
 * <h2>⚠️ 为什么必须有这个文件</h2>
 *
 * 上游把它放在 `neoforge/`（平台侧），移植时**平台侧目录整个没搬**，只搬了 `common/`。
 * 后果：这些类里 `CooEventBus.call(...)` 派发的自定义事件**永远不会被触发**，
 * 于是所有依赖它们的功能静默失效（插件的扩展点全部失效）。
 *
 * | 自定义事件 | 上游触发点 |
 * |---|---|
 * | `EntityPrePlaceBlockEvent` | `PlayerInteractEvent.RightClickBlock` |
 * | `ServerPlayerDeathEvent` | `LivingDeathEvent` |
 * | `ServerPlayerRespawnEvent` | `PlayerEvent.PlayerRespawnEvent` |
 * | `PlayerDisconnectEvent` | `PlayerEvent.PlayerLoggedOutEvent` |
 *
 * ⚠️ 1.20.1 的取消语义与 NeoForge 不同：这里用
 * `event.isCanceled = true` + `event.cancellationResult = InteractionResult.FAIL`
 * （`cancellationResult` 的类型是 `net.minecraftforge.eventbus.api.Event.Result`，
 * 赋值 `InteractionResult` 时由 Forge 的重载 setter 接收）。
 */
object CooParticlesForgeCommonListeners {

    /** 由 `CooParticlesAPIForge` 在 mod 构造期调用 */
    fun register() {
        FORGE_BUS.addListener(::onRightClickBlock)
        FORGE_BUS.addListener(::onLivingDeath)
        FORGE_BUS.addListener(::onPlayerRespawn)
        FORGE_BUS.addListener(::onPlayerLoggedOut)
        CooParticlesConstants.logger.info("CooParticlesAPI Forge 通用监听器已注册")
    }

    /** 上游 `PlayerListener.onRightClickBlock`：派发「放置方块前」事件，可拦截。 */
    private fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        runCatching {
            val player: Player = event.entity
            val face = event.face ?: return
            val cooEvent = EntityPrePlaceBlockEvent(
                player,
                event.level,
                event.pos,
                face,
            )
            if (CooEventBus.call(cooEvent).isCancelled) {
                event.isCanceled = true
                event.cancellationResult = InteractionResult.FAIL
            }
        }.onFailure {
            CooParticlesConstants.logger.error("RightClickBlock 处理失败", it)
        }
    }

    /** 上游 `PlayerListener.playerDeath` */
    private fun onLivingDeath(event: LivingDeathEvent) {
        runCatching {
            val entity = event.entity
            if (entity !is Player) return
            event.isCanceled = CooEventBus.call(
                ServerPlayerDeathEvent(entity, entity.level(), event.source)
            ).isCancelled
        }.onFailure {
            CooParticlesConstants.logger.error("LivingDeathEvent 处理失败", it)
        }
    }

    /** 上游 `PlayerListener.playerRespawn` */
    private fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        runCatching {
            val entity: Player = event.entity
            val world = entity.level()
            if (!world.isClientSide) {
                ParticleCompositionManager.clearVisibleFor(entity)
                CooEventBus.call(ServerPlayerRespawnEvent(entity, world))
            }
        }.onFailure {
            CooParticlesConstants.logger.error("PlayerRespawnEvent 处理失败", it)
        }
    }

    /** 上游 `PlayerListener.playerLoggedOut` */
    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        runCatching {
            val entity: Player = event.entity
            CooEventBus.call(PlayerDisconnectEvent(entity, EntityUnloadType.QUIT))
        }.onFailure {
            CooParticlesConstants.logger.error("PlayerLoggedOutEvent 处理失败", it)
        }
    }
}
