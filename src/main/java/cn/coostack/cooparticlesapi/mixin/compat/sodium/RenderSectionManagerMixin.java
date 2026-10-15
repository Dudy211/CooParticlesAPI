package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地形覆盖层的渲染入口：每渲染完一个 terrain pass 就接着画覆盖层。
 *
 * ★ 移植注记（上游 1.21.1 NeoForge → Embeddium 1.20.1）：
 *  1) 包名 net.caffeinemc.mods.sodium.*  →  me.jellysquid.mods.sodium.*
 *  2) 方法签名**完全一致**，无需改名。字节码实证：
 *     javap → public void renderLayer(ChunkRenderMatrices, TerrainRenderPass, double, double, double)
 *     javap → public void destroy()
 *  3) renderLists 字段用 @Shadow 读取。注意 remap = false —— Embeddium 不是 MC 本体类，
 *     不参与 SRG 重映射，字段名保持原样（verify_reobf.py 会跳过 remap=false 的类）。
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public class RenderSectionManagerMixin {
    @Shadow(remap = false)
    private SortedRenderLists renderLists;

    @Inject(method = "renderLayer", at = @At("TAIL"))
    private void cooParticlesAPI$renderTerrainOverlays(ChunkRenderMatrices matrices,
                                                       TerrainRenderPass pass,
                                                       double cameraX,
                                                       double cameraY,
                                                       double cameraZ,
                                                       CallbackInfo info) {
        CooSodiumTerrainOverlay.renderOrDefer(renderLists, matrices, pass, cameraX, cameraY, cameraZ);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cooParticlesAPI$releaseTerrainOverlays(CallbackInfo info) {
        CooSodiumTerrainOverlay.releaseAll();
    }
}
