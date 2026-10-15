package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.CooParticlesAPIClient;
import cn.coostack.cooparticlesapi.cparticle.CParticleSystemManager;
import cn.coostack.cooparticlesapi.renderer.client.ClientRenderEntityManager;
import cn.coostack.cooparticlesapi.renderer.client.ClientRenderPipelineManager;
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;

/**
 * 把 Coo 粒子系统、地形 Pipeline 和统一后处理生命周期接入原版世界渲染器。
 *
 * <p>地形覆盖在对应原版 chunk layer 绘制后提交；Sodium 使用独立兼容路径，
 * Iris shader pack 激活时则把覆盖绘制推迟到最终合成之后。
 *
 * <h2>1.21 → 1.20.1 差异（勿回退）</h2>
 * <ul>
 *   <li>1.20.1 的 {@code renderLevel} 签名是
 *       {@code (PoseStack, float, long, boolean, Camera, GameRenderer, LightTexture, Matrix4f)}：
 *       partialTick 是**裸 float 且在参数表前面**，没有 {@code DeltaTracker}。
 *       1.21 的 {@code DeltaTracker} shim 只能骗过编译器 —— Mixin 在 apply 阶段拿
 *       handler 描述符去匹配目标方法，对不上就 {@code defaultRequire:1} 直接崩，所以一律用原生签名。</li>
 *   <li>1.20.1 没有任何 {@code renderSectionLayer(...)}，该方法叫
 *       {@code renderChunkLayer(RenderType, PoseStack, double, double, double, Matrix4f)}。
 *       PoseStack 是**第 2 个参数**，必须原样透传给覆盖层绘制，否则矩阵栈不对。</li>
 * </ul>
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public class LevelRendererMixin {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Shadow
    private void renderChunkLayer(RenderType renderType,
                                  PoseStack poseStack,
                                  double cameraX,
                                  double cameraY,
                                  double cameraZ,
                                  Matrix4f projectionMatrix) {
        throw new AssertionError();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void cooParticlesAPI$beginRenderFrame(PoseStack poseStack,
                                                  float partialTick,
                                                  long finishTimeNano,
                                                  boolean renderBlockOutline,
                                                  Camera camera,
                                                  GameRenderer gameRenderer,
                                                  LightTexture lightTexture,
                                                  Matrix4f projectionMatrix,
                                                  CallbackInfo info) {
        CParticleSystemManager.beginRenderFrame();
        CooTerrainPipelineManager.updateCompatibilityState();
        CooTerrainPipelineManager.beginRenderFrame(partialTick);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderSolidTerrainPipelines(PoseStack poseStack,
                                                             float partialTick,
                                                             long finishTimeNano,
                                                             boolean renderBlockOutline,
                                                             Camera camera,
                                                             GameRenderer gameRenderer,
                                                             LightTexture lightTexture,
                                                             Matrix4f projectionMatrix,
                                                             CallbackInfo info) {
        renderTerrainPipelines(RenderType.solid(), poseStack, camera, projectionMatrix);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderCutoutMippedTerrainPipelines(PoseStack poseStack,
                                                                    float partialTick,
                                                                    long finishTimeNano,
                                                                    boolean renderBlockOutline,
                                                                    Camera camera,
                                                                    GameRenderer gameRenderer,
                                                                    LightTexture lightTexture,
                                                                    Matrix4f projectionMatrix,
                                                                    CallbackInfo info) {
        renderTerrainPipelines(RenderType.cutoutMipped(), poseStack, camera, projectionMatrix);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 2,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderCutoutTerrainPipelines(PoseStack poseStack,
                                                              float partialTick,
                                                              long finishTimeNano,
                                                              boolean renderBlockOutline,
                                                              Camera camera,
                                                              GameRenderer gameRenderer,
                                                              LightTexture lightTexture,
                                                              Matrix4f projectionMatrix,
                                                              CallbackInfo info) {
        renderTerrainPipelines(RenderType.cutout(), poseStack, camera, projectionMatrix);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 3
            )
    )
    private void cooParticlesAPI$captureTranslucentTerrainDepthBefore(PoseStack poseStack,
                                                                      float partialTick,
                                                                      long finishTimeNano,
                                                                      boolean renderBlockOutline,
                                                                      Camera camera,
                                                                      GameRenderer gameRenderer,
                                                                      LightTexture lightTexture,
                                                                      Matrix4f projectionMatrix,
                                                                      CallbackInfo info) {
        CooTerrainPipelineManager.captureTranslucentTerrainDepthBefore();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 5
            )
    )
    private void cooParticlesAPI$captureFabulousTranslucentTerrainDepthBefore(PoseStack poseStack,
                                                                              float partialTick,
                                                                              long finishTimeNano,
                                                                              boolean renderBlockOutline,
                                                                              Camera camera,
                                                                              GameRenderer gameRenderer,
                                                                              LightTexture lightTexture,
                                                                              Matrix4f projectionMatrix,
                                                                              CallbackInfo info) {
        CooTerrainPipelineManager.captureTranslucentTerrainDepthBefore();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 3,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$captureTranslucentTerrainDepthAfter(PoseStack poseStack,
                                                                     float partialTick,
                                                                     long finishTimeNano,
                                                                     boolean renderBlockOutline,
                                                                     Camera camera,
                                                                     GameRenderer gameRenderer,
                                                                     LightTexture lightTexture,
                                                                     Matrix4f projectionMatrix,
                                                                     CallbackInfo info) {
        CooTerrainPipelineManager.captureTranslucentTerrainDepthAfter();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 5,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$captureFabulousTranslucentTerrainDepthAfter(PoseStack poseStack,
                                                                             float partialTick,
                                                                             long finishTimeNano,
                                                                             boolean renderBlockOutline,
                                                                             Camera camera,
                                                                             GameRenderer gameRenderer,
                                                                             LightTexture lightTexture,
                                                                             Matrix4f projectionMatrix,
                                                                             CallbackInfo info) {
        CooTerrainPipelineManager.captureTranslucentTerrainDepthAfter();
    }

    /**
     * ⚠️ 与 {@link #cooParticlesAPI$captureFabulousTranslucentTerrainDepthBefore} 相同：
     * 1.20.1 的 {@code renderLevel} 里 ordinal 3 与 ordinal 5 **指向同一条** renderChunkLayer
     * 调用（bci 2515）。这是上游 1.21 结构直接照搬过来的结果 —— 该覆盖层会被登记两次。
     * 保守起见先保持与上游一致（只画一次也无副作用），但**不要**误以为它对应 fabulous 分支。
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 3,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderTranslucentTerrainPipelines(PoseStack poseStack,
                                                                   float partialTick,
                                                                   long finishTimeNano,
                                                                   boolean renderBlockOutline,
                                                                   Camera camera,
                                                                   GameRenderer gameRenderer,
                                                                   LightTexture lightTexture,
                                                                   Matrix4f projectionMatrix,
                                                                   CallbackInfo info) {
        renderTerrainPipelines(RenderType.translucent(), poseStack, camera, projectionMatrix);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 4,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderTripwireTerrainPipelines(PoseStack poseStack,
                                                                float partialTick,
                                                                long finishTimeNano,
                                                                boolean renderBlockOutline,
                                                                Camera camera,
                                                                GameRenderer gameRenderer,
                                                                LightTexture lightTexture,
                                                                Matrix4f projectionMatrix,
                                                                CallbackInfo info) {
        renderTerrainPipelines(RenderType.tripwire(), poseStack, camera, projectionMatrix);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 5,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderFabulousTranslucentTerrainPipelines(PoseStack poseStack,
                                                                           float partialTick,
                                                                           long finishTimeNano,
                                                                           boolean renderBlockOutline,
                                                                           Camera camera,
                                                                           GameRenderer gameRenderer,
                                                                           LightTexture lightTexture,
                                                                           Matrix4f projectionMatrix,
                                                                           CallbackInfo info) {
        renderTerrainPipelines(RenderType.translucent(), poseStack, camera, projectionMatrix);
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
                    ordinal = 6,
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$renderFabulousTripwireTerrainPipelines(PoseStack poseStack,
                                                                        float partialTick,
                                                                        long finishTimeNano,
                                                                        boolean renderBlockOutline,
                                                                        Camera camera,
                                                                        GameRenderer gameRenderer,
                                                                        LightTexture lightTexture,
                                                                        Matrix4f projectionMatrix,
                                                                        CallbackInfo info) {
        renderTerrainPipelines(RenderType.tripwire(), poseStack, camera, projectionMatrix);
    }

    /**
     * 绘制建立在一个原版 terrain layer 上的全部 Coo 覆盖层。
     *
     * <p>Sodium 已接管时不进入原版路径。带后处理的加法 Mapping 只登记 attachment 捕获回调，
     * 避免先写世界目标再重放同一 section；其余覆盖层按普通或 Iris 最终合成路径绘制。
     * 任一覆盖层失败都会切回原版几何。
     *
     * @param baseLayer 刚完成绘制的原版 terrain layer
     * @param poseStack 当前世界的 PoseStack（原版 {@code renderLevel} 的入参，直接复用，
     *                  不要在内部 push/pop —— 该栈在整个 {@code renderLevel} 期间保持单位矩阵）
     * @param camera 当前世界相机
     * @param projectionMatrix 当前投影矩阵
     */
    private void renderTerrainPipelines(RenderType baseLayer,
                                        PoseStack poseStack,
                                        Camera camera,
                                        Matrix4f projectionMatrix) {
        if (!CooTerrainPipelineManager.isTerrainOverlayEnabled()) {
            return;
        }
        if (CooTerrainPipelineManager.usesSodiumTerrainOverlay()) {
            return;
        }
        var layers = CooTerrainPipelineManager.layersFor(baseLayer);
        if (layers.isEmpty()) {
            return;
        }
        var immediateLayers = new ArrayList<RenderType>();
        for (RenderType renderType : layers) {
            final RenderType layerType = renderType;
            final double cameraX = camera.getPosition().x;
            final double cameraY = camera.getPosition().y;
            final double cameraZ = camera.getPosition().z;
            Runnable draw = () -> renderChunkLayer(
                    layerType,
                    poseStack,
                    cameraX,
                    cameraY,
                    cameraZ,
                    projectionMatrix
            );
            if (CooTerrainPipelineManager.isTerrainPostRenderType(renderType)) {
                CooTerrainPipelineManager.recordPostDraw(renderType, draw);
            } else if (CooTerrainPipelineManager.shouldDeferVanillaTerrainOverlay()) {
                CooTerrainPipelineManager.deferVanillaTerrainOverlay(renderType, draw);
            } else {
                immediateLayers.add(renderType);
            }
        }
        if (immediateLayers.isEmpty()) {
            return;
        }
        if (!CooTerrainPipelineManager.beginOverlayBatch(immediateLayers)) {
            return;
        }
        try {
            if (!CooTerrainPipelineManager.isTerrainOverlayEnabled()) {
                return;
            }
            for (RenderType renderType : immediateLayers) {
                try {
                    renderChunkLayer(
                            renderType,
                            poseStack,
                            camera.getPosition().x,
                            camera.getPosition().y,
                            camera.getPosition().z,
                            projectionMatrix
                    );
                } catch (RuntimeException error) {
                    if (!CooTerrainPipelineManager.handleOverlayDrawFailure(renderType, error)) {
                        throw error;
                    }
                    break;
                }
            }
        } finally {
            CooTerrainPipelineManager.endOverlayBatch();
        }
    }



    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                    ordinal = 10))
    public void renderBeforeEntity(PoseStack poseStack,
                                   float partialTick,
                                   long finishTimeNano,
                                   boolean renderBlockOutline,
                                   Camera camera,
                                   GameRenderer gameRenderer,
                                   LightTexture lightTexture,
                                   Matrix4f projectionMatrix,
                                   CallbackInfo info) {

        if (level == null) {
            return;
        }
        CooParticlesAPIClient.initShaderPrograms();
        CooParticlesAPIClient.syncRenderBackend();


        float tickDelta = partialTick;
        ClientRenderPipelineManager.INSTANCE.beginFrame(tickDelta, poseStack.last().pose(), projectionMatrix);
        CooTerrainPipelineManager.captureOpaqueTerrainDepth();
        boolean irisShaderPackInUse = CooParticlesAPIClient.checkIrisShaderPackUsed();
        ClientRenderEntityManager.INSTANCE.beginWorldRenderFrame();
        ClientRenderEntityManager.INSTANCE.renderIrisWorldPass(
                tickDelta,
                poseStack.last().pose(),
                projectionMatrix,
                irisShaderPackInUse
        );
        ClientRenderPipelineManager.INSTANCE.renderIrisCooFxWorldPass();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    public void renderOnTail(PoseStack poseStack,
                             float partialTick,
                             long finishTimeNano,
                             boolean renderBlockOutline,
                             Camera camera,
                             GameRenderer gameRenderer,
                             LightTexture lightTexture,
                             Matrix4f projectionMatrix,
                             CallbackInfo info) {

        if (level == null) {
            return;
        }
        CooParticlesAPIClient.initShaderPrograms();
        CooParticlesAPIClient.syncRenderBackend();
        float tickDelta = partialTick;
        if (CooTerrainPipelineManager.deferFrameFinish(tickDelta, poseStack.last().pose(), projectionMatrix)) {
            return;
        }
        ClientRenderPipelineManager.INSTANCE.finishLevelRender(tickDelta, poseStack.last().pose(), projectionMatrix);
    }
}
