package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在没有 Sodium 时，于 Iris 最终合成后提交延迟的 terrain 覆盖层。
 *
 * <p>⚠️ 1.20.1 与 1.21 的调用关系**相反**：
 * <ul>
 *   <li>1.21：{@code LevelRenderer.renderLevel} 在外层，{@code GameRenderer.renderLevel} 由它调用，
 *       所以「世界渲染结束」挂在 {@code GameRenderer.renderLevel} 的 RETURN 上。</li>
 *   <li>1.20.1：{@code GameRenderer.renderLevel(float, long, PoseStack)} 在外层，
 *       它内部调用 {@code LevelRenderer.renderLevel(PoseStack, float, long, boolean, Camera,
 *       GameRenderer, LightTexture, Matrix4f)}。挂在 GameRenderer 上会**晚一整层**（Iris 最终合成本来就
 *       发生在 LevelRenderer 之后很久）。</li>
 * </ul>
 *
 * <p>因此这里改挂到 {@code LevelRenderer.renderLevel} 的 RETURN —— 语义上等价于 1.21 的
 * GameRenderer 时机（「世界图层 + 后处理都跑完了」），也就是上游想表达的那个位置。
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public class GameRendererTerrainFinishMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void cooParticlesAPI$finishTerrainAfterIris(PoseStack poseStack,
                                                        float partialTick,
                                                        long finishTimeNano,
                                                        boolean renderBlockOutline,
                                                        Camera camera,
                                                        GameRenderer gameRenderer,
                                                        LightTexture lightTexture,
                                                        Matrix4f projectionMatrix,
                                                        CallbackInfo info) {
        if (CooTerrainPipelineManager.usesSodiumTerrainOverlay()) {
            return;
        }
        try {
            CooTerrainPipelineManager.flushDeferredVanillaTerrainOverlays();
        } finally {
            CooTerrainPipelineManager.finishDeferredFrame();
        }
    }
}
