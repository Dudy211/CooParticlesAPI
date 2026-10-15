package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * section 被删除时释放它持有的地形覆盖层。
 *
 * <p>瞄准 {@code RenderSection.delete()}（Embeddium 0.3.31 实测存在，
 * javap: {@code public void delete();}）。
 *
 * <p>★ 移植注记（上游 → Embeddium 1.20.1）：
 * <ul>
 *   <li>包名 {@code net.caffeinemc.mods.sodium.*} → <b>{@code me.jellysquid.mods.sodium.*}</b>
 *       （Embeddium 沿用 Sodium 旧包名，不是 caffeinemc）</li>
 *   <li>方法名 {@code delete} 与上游一致，无需改名</li>
 * </ul>
 */
@Mixin(value = RenderSection.class, remap = false)
public class RenderSectionMixin {
    @Inject(method = "delete", at = @At("HEAD"))
    private void cooParticlesAPI$releaseTerrainOverlay(CallbackInfo info) {
        CooSodiumTerrainOverlay.releaseSection((RenderSection) (Object) this);
    }
}
