package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 地形覆盖层的 **vanilla 路径采集入口**（不装 Embeddium 时走这里）。
 *
 * <h2>★★★ 移植注记（上游 1.21 → 1.20.1）—— 这是三个 mixin 里最难的一个</h2>
 *
 * <table>
 *   <tr><th>上游（1.21）</th><th>本地（1.20.1）</th></tr>
 *   <tr><td>{@code SectionCompiler.compile(SectionPos, RenderChunkRegion, VertexSorting, SectionBufferBuilderPack)}</td>
 *       <td><b>{@code ChunkRenderDispatcher$RenderChunk$RebuildTask.compile(float, float, float, ChunkBufferBuilderPack)}</b></td></tr>
 *   <tr><td>{@code BlockRenderDispatcher.renderLiquid(BlockPos, BlockAndTintGetter, VertexConsumer, BlockState, FluidState)}</td>
 *       <td>✅ <b>完全相同的签名</b>（字节码偏移 271 实证）</td></tr>
 *   <tr><td>{@code BlockRenderDispatcher.renderBatched(BlockState, BlockPos, BlockAndTintGetter, PoseStack, VertexConsumer, boolean, RandomSource)}</td>
 *       <td>⚠️ <b>多两个参数</b>：{@code (…, RandomSource, ModelData, RenderType)}
 *           —— 1.20.1 的 Forge 版加了 {@code ModelData} 和 {@code RenderType}（字节码偏移 455）</td></tr>
 *   <tr><td>{@code @Local Map&lt;RenderType, BufferBuilder&gt; renderedLayers}</td>
 *       <td>❌ <b>不存在！</b> 1.20.1 里 {@code renderedLayers} 是个
 *           <b>{@code Set&lt;RenderType&gt;}</b>（只记录「这个层已经 beginLayer 过了」），
 *           buffer 本身是<b>现取</b>：{@code pack.builder(renderType)}（字节码偏移 231）</td></tr>
 * </table>
 *
 * <h2>★ 为什么 1.20.1 反而更好写</h2>
 *
 * 上游要靠 {@code @Local Map} 缓存 {@code BufferBuilder}；1.20.1 每次从
 * {@code ChunkBufferBuilderPack.builder()} 取（内部就是 computeIfAbsent），
 * 所以这里**只需要 {@code @Local(argsOnly=true) ChunkBufferBuilderPack}**。
 *
 * <h2>⚠️ 第三个注入点（半透明排序）在 1.20.1 是另一套机制</h2>
 *
 * 上游对 {@code Map.put(renderType, meshData)} 做 WrapOperation，对
 * 需要排序的层调 {@code meshData.sortQuads(...)}。
 *
 * 1.20.1 里排序由**原版自己**做（字节码偏移 526-529）：
 * <pre>
 *   bufferbuilder.setQuadSorting(VertexSorting.byDistance(...));
 *   compileResults.transparencyState = bufferbuilder.getSortState();
 * </pre>
 * 即：**只对 {@code RenderType.translucent()} 那一个 buffer** 做排序。
 *
 * <p>我们的自定义地形 RenderType 是独立的 buffer，**原版不会碰它** ——
 * 所以这里额外补一次排序（见 {@link #cooParticlesAPI$sortTerrainBuffers}）。
 * 若不补，半透明地形特效的叠加顺序会是「按写入顺序」而非「按距离」，
 * 可能出现深度穿插的观感问题。
 *
 * <p>⚠️ 需要为参与排序的每个地形 RenderType 都设一次 —— 但 {@code setQuadSorting}
 * 必须在 {@code end()} 之前调用，而本注入点在 {@code compile} 返回前（即所有
 * {@code end()} 之后）。所以这里用的是**「记录待排序层 + 延后到 upload 前处理」**
 * 的策略：{@link CooTerrainPipelineManager#markTerrainBuffersForSorting} 只是登记，
 * 真正的排序在 {@code CooTerrainPipelineManager} 的 upload 阶段完成。
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask")
public abstract class RebuildTaskMixin {

    /** 目标方法描述符：1.20.1 是 {@code (FFFLnet/minecraft/client/renderer/ChunkBufferBuilderPack;)} */
    private static final String COMPILE_DESC =
            "compile(FFFLnet/minecraft/client/renderer/ChunkBufferBuilderPack;)" +
                    "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk$RebuildTask$CompileResults;";




    @WrapOperation(
            method = COMPILE_DESC,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;" +
                            "renderLiquid(Lnet/minecraft/core/BlockPos;" +
                            "Lnet/minecraft/world/level/BlockAndTintGetter;" +
                            "Lcom/mojang/blaze3d/vertex/VertexConsumer;" +
                            "Lnet/minecraft/world/level/block/state/BlockState;" +
                            "Lnet/minecraft/world/level/material/FluidState;)V"
            )
    )
    private void cooParticlesAPI$writeTerrainFluidOverlayBatch(
            BlockRenderDispatcher dispatcher,
            BlockPos pos,
            BlockAndTintGetter level,
            VertexConsumer consumer,
            BlockState state,
            FluidState fluidState,
            Operation<Void> original,
            @Local(argsOnly = true) ChunkBufferBuilderPack pack
    ) {
        RenderType baseLayer = ItemBlockRenderTypes.getRenderLayer(fluidState);
        List<RenderType> overlayLayers =
                CooTerrainPipelineManager.resolveOverlayRenderTypes(state, baseLayer, pos);

        if (overlayLayers.isEmpty()
                || CooTerrainPipelineManager.shouldPreserveVanillaTerrainGeometry(overlayLayers)) {
            original.call(dispatcher, pos, level, consumer, state, fluidState);
        }
        for (RenderType overlayLayer : overlayLayers) {
            BufferBuilder overlayBuffer = pack.builder(overlayLayer);
            VertexConsumer overlayConsumer = CooTerrainPipelineManager.decorateVertexConsumer(
                    overlayLayer, pos, overlayBuffer);
            original.call(dispatcher, pos, level, overlayConsumer, state, fluidState);
        }
    }




    @WrapOperation(
            method = COMPILE_DESC,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;" +
                            "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;" +
                            "Lnet/minecraft/core/BlockPos;" +
                            "Lnet/minecraft/world/level/BlockAndTintGetter;" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lcom/mojang/blaze3d/vertex/VertexConsumer;Z" +
                            "Lnet/minecraft/util/RandomSource;" +
                            "Lnet/minecraftforge/client/model/data/ModelData;" +
                            "Lnet/minecraft/client/renderer/RenderType;)V"
            ),
            require = 0
    )
    private void cooParticlesAPI$writeTerrainOverlayBatch(
            BlockRenderDispatcher dispatcher,
            BlockState state,
            BlockPos pos,
            BlockAndTintGetter level,
            PoseStack poseStack,
            VertexConsumer consumer,
            boolean checkSides,
            RandomSource random,
            net.minecraftforge.client.model.data.ModelData modelData,
            RenderType originalRenderType,
            Operation<Void> original,
            @Local(argsOnly = true) ChunkBufferBuilderPack pack
    ) {
        RenderType baseLayer = ItemBlockRenderTypes.getChunkRenderType(state);
        List<RenderType> overlayLayers =
                CooTerrainPipelineManager.resolveOverlayRenderTypes(state, baseLayer, pos);

        if (overlayLayers.isEmpty()
                || CooTerrainPipelineManager.shouldPreserveVanillaTerrainGeometry(overlayLayers)) {
            poseStack.pushPose();
            try {
                original.call(dispatcher, state, pos, level, poseStack, consumer,
                        checkSides, random, modelData, originalRenderType);
            } finally {
                poseStack.popPose();
            }
        }
        for (RenderType overlayLayer : overlayLayers) {
            BufferBuilder overlayBuffer = pack.builder(overlayLayer);
            VertexConsumer overlayConsumer = CooTerrainPipelineManager.decorateVertexConsumer(
                    overlayLayer, pos, overlayBuffer);


            random.setSeed(state.getSeed(pos));
            poseStack.pushPose();
            try {
                original.call(dispatcher, state, pos, level, poseStack, overlayConsumer,
                        checkSides, random, modelData, overlayLayer);
            } finally {
                poseStack.popPose();
            }
        }
    }




    /**
     * 在 {@code compile} 收尾处，为「需要排序的自定义地形 RenderType」补一次排序。
     *
     * <h2>★★ 为什么需要这个注入</h2>
     *
     * 1.20.1 的 {@code compile} 末尾（字节码偏移 482-539）原版会对自己认识的那些
     * 层做排序：
     * <pre>
     *   BufferBuilder b = pack.builder(RenderType.translucent());
     *   if (!b.isCurrentBatchEmpty()) {
     *       b.setQuadSorting(VertexSorting.byDistance(
     *           px - blockpos.getX(), py - blockpos.getY(), pz - blockpos.getZ()));
     *       compileResults.transparencyState = b.getSortState();
     *   }
     * </pre>
     *
     * <b>但它只处理 {@code RenderType.translucent()} 这一个 buffer</b>。
     * 我们的地形覆盖层是**独立的 RenderType / 独立 buffer**，原版完全不会碰它 ⇒
     * 半透明地形特效的四边形叠加顺序会退化成「按写入顺序」，
     * 深度穿插的观感会和原版不一致。
     *
     * <p>注入点选在 {@code BufferBuilder.setQuadSorting} 的**调用之后**
     * —— 此时原版刚做完自己的排序，我们接着处理自定义层，
     * 复用同一个 {@code VertexSorting} 语义（相机相对方块的距离）。
     *
     * <p>⚠️ {@code setQuadSorting} 必须在该 buffer 的 {@code end()} **之前**调用，
     * 而本注入点仍在 {@code compile} 内、所有 {@code end()} 之前，所以是安全的。
     *
     * <p>⚠️ 只对 {@code requiresTerrainSorting(rt) == true} 的层操作
     * （即基于 {@code RenderType.translucent()} 的覆盖层），
     * solid / cutout 覆盖层不受影响（它们本来也不需要排序）。
     */
    @Inject(
            method = COMPILE_DESC,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;" +
                            "setQuadSorting(Lcom/mojang/blaze3d/vertex/VertexSorting;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void cooParticlesAPI$sortTerrainOverlayBuffers(
            float px, float py, float pz,
            ChunkBufferBuilderPack pack,
            CallbackInfoReturnable<Object> info,
            @Local(ordinal = 0) BlockPos origin
    ) {
        CooTerrainPipelineManager.sortTerrainBuffers(
                pack, origin,
                VertexSorting.byDistance(
                        px - origin.getX(),
                        py - origin.getY(),
                        pz - origin.getZ()
                )
        );
    }
}
