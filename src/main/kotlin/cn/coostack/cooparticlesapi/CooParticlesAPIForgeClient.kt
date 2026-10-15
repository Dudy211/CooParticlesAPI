package cn.coostack.cooparticlesapi

/**
 * CooParticlesAPI 的 Forge 1.20.1 客户端初始化。
 *
 * 1.20.1 的 `@Mod` 没有 `dist` 参数，客户端分流由主类用 KFF 的 `DIST` 判断后调用本类。
 *
 * <h2>上游对应关系</h2>
 *
 * 上游 NeoForge 侧把客户端注册拆在 6 个 `@EventBusSubscriber` 类里
 * （`CooParticlesNeoClientInitialize` / `CooParticlesAPINeoClientModListener` /
 * `CooParticlesAPINeoClientListener` / `CooParticlesAPINeoClientRegistration` /
 * `ClientEventsListener` / `ClientPlayerListener`）。
 * **移植时这些类一个都没搬**（它们都在平台侧目录 `neoforge/`），于是客户端行为整个缺失。
 *
 * 本类负责把等价的东西全挂上：
 * - [cn.coostack.cooparticlesapi.listener.client.CooParticlesForgeClientListeners]
 *   客户端 tick / 92 个粒子 provider / 按键绑定 / shader reload
 * - [cn.coostack.cooparticlesapi.listener.forge.CooParticlesForgeWorldRenderListeners]
 *   **世界渲染阶段（cparticle GPU 粒子真正的绘制入口）**
 * - [cn.coostack.cooparticlesapi.listener.forge.CooParticlesForgeClientExtraListeners]
 *   实体渲染器（TEST_RENDER）/ 死亡清粒子
 */
object CooParticlesAPIForgeClient {

    /**
     * **第一步：只挂监听器**（必须在 FMLCommonSetupEvent **派发期间**同步执行）。
     *
     * ⚠️⚠️ 这些注册事件 Forge 是**一次性 post** 的（见 `ForgeHooksClient.initClientHooks`，
     * 它在 `Minecraft` 构造函数里跑）：
     *
     * ```
     * RegisterClientReloadListenersEvent / EntityRenderersEvent.RegisterLayerDefinitions /
     * EntityRenderersEvent.RegisterRenderers / ... / ForgeHooksClient.onRegisterKeyMappings(...)
     * ```
     *
     * 而 `RegisterParticleProvidersEvent` / `RegisterKeyMappingsEvent` 同样是这一批。
     * 一旦把注册动作放进 `event.enqueueWork { }`（= 等 FMLCommonSetupEvent 处理完再排队跑），
     * **同一轮里已经 post 完的注册事件全都收不到**。
     *
     * 2026-09-24 实测后果（日志 `UsefulMagic 按键绑定注册完成` 有、CP 没有）：
     * - `onRegisterParticleProviders` 没收 → **92 个粒子 provider 没注册**
     *   → `SpriteSet` 取不到 → `CParticleTextureResolver.hasEffectFrames()` = false
     *   → `missingTexture()` → `display()` 返回 null → **一批粒子完全不显示**
     * - `onRegisterKeyMappings` 没收 → `CooKeyBindingManager.registrar` 恒为 null
     *   → `registerIfPossible` 直接 return → **UsefulMagic 的 CHARGE_MAGIC 从未注册**
     *   → **法杖蓄力完全没反应**
     * - `onRegisterClientReloadListeners` 没收 → `CooParticleTextureSheet.reloadShader` /
     *   `CooRenderTypeResourceRegistry.reload` / `CooFXClient.reloadResources` 全没跑
     *
     * 本方法只做「挂监听器」，不碰 GL / 不建 Shader，所以放在事件派发期同步调用是安全的。
     */
    fun registerListeners() {

        runCatching {
            cn.coostack.cooparticlesapi.listener.client.CooParticlesForgeClientListeners.register()
        }.onFailure {
            CooParticlesConstants.logger.error("客户端监听器注册失败", it)
        }




        runCatching {
            cn.coostack.cooparticlesapi.network.packet.compat.ForgePacketChannels.registerClientReceivers()
        }.onFailure {
            CooParticlesConstants.logger.error("直通 S2C 接收器登记失败", it)
        }


        runCatching {
            cn.coostack.cooparticlesapi.listener.forge.CooParticlesForgeWorldRenderListeners.register()
        }.onFailure {
            CooParticlesConstants.logger.error("世界渲染监听器注册失败", it)
        }


        runCatching {
            cn.coostack.cooparticlesapi.listener.forge.CooParticlesForgeClientExtraListeners.register()
        }.onFailure {
            CooParticlesConstants.logger.error("客户端杂项监听器注册失败", it)
        }
    }

    /**
     * **第二步：重初始化**（回到主线程 / Render thread 再执行）。
     *
     * 这里会建渲染管线 / 各种 registry，历史上一直在 Render thread 上跑
     * （`event.enqueueWork` 保证了这点），所以调用方必须把它排回主线程。
     *
     * @see registerListeners
     */
    fun setupClient() {
        CooParticlesConstants.logger.info("CooParticlesAPI Forge 客户端初始化")
        runCatching {
            CooParticlesAPIClient.init()
        }.onFailure {
            CooParticlesConstants.logger.error("客户端初始化失败", it)
        }
    }
}
