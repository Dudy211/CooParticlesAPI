package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * 区块网格上传时，顺手把地形覆盖层也上传上去；region 释放时一并释放。
 *
 * ★ 移植注记（上游 1.21.1 NeoForge → Embeddium 1.20.1）：
 *  1) 包名 net.caffeinemc.mods.sodium.*  →  me.jellysquid.mods.sodium.*
 *  2) ★ 方法名与参数类型都变了：
 *       上游：uploadResults(CommandList, Collection<BuilderTaskOutput>)
 *       本地：uploadMeshes (CommandList, Collection<ChunkBuildOutput>)
 *     字节码实证：javap →
 *       public void uploadMeshes(CommandList, java.util.Collection<ChunkBuildOutput>)
 *       public void delete(CommandList)
 *     注意还有同名私有重载 uploadMeshes(CommandList, RenderRegion, Collection) ——
 *     这里用**全描述符**注入，确保命中 public 那个（否则 Mixin 会因歧义而报错）。
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public class RenderRegionManagerMixin {
    @Inject(
            method = "uploadMeshes(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;" +
                    "Ljava/util/Collection;)V",
            at = @At("HEAD")
    )
    private void cooParticlesAPI$uploadTerrainOverlays(CommandList commandList,
                                                       Collection<ChunkBuildOutput> outputs,
                                                       CallbackInfo info) {
        CooSodiumTerrainOverlay.uploadResults(outputs);
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void cooParticlesAPI$releaseTerrainOverlays(CommandList commandList, CallbackInfo info) {
        CooSodiumTerrainOverlay.releaseAll();
    }
}
