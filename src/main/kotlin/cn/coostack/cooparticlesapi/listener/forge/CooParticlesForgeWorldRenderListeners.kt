package cn.coostack.cooparticlesapi.listener.forge

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.accessor.LevelRendererAccessor
import cn.coostack.cooparticlesapi.compat.mc1201.BufferedRenderTypes
import cn.coostack.cooparticlesapi.compat.mc1201.DeltaTracker
import cn.coostack.cooparticlesapi.event.CooEventBus
import cn.coostack.cooparticlesapi.event.events.world.client.ClientWorldRenderEvent
import cn.coostack.cooparticlesapi.test.block.client.TestControllerPickClient
import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.RenderLevelStageEvent
import thedarkcolour.kotlinforforge.forge.FORGE_BUS

/**
 * CooParticlesAPI 的**客户端世界渲染**监听 —— 对应上游 NeoForge 侧的
 * `listener/client/ClientEventsListener.kt`。
 *
 * <h2>⚠️⚠️ 没有它，粒子就**画不出来**（这是「所有粒子都不显示」的第三块拼图）</h2>
 *
 * 上游在 `RenderLevelStageEvent` 的 **`AFTER_ENTITIES`** 与 **`AFTER_PARTICLES`**
 * 两个阶段派发 [ClientWorldRenderEvent]，而 cparticle 的 GPU 粒子系统
 * 正是挂在 `AFTER_TRANSLUCENT`（= 上游映射的 `AFTER_PARTICLES`）上绘制的。
 *
 * 移植时这个类在平台侧目录里，**整个没搬** → `ClientWorldRenderEvent` 从不派发 →
 *
 * 1. **cparticle GPU 粒子系统永不绘制**（它不经过原版粒子系统）。
 * 2. 测试控制器的**取点框 / 连线高亮**（`TestControllerPickClient.render`）永不出现。
 *
 * ⚠️ 1.20.1 与 1.20.5+ 的差异：`RenderLevelStageEvent` **没有** `getBufferSource()`
 * 和 `getModelViewMatrix()`，只有 `getProjectionMatrix()` / `getPoseStack()` /
 * `getCamera()` / `getPartialTick()`。所以 buffer 要从 `LevelRenderer` 的
 * `renderBuffers()` 拿（走我们自己的 [LevelRendererAccessor] mixin），
 * 观察矩阵就是 `poseStack.last().pose()`（1.20.1 里 PoseStack 最后一位才是 view 矩阵）。
 */
object CooParticlesForgeWorldRenderListeners {

    /** 由 `CooParticlesAPIForgeClient.setupClient()` 调用 */
    fun register() {
        FORGE_BUS.addListener(::onRenderLevelStage)
        CooParticlesConstants.logger.info("CooParticlesAPI Forge 世界渲染监听器已注册")
    }

    private fun onRenderLevelStage(event: RenderLevelStageEvent) {
        val world = Minecraft.getInstance().level ?: return
        val stage = when (event.stage) {
            RenderLevelStageEvent.Stage.AFTER_ENTITIES ->
                ClientWorldRenderEvent.RenderStage.AFTER_ENTITY

            RenderLevelStageEvent.Stage.AFTER_PARTICLES ->
                ClientWorldRenderEvent.RenderStage.AFTER_TRANSLUCENT

            else -> return
        }

        runCatching {
            val renderer = event.levelRenderer
            val accessor = renderer as? LevelRendererAccessor ?: return
            val shared = accessor.renderBuffers().bufferSource()





            val buffer = BufferedRenderTypes(shared)

            val cooEvent = ClientWorldRenderEvent(
                world,
                stage,
                event.poseStack.last().pose(),
                event.projectionMatrix,
                event.poseStack,
                buffer,
                renderer,
                event.camera,
                DeltaTracker.of(event.partialTick),
            )

            CooEventBus.call(cooEvent)


            TestControllerPickClient.render(cooEvent)


            buffer.flush()
        }.onFailure {
            CooParticlesConstants.logger.error("世界渲染事件处理失败", it)
        }
    }
}
