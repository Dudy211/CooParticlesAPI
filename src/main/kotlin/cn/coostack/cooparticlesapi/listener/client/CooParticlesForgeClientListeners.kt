package cn.coostack.cooparticlesapi.listener.client

import cn.coostack.cooparticlesapi.CooParticlesAPIClient
import cn.coostack.cooparticlesapi.CooShaderReloadSupport
import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.event.CooEventBus
import cn.coostack.cooparticlesapi.event.events.client.ClientPostTickEvent
import cn.coostack.cooparticlesapi.event.events.client.ClientPreTickEvent
import cn.coostack.cooparticlesapi.event.events.client.ClientStartEvent
import cn.coostack.cooparticlesapi.event.events.world.client.ClientWorldChangeEvent
import cn.coostack.cooparticlesapi.event.events.world.client.ClientWorldPostTickEvent
import cn.coostack.cooparticlesapi.event.events.world.client.ClientWorldPreTickEvent
import cn.coostack.cooparticlesapi.key.CooKeyBindingManager
import cn.coostack.cooparticlesapi.particles.CooModParticles
import cn.coostack.cooparticlesapi.particles.impl.particles.SimpleControlableSpriteParticle
import cn.coostack.cooparticlesapi.particles.impl.particles.*
import cn.coostack.cooparticlesapi.platform.registry.CommonDeferredRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleType
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RegisterParticleProvidersEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

/**
 * CooParticlesAPI 的 Forge 1.20.1 客户端事件监听（对应上游的 4 个 `@EventBusSubscriber`）。
 *
 * <h2>⚠️ 为什么必须有这个文件（真机症状：**所有粒子都不出现**）</h2>
 *
 * 上游把这些注册拆在 NeoForge 侧的四个类里：
 *
 * | 上游 | 作用 |
 * |---|---|
 * | `CooParticlesNeoClientInitialize` | `FMLClientSetupEvent` → `CooParticlesAPIClient.init()`；**`RegisterParticleProvidersEvent` → 92 个粒子 provider** |
 * | `CooParticlesAPINeoClientModListener` | `LevelTickEvent.Pre/Post` → **`tickClient(level)`**（粒子系统/CParticle/CooFX/动画的 tick） |
 * | `CooParticlesAPINeoClientListener` | 断线 / 换世界 / 客户端 tick / 启动事件 |
 * | `CooParticlesAPINeoClientRegistration` | 按键绑定 + shader reload 监听 |
 *
 * 移植到 Forge 时**只搬了 `CooParticlesAPIClient.init()`**，上面这些**一个都没挂**。后果：
 *
 * 1. **没有 world tick** → `CParticleSystemManager.tick()` 从不执行 → 粒子系统永不更新
 *    → **所有粒子都不显示**（测试工具也全都没反应）。
 * 2. **没有 `RegisterParticleProviders`** → 92 个粒子类型没有 provider
 *    → 即使 tick 了也渲染不出来。
 *
 * 注意 `CooModParticles.reg()` 在本项目里是**空实现**（它在上游只是个给平台侧挂
 * `RegisterEvent` 的钩子），所以 provider 注册只能放在这里。
 *
 * MOD_BUS = mod 事件总线（注册类事件）；FORGE_BUS = 游戏总线（tick/世界/入退服）。
 */
object CooParticlesForgeClientListeners {

    /** 由 `CooParticlesAPIForgeClient.setupClient()` 在客户端调用 */
    fun register() {

        MOD_BUS.addListener(::onClientSetup)
        MOD_BUS.addListener(::onRegisterParticleProviders)
        MOD_BUS.addListener(::onRegisterKeyMappings)
        MOD_BUS.addListener(::onRegisterClientReloadListeners)


        FORGE_BUS.addListener(::onClientTick)
        FORGE_BUS.addListener(::onLevelTick)
        FORGE_BUS.addListener(::onLevelLoad)
        FORGE_BUS.addListener(::onLoggingOut)

        CooParticlesConstants.logger.info("CooParticlesAPI Forge 客户端监听器已注册")
    }



    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            runCatching { CooEventBus.call(ClientStartEvent(Minecraft.getInstance())) }
                .onFailure { CooParticlesConstants.logger.error("ClientStartEvent 失败", it) }
        }
    }

    /**
     * 92 个 controlable 粒子的 provider。
     *
     * 前 7 个有专用 Factory（需要 `SpriteSet` 或额外参数），其余走通用
     * [SimpleControlableSpriteParticle.Factory]。
     */
    private fun onRegisterParticleProviders(event: RegisterParticleProvidersEvent) {
        val p = CooModParticles
        event.registerSpriteSet(p.controlableEndRod.get()) { ControlableEndRodParticle.Factory(it) }
        event.registerSpriteSet(p.controlableCloud.get()) { ControlableCloudParticle.Factory(it) }
        event.registerSpriteSet(p.controlableFlash.get()) { ControlableFlashParticle.Factory(it) }
        event.registerSpriteSet(p.controlableFirework.get()) { ControlableFireworkParticle.Factory(it) }
        event.registerSpriteSet(p.controlableSplash.get()) { ControlableSplashParticle.Factory(it) }
        event.registerSpriteSet(p.controlableEnchantment.get()) { ControlableEnchantmentParticle.Factory(it) }
        event.registerSpriteSet(p.controlableFallingDust.get()) { ControlableFallingDustParticle.Factory() }

        simple(event, p.controlableAngryVillager); simple(event, p.controlableBubble)
        simple(event, p.controlableBubbleColumnUp); simple(event, p.controlableBubblePop)
        simple(event, p.controlableCampfireCosySmoke); simple(event, p.controlableCampfireSignalSmoke)
        simple(event, p.controlableComposter); simple(event, p.controlableCrit)
        simple(event, p.controlableCurrentDown); simple(event, p.controlableDamageIndicator)
        simple(event, p.controlableDragonBreath); simple(event, p.controlableDolphin)
        simple(event, p.controlableDrippingLava); simple(event, p.controlableFallingLava)
        simple(event, p.controlableLandingLava); simple(event, p.controlableDrippingWater)
        simple(event, p.controlableFallingWater); simple(event, p.controlableEffect)
        simple(event, p.controlableEnchantedHit); simple(event, p.controlableExplosion)
        simple(event, p.controlableSonicBoom); simple(event, p.controlableGust)
        simple(event, p.controlableSmallGust); simple(event, p.controlableFishing)
        simple(event, p.controlableFlame); simple(event, p.controlableInfested)
        simple(event, p.controlableCherryLeaves); simple(event, p.controlableSculkSoul)
        simple(event, p.controlableSculkChargePop); simple(event, p.controlableSoul)
        simple(event, p.controlableSoulFireFlame); simple(event, p.controlableHappyVillager)
        simple(event, p.controlableHeart); simple(event, p.controlableInstantEffect)
        simple(event, p.controlableLargeSmoke); simple(event, p.controlableLava)
        simple(event, p.controlableMycelium); simple(event, p.controlableNautilus)
        simple(event, p.controlableNote); simple(event, p.controlablePoof)
        simple(event, p.controlablePortal); simple(event, p.controlableRain)
        simple(event, p.controlableSmoke); simple(event, p.controlableWhiteSmoke)
        simple(event, p.controlableSneeze); simple(event, p.controlableSnowflake)
        simple(event, p.controlableSpit); simple(event, p.controlableSweepAttack)
        simple(event, p.controlableTotemOfUndying); simple(event, p.controlableSquidInk)
        simple(event, p.controlableUnderwater); simple(event, p.controlableWitch)
        simple(event, p.controlableDrippingHoney); simple(event, p.controlableFallingHoney)
        simple(event, p.controlableLandingHoney); simple(event, p.controlableFallingNectar)
        simple(event, p.controlableFallingSporeBlossom); simple(event, p.controlableSporeBlossomAir)
        simple(event, p.controlableAsh); simple(event, p.controlableCrimsonSpore)
        simple(event, p.controlableWarpedSpore); simple(event, p.controlableDrippingObsidianTear)
        simple(event, p.controlableFallingObsidianTear); simple(event, p.controlableLandingObsidianTear)
        simple(event, p.controlableReversePortal); simple(event, p.controlableWhiteAsh)
        simple(event, p.controlableSmallFlame); simple(event, p.controlableDrippingDripstoneWater)
        simple(event, p.controlableFallingDripstoneWater); simple(event, p.controlableDrippingDripstoneLava)
        simple(event, p.controlableFallingDripstoneLava); simple(event, p.controlableGlowSquidInk)
        simple(event, p.controlableGlow); simple(event, p.controlableWaxOn)
        simple(event, p.controlableWaxOff); simple(event, p.controlableElectricSpark)
        simple(event, p.controlableScrape); simple(event, p.controlableEggCrack)
        simple(event, p.controlableDustPlume); simple(event, p.controlableTrialSpawnerDetection)
        simple(event, p.controlableTrialSpawnerDetectionOminous); simple(event, p.controlableVaultConnection)
        simple(event, p.controlableRaidOmen); simple(event, p.controlableTrialOmen)
        simple(event, p.controlableOminousSpawning)

        CooParticlesConstants.logger.info("CooParticlesAPI 粒子 provider 注册完成")
    }

    private fun <T : cn.coostack.cooparticlesapi.particles.ControlableParticleEffect> simple(
        event: RegisterParticleProvidersEvent,
        type: CommonDeferredRegistry<ParticleType<T>>,
    ) {
        event.registerSpriteSet(type.get()) { SimpleControlableSpriteParticle.Factory<T>(it) }
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        runCatching {
            CooKeyBindingManager.setRegistrar { mapping -> event.register(mapping) }
            cn.coostack.cooparticlesapi.performance.client.PerformanceStatusKeyBindings
                .mappings().forEach(event::register)
        }.onFailure { CooParticlesConstants.logger.error("按键绑定注册失败", it) }
    }

    private fun onRegisterClientReloadListeners(event: RegisterClientReloadListenersEvent) {
        runCatching {

            event.registerReloadListener(
                net.minecraft.server.packs.resources.ResourceManagerReloadListener {
                    CooShaderReloadSupport.reload(it)
                }
            )
        }.onFailure { CooParticlesConstants.logger.error("shader reload 监听注册失败", it) }
    }



    /** ★ 关键：粒子系统的 tick 入口（缺了它所有粒子都不动） */
    private fun onLevelTick(event: TickEvent.LevelTickEvent) {
        val level = event.level as? ClientLevel ?: return
        runCatching {
            if (event.phase == TickEvent.Phase.START) {
                CooParticlesAPIClient.tickClient(level)
                CooEventBus.call(ClientWorldPreTickEvent(level))
            } else {
                CooEventBus.call(ClientWorldPostTickEvent(level))
            }
        }.onFailure { CooParticlesConstants.logger.error("客户端世界 tick 失败", it) }
    }

    private fun onClientTick(event: TickEvent.ClientTickEvent) {
        val client = Minecraft.getInstance()
        runCatching {
            if (event.phase == TickEvent.Phase.START) {
                CooKeyBindingManager.tick()
                CooEventBus.call(ClientPreTickEvent(client))
            } else {
                CooEventBus.call(ClientPostTickEvent(client))
                cn.coostack.cooparticlesapi.performance.client.PerformanceStatusKeyBindings.tick(client)
                cn.coostack.cooparticlesapi.performance.client.PerformanceStatusClientController
                    .onClientTick(client)
            }
        }.onFailure { CooParticlesConstants.logger.error("客户端 tick 失败", it) }
    }

    private fun onLevelLoad(event: LevelEvent.Load) {
        val level = event.level as? ClientLevel ?: return
        runCatching {





            Minecraft.getInstance().execute {
                cn.coostack.cooparticlesapi.test.options.display.MCShaders.ensureInitialized()
            }
            CooParticlesAPIClient.afterClientWorldChange()
            CooEventBus.call(ClientWorldChangeEvent(level))
        }.onFailure { CooParticlesConstants.logger.error("世界切换处理失败", it) }
    }

    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        runCatching { CooParticlesAPIClient.onDisconnect() }
            .onFailure { CooParticlesConstants.logger.error("断线清理失败", it) }
    }
}
