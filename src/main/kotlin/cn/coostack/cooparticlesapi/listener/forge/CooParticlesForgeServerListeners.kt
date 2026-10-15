package cn.coostack.cooparticlesapi.listener.forge

import cn.coostack.cooparticlesapi.CooParticlesAPI
import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.commands.APICommand
import cn.coostack.cooparticlesapi.event.CooEventBus
import cn.coostack.cooparticlesapi.event.events.server.ServerPostTickEvent
import cn.coostack.cooparticlesapi.event.events.server.ServerPreTickEvent
import cn.coostack.cooparticlesapi.network.particle.composition.manager.ParticleCompositionManager
import cn.coostack.cooparticlesapi.network.particle.emitters.ParticleEmittersManager
import cn.coostack.cooparticlesapi.performance.PerformanceStatusServerRequestGate
import cn.coostack.cooparticlesapi.test.TestManager
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

/**
 * CooParticlesAPI 的**服务端**事件监听 —— 对应上游 NeoForge 侧的
 * `listener/server/CooParticlesAPINeoServerListener.kt`。
 *
 * <h2>⚠️⚠️⚠️ 这个文件的缺失，会一次性造成「道具点不动 + 所有粒子都不出现」</h2>
 *
 * 上游在 `ServerTickEvent.Pre` 里调用 **`CooParticlesAPI.tickServer(server)`**，
 * 而 `tickServer` 驱动的是**整个服务端粒子管线**：
 *
 * ```kotlin
 * fun tickServer(server: MinecraftServer) {
 *     ...
 *     ServerParticleGroupManager.upgrade()
 *     ParticleStyleManager.doTickServer()
 *     ParticleEmittersManager.doTickServer()
 *     BarrageManager.doTick()
 *     DataHolderManager.tick()
 *     ServerRenderEntityManager.tick()
 *     scheduler.doTick()
 *     ServerSoundLoopManager.tick()
 *     ServerSoundManager.tick()
 *     DisplayEntityManager.tickServer()
 *     ParticleCompositionManager.tickServer()
 *     AnimateManager.tickServer()
 *     TestManager.doTickServer()          // ← ★ 测试序列靠它推进
 *     CooTerrainEffectManager.tick(server)
 *     CooTerrainMappingManager.tick(server)
 *     CooServerPacketManager.tick()
 * }
 * ```
 *
 * 移植时**整个服务端监听类没搬** → `tickServer` 从不执行 →
 *
 * 1. **`TestManager.doTickServer()` 不跑**，测试序列永远停在第一步；
 *    测试工具 `use()` 里 `TestManager.startTest(...)` 起的序列推进不了，
 *    表现就是「点了没反应」。
 * 2. 服务端**一个粒子都不会生成**，也不会通过 `CooServerPacketManager` 发给客户端，
 *    于是**所有粒子测试都没有粒子**。
 * 3. `APICommand.register` 没注册 → 所有 `/coo*` 命令不存在。
 * 4. `onServerStart` 没调 → `CooParticlesAPI.server` 一直是 null。
 *
 * 注意 **[`ServerTickEvent`] 的 `Pre` 阶段**才是 `tickServer` 的时机（与上游一致）。
 */
object CooParticlesForgeServerListeners {

    /** 由 `CooParticlesAPIForge` 在 mod 构造期调用 */
    fun register() {
        FORGE_BUS.addListener(::onRegisterCommands)
        FORGE_BUS.addListener(::onServerStarting)
        FORGE_BUS.addListener(::onServerStopped)
        FORGE_BUS.addListener(::onServerTickPre)
        FORGE_BUS.addListener(::onServerTickPost)
        FORGE_BUS.addListener(::onPlayerLoggedOut)
        CooParticlesConstants.logger.info("CooParticlesAPI Forge 服务端监听器已注册")
    }

    /** 上游 `CooParticlesAPINeoServerListener.onRegisterCommands` */
    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        runCatching { APICommand.register(event.dispatcher) }
            .onFailure { CooParticlesConstants.logger.error("注册命令失败", it) }
    }

    /** 上游 `onServerLoad` */
    private fun onServerStarting(event: ServerStartingEvent) {
        runCatching { CooParticlesAPI.onServerStart(event.server) }
            .onFailure { CooParticlesConstants.logger.error("onServerStart 失败", it) }
    }

    /** 上游 `onServerStopped` */
    private fun onServerStopped(event: ServerStoppedEvent) {
        runCatching { CooParticlesAPI.onServerStop() }
            .onFailure { CooParticlesConstants.logger.error("onServerStop 失败", it) }
    }

    /**
     * ★★ 最关键的一处：上游 `onServerTick(ServerTickEvent.Pre)`。
     *
     * `CooEventBus.call(ServerPreTickEvent)` + **`CooParticlesAPI.tickServer(server)`**。
     */
    private fun onServerTickPre(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        runCatching {
            CooEventBus.call(ServerPreTickEvent(event.server))
            CooParticlesAPI.tickServer(event.server)
        }.onFailure {
            CooParticlesConstants.logger.error("服务端 tick 失败", it)
        }
    }

    /** 上游 `onServerStart(ServerTickEvent.Post)` */
    private fun onServerTickPost(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        runCatching {
            CooEventBus.call(ServerPostTickEvent(event.server))
        }.onFailure {
            CooParticlesConstants.logger.error("服务端 post tick 失败", it)
        }
    }

    /** 上游 `onPlayerLogout`：清理该玩家的服务端追踪状态 */
    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        runCatching {
            val player = event.entity
            if (player.level().isClientSide) return
            PerformanceStatusServerRequestGate.forget(player.uuid)
            TestManager.clearServerFor(player)
            ParticleCompositionManager.clearVisibleFor(player)
            ParticleEmittersManager.clearVisibleFor(player)
        }.onFailure {
            CooParticlesConstants.logger.error("玩家登出清理失败", it)
        }
    }
}
