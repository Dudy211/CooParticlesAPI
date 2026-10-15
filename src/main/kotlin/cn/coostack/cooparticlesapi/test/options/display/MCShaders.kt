package cn.coostack.cooparticlesapi.test.options.display

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.compat.IrisCompat
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.server.packs.resources.ResourceManager

/**
 * 自定义 ShaderInstance 注册中心。
 *
 * 历史结论：直接拿 [ShaderInstance] 配合 vanilla [net.minecraft.client.renderer.RenderType] 在
 * IRIS 光影下不会渲染 —— 因为 IRIS 的 MixinShaderInstance 把"未注册的 shader"集体跳过。
 *
 * 解法：每次 reload 后调用 [IrisCompat.markUnskippable] 把我们的 shader 标为
 * "永远不要 skip"，IRIS 不存在时该调用是 no-op。
 *
 * <h2>⚠️ 为什么还需要 [ensureInitialized]（2026-09-23 崩溃根因）</h2>
 *
 * 这两个 shader 原先**只**在 `ShaderReloadBus.handleFullReload` 里初始化，而那条链的唯一触发点是
 * Forge 的 `RegisterClientReloadListenersEvent`：
 *
 * ```
 * RegisterClientReloadListenersEvent (Forge 只 post 一次，在 Minecraft 构造函数里)
 *   → CooShaderReloadSupport.reload → ShaderReloadBus.dispatch(FullReload)
 *   → MCShaders.init(resourceManager)
 * ```
 *
 * 但本模组的监听器是在 `FMLCommonSetupEvent` 里用 `MOD_BUS.addListener` 挂的 ——
 * 那个阶段**远晚于** `Minecraft` 构造期，事件早发完了 → 监听器永远收不到
 * → `MCShaders` 的 `lateinit` **从未被赋值**。
 *
 * 而 `RenderStateShard.ShaderStateShard` 在切 shader 时会去读它 →
 * 每帧每实体抛一次 `UninitializedPropertyAccessException`
 * （实测日志里刷了数千次 `lateinit property ENTITY_CUTOUT_EMISSIVE has not been initialized`）。
 *
 * 上游用 `@EventBusSubscriber(bus = MOD)` 在 mod 构造期就挂好了，所以拿得到那个事件；
 * 移植侧改成 `FMLCommonSetupEvent` 才错过。**注册时机无法改回去**（Forge 那个事件
 * 在 mod 侧根本来不及接），所以必须让 shader 能**按需就绪**。
 *
 * [ensureInitialized] 会：如果还没初始化，用当前 `Minecraft` 的资源管理器补一次初始化。
 * 它被 [cn.coostack.cooparticlesapi.renderer.shader.ShaderReloadBus] 与两个读取点调用，
 * 重复调用无副作用（已初始化时直接返回）。
 */
object MCShaders {

    lateinit var GLOW: ShaderInstance
        private set

    lateinit var ENTITY_CUTOUT_EMISSIVE: ShaderInstance
        private set

    /** 是否已经成功初始化过（两个字段都可用）。 */
    val isReady: Boolean
        get() = ::GLOW.isInitialized && ::ENTITY_CUTOUT_EMISSIVE.isInitialized

    /**
     * 按需初始化。若尚未初始化，用 [net.minecraft.client.Minecraft] 当前的资源管理器
     * 建好两个 shader；已初始化则直接返回。
     *
     * ⚠️ 必须在**渲染线程**调用（`ShaderInstance` 的构造会碰 GL）。
     *
     * @return 本次调用后是否处于可用状态
     */
    fun ensureInitialized(): Boolean {
        if (isReady) return true
        return try {
            val resourceManager = Minecraft.getInstance().resourceManager
            init(resourceManager)
            CooParticlesConstants.logger.info("MCShaders 已按需初始化（reload 事件未覆盖本次启动）")
            true
        } catch (t: Throwable) {
            CooParticlesConstants.logger.error("MCShaders 按需初始化失败", t)
            false
        }
    }

    fun init(resourceManager: ResourceManager) {
        release()
        GLOW = ShaderInstance(
            resourceManager,
            "coo_glow",
            DefaultVertexFormat.POSITION_COLOR
        )
        IrisCompat.markUnskippable(GLOW)
        ENTITY_CUTOUT_EMISSIVE = ShaderInstance(
            resourceManager,
            "coo_entity_cutout_emissive",
            DefaultVertexFormat.NEW_ENTITY
        )
        IrisCompat.markUnskippable(ENTITY_CUTOUT_EMISSIVE)
    }

    fun release() {
        if (::GLOW.isInitialized) {
            GLOW.close()
        }
        if (::ENTITY_CUTOUT_EMISSIVE.isInitialized) {
            ENTITY_CUTOUT_EMISSIVE.close()
        }
    }
}
