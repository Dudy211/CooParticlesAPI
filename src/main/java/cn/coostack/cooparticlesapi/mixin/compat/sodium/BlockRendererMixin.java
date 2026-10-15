package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 地形覆盖层的**数据采集入口**：每个方块面在写进 Sodium 顶点缓冲之前，
 * 顺便把它的几何复制一份到我们的扩展缓冲（带 EffectUV 与生效 tick）。
 *
 * <h2>★★★ 这是整个 Embeddium 兼容层里改动最大的一个文件</h2>
 *
 * 上游（1.21.1 NeoForge + Sodium 0.6.x）走的是 FRAPI 那条路：
 * <pre>
 *   @Mixin(BlockRenderer.class)
 *   public abstract class BlockRendererMixin extends AbstractBlockRenderContext {
 *       @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;
 *       @Inject(method = "bufferQuad",
 *               at = @At(value="INVOKE", target="...TerrainRenderPass;isTranslucent()Z"),
 *               cancellable = true)
 *       private void ...(MutableQuadViewImpl quad, float[] brightness, Material material,
 *                        CallbackInfo info, @Local(name="pass") TerrainRenderPass pass) { ... }
 *   }
 * </pre>
 *
 * <p><b>1.20.1 / Embeddium 里这些东西一个都不存在：</b>
 * <ul>
 *   <li>{@code BlockRenderer} <b>不继承</b> {@code AbstractBlockRenderContext}
 *       （Embeddium 的 FRAPI 在 {@code org.embeddedt.embeddium.render.frapi}，是另一套）；</li>
 *   <li>没有 {@code bufferQuad} 方法 —— 真正的几何写入在 <b>{@code writeGeometry}</b>；</li>
 *   <li>没有 {@code MutableQuadViewImpl} —— 用 {@code BakedQuadView}；</li>
 *   <li>没有 {@code @Local(name="pass")} —— 但 {@code material.pass} 直接给到同一个对象。</li>
 * </ul>
 *
 * <h2>移植映射</h2>
 * <table>
 *   <tr><th>上游</th><th>本地（Embeddium 0.3.31）</th></tr>
 *   <tr><td>{@code bufferQuad(quad, brightness, material, info, pass)}</td>
 *       <td>{@code writeGeometry(ctx, builder, offset, material, quad, colors, light)}</td></tr>
 *   <tr><td>{@code @Local(name="pass")}</td>
 *       <td>{@code material.pass}（{@code Material.pass} 是 public final 字段，javap 实证）</td></tr>
 *   <tr><td>{@code brightness[]}</td>
 *       <td>{@code light.br[]}（QuadLightData.br，就是 AO 亮度）</td></tr>
 *   <tr><td>{@code this.vertices}</td>
 *       <td>同名 {@code @Shadow @Final vertices}（Embeddium 里仍在，javap 实证）</td></tr>
 *   <tr><td>{@code type}（父类字段）</td>
 *       <td>{@code ctx.renderLayer()}（javap 实证）</td></tr>
 *   <tr><td>{@code state} / {@code pos}</td>
 *       <td>{@code ctx.state()} / {@code ctx.pos()}</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么用 HEAD 而不是上游那种「INVOKE + cancellable」</h2>
 *
 * 上游在 {@code TerrainRenderPass.isTranslucent()} 调用点取消后续写入，是为了
 * 「不让原版几何写进区块缓冲」（几何被我们接管了）。
 * 1.20.1 的 {@code writeGeometry} 末尾只有一次 {@code vertexBuffer.push(...)}，
 * 结构完全不同，套用上游的取消点没有意义。
 *
 * <p>这里改为在 <b>HEAD</b> 捕获一份数据（不取消原写入） —— 即
 * **原版几何照常写入，我们额外多写一份到自己的缓冲**。
 * 代价是区块缓冲里多了一份原版几何（GPU 开销极小，因为最终被我们的 RenderType
 * 画的覆盖层遮住或被 {@code shouldPreserveVanillaTerrainGeometry} 决定是否保留）。
 * 好处是**逻辑简单、不可能把原版渲染搞坏** —— 对一个可选兼容层来说这是正确的取舍。
 *
 * <p>⚠️ 若要改成「接管」语义（取消原写入），需确认
 * {@code ChunkModelBuilder.getVertexBuffer(normalFace).push(...)} 的副作用
 * 是否被别处依赖，风险较高，暂不做。
 */
@Mixin(value = BlockRenderer.class, remap = false)
public class BlockRendererMixin {

    @Shadow(remap = false)
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Inject(method = "writeGeometry", at = @At("HEAD"))
    private void cooParticlesAPI$captureTerrainOverlay(BlockRenderContext ctx,
                                                       ChunkModelBuilder builder,
                                                       Vec3 offset,
                                                       Material material,
                                                       BakedQuadView quad,
                                                       int[] colors,
                                                       QuadLightData light,
                                                       CallbackInfo info) {
        if (material == null || quad == null) {
            return;
        }


        TerrainRenderPass pass = material.pass;
        if (pass == null) {
            return;
        }


        RenderType original = ctx.renderLayer();
        if (original == null) {
            return;
        }

        RenderType baseLayer = CooSodiumTerrainOverlay.resolveBaseLayer(original, material, pass);
        if (baseLayer == null) {
            return;
        }
        List<RenderType> overlays =
                CooTerrainPipelineManager.resolveOverlayRenderTypes(ctx.state(), baseLayer, ctx.pos());
        if (overlays.isEmpty()) {
            return;
        }


        float[] brightness = light != null ? light.br : null;
        for (RenderType overlay : overlays) {
            CooSodiumTerrainOverlay.captureQuad(
                    overlay, pass, ctx.pos(), quad, vertices, brightness);
        }
    }
}
