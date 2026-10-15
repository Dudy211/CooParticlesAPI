package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界渲染器 reload 时释放全部地形覆盖层。
 *
 * <p>瞄准 {@code SodiumWorldRenderer.reload()}（Embeddium 0.3.31 实测存在，
 * javap: {@code public void reload();}）。
 *
 * <p>★ 移植注记：包名 {@code net.caffeinemc} → <b>{@code me.jellysquid}</b>；方法名一致。
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class SodiumWorldRendererMixin {
    @Inject(method = "reload", at = @At("HEAD"))
    private void cooParticlesAPI$releaseTerrainOverlaysForReload(CallbackInfo info) {
        CooSodiumTerrainOverlay.releaseAll();
    }
}
