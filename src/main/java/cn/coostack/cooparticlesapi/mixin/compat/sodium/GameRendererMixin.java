package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 renderLevel 收尾时把延迟的覆盖层冲刷掉 —— 保证「Sodium 路径下最后画的仍是我们的地形特效」。
 *
 * ★★ 移植注记（上游 1.21.1 NeoForge → 1.20.1 Forge，本文件改动最大）：
 *
 *  1) 包名与方法名：
 *       上游  @Inject(method = "renderLevel", ...)  +  DeltaTracker 参数
 *       本地  renderLevel(float, long, PoseStack)   —— 签名**完全不同**
 *
 *     ★ SRG 映射表实证（build/createMcpToSrg/output.tsrg）：
 *         renderLevel (FJLcom/mojang/blaze3d/vertex/PoseStack;)V   m_109089_
 *
 *     1.21 把 tick 参数换成了 DeltaTracker 对象；1.20.1 还是老式的 (partialTick, nanoTime, poseStack)。
 *     照抄上游会因描述符不匹配而注入失败 —— 正是 skill Rule 5「重载按版本不同」的典型例子。
 *
 *  2) 因为 renderLevel 会被 SRG 重映射，这里**不能写 remap = false**，
 *     用官方名 + 让 refmap 接管即可（与 GameRendererTerrainFinishMixin 同款做法）。
 *
 *  3) priority = 900 保留：要晚于 Iris 的注入（Iris 用默认 1000，数值越小越晚执行……
 *     实际语义见 Mixin 文档；这里沿用上游设定，实测确保覆盖层最后冲刷）。
 */
@Mixin(value = GameRenderer.class, priority = 900)
public class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void cooParticlesAPI$renderTerrainAfterIris(float partialTick,
                                                        long nanoTime,
                                                        PoseStack poseStack,
                                                        CallbackInfo info) {
        try {
            CooSodiumTerrainOverlay.flushDeferred();
        } finally {
            CooTerrainPipelineManager.finishDeferredFrame();
        }
    }
}
