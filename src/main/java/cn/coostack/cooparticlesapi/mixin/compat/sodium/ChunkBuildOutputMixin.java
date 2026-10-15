package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 区块 build 结果被丢弃时，一并丢弃它挂着的待用覆盖层。
 *
 * ★ 移植注记（上游 1.21.1 NeoForge → Embeddium 1.20.1）：
 *  1) 包名 net.caffeinemc.mods.sodium.*  →  me.jellysquid.mods.sodium.*
 *     （Embeddium 沿用 Sodium 的**旧包名**，不是 caffeinemc）
 *  2) ★ 方法名改了：上游注入 destroy()，Embeddium 里叫 delete()
 *     字节码实证：javap → public void delete();
 *     写错的话 defaultRequire=1 会在启动时直接崩，而不是静默失效。
 */
@Mixin(value = ChunkBuildOutput.class, remap = false)
public class ChunkBuildOutputMixin {
    @Inject(method = "delete", at = @At("HEAD"))
    private void cooParticlesAPI$discardPendingTerrainOverlay(CallbackInfo info) {
        CooSodiumTerrainOverlay.discardBuildOutput((ChunkBuildOutput) (Object) this);
    }
}
