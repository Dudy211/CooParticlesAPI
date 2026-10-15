package cn.coostack.cooparticlesapi.listener.forge

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.entities.CooModEntityTypes
import cn.coostack.cooparticlesapi.entities.renderer.TestRenderEntityRenderer
import cn.coostack.cooparticlesapi.listeners.ClientPlayerDeathListener
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.ScreenEvent
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

/**
 * CooParticlesAPI 的客户端**杂项**监听 —— 对应上游 NeoForge 侧的
 * `listener/client/ClientPlayerListener.kt`（不需要世界渲染上下文的那些）。
 *
 * <h2>⚠️ 缺了它的后果</h2>
 *
 * 1. **实体渲染器没注册** —— `CooModEntityTypes.TEST_RENDER`
 *    （测试用的 RenderEntity）在客户端**没有渲染器** → 该实体不显示，
 *    而不少测试粒子/展示效果是靠这个实体承载的。
 *    症状上也是「测试工具点了没东西出现」。
 * 2. **死亡屏出现时不清粒子** —— 上游 `ScreenEvent.Opening` 检测到
 *    `DeathScreen` 就调 `ClientPlayerDeathListener.call()` 清空所有粒子。
 *    少了它，死亡后客户端残留粒子状态（视觉 bug，不致命）。
 */
object CooParticlesForgeClientExtraListeners {

    /** 由 `CooParticlesAPIForgeClient.setupClient()` 调用 */
    fun register() {

        MOD_BUS.addListener(::onRegisterEntityRenderers)

        FORGE_BUS.addListener(::onScreenOpening)
        CooParticlesConstants.logger.info("CooParticlesAPI Forge 客户端杂项监听器已注册")
    }

    /** 上游 `ClientPlayerListener.registerEntityRenderer` */
    private fun onRegisterEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        runCatching {
            event.registerEntityRenderer(CooModEntityTypes.TEST_RENDER.get()) {
                TestRenderEntityRenderer(it)
            }
        }.onFailure {
            CooParticlesConstants.logger.error("注册实体渲染器失败", it)
        }
    }

    /** 上游 `ClientPlayerListener.onPlayerDeath`（死亡屏开局 → 清粒子） */
    private fun onScreenOpening(event: ScreenEvent.Opening) {
        if (event.screen !is DeathScreen) return
        runCatching { ClientPlayerDeathListener.call() }
            .onFailure { CooParticlesConstants.logger.error("死亡清粒子失败", it) }
    }
}
