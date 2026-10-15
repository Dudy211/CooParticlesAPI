package cn.coostack.cooparticlesapi.mixin.compat.sodium;

import cn.coostack.cooparticlesapi.renderer.terrain.sodium.CooSodiumTerrainOverlay;
import com.llamalad7.mixinextras.sugar.Local;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 区块 meshing 任务的三段式注入：开始 → 标记 pass → 收尾。
 *
 * ★ 移植注记（上游 1.21.1 NeoForge → Embeddium 1.20.1）：
 *  1) 包名 net.caffeinemc.mods.sodium.*  →  me.jellysquid.mods.sodium.*
 *     （三处 @Inject 的 method 描述符里都硬编码了类名，**必须全部替换**，
 *       漏一处就是 "Unable to locate obfuscation mapping" 或运行时 ScriptError）
 *  2) 方法签名完全一致。字节码实证：
 *     javap → public ChunkBuildOutput execute(ChunkBuildContext, CancellationToken)
 *  3) @Local(name = "renderData") 的变量名经 LocalVariableTable 核实**依然叫 renderData**，
 *     类型 BuiltSectionInfo$Builder 也一致 —— 无需改名。
 *  4) INVOKE 目标 BuiltSectionInfo$Builder.build() 在字节码里确认存在（偏移 806）：
 *     invokevirtual BuiltSectionInfo$Builder.build:()LBuiltSectionInfo;
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class ChunkBuilderMeshingTaskMixin {

    private static final String EXECUTE_DESC =
            "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;" +
                    "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)" +
                    "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";

    @Inject(method = EXECUTE_DESC, at = @At("HEAD"))
    private void cooParticlesAPI$beginTerrainOverlayBuild(ChunkBuildContext context,
                                                          CancellationToken cancellationToken,
                                                          CallbackInfoReturnable<ChunkBuildOutput> callback) {
        CooSodiumTerrainOverlay.beginBuild();
    }

    @Inject(
            method = EXECUTE_DESC,
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/data/BuiltSectionInfo$Builder;" +
                            "build()Lme/jellysquid/mods/sodium/client/render/chunk/data/BuiltSectionInfo;"
            )
    )
    private void cooParticlesAPI$markTerrainOverlayPasses(ChunkBuildContext context,
                                                          CancellationToken cancellationToken,
                                                          CallbackInfoReturnable<ChunkBuildOutput> callback,
                                                          @Local(name = "renderData") BuiltSectionInfo.Builder renderData) {
        CooSodiumTerrainOverlay.markRenderPasses(renderData);
    }

    @Inject(method = EXECUTE_DESC, at = @At("RETURN"))
    private void cooParticlesAPI$finishTerrainOverlayBuild(ChunkBuildContext context,
                                                           CancellationToken cancellationToken,
                                                           CallbackInfoReturnable<ChunkBuildOutput> callback) {
        CooSodiumTerrainOverlay.finishBuild(callback.getReturnValue());
    }
}
