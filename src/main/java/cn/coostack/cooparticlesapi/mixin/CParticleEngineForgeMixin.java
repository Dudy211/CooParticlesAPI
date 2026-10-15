package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.cparticle.CParticleRenderPass;
import cn.coostack.cooparticlesapi.cparticle.CParticleSystemManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★★★ cparticle GPU 粒子的**真正绘制入口**。
 *
 * <h2>这个类是从上游 `neoforge/` 侧搬过来的</h2>
 *
 * 上游在 `ParticleEngine.render(...)` 里插入
 * {@link CParticleSystemManager#renderParticlePass}，把 cparticle 的 GPU 粒子
 * 画进原版粒子 pass。上游 Fabric 侧也有等价的 `CParticleEngineFabricMixin`。
 *
 * 移植时整个平台侧目录没搬 → `renderParticlePass` 从来不被调用 →
 * **所有 cparticle GPU 粒子都不显示**。
 *
 * <h2>⚠️⚠️⚠️ 1.20.1 的致命差异：`ParticleEngine.render` 有**两个重载**</h2>
 *
 * 用 `javap` 核实（Forge 1.20.1-47.4.23）：
 * ```java
 * // ① 5 参：原版方法的空壳委托，内部直接 render(..., null) 转调 6 参
 * public void m_107336_(PoseStack, BufferSource$BufferSource, LightTexture, Camera, float) {
 *     this.render(poseStack, bufferSource, lightTexture, camera, partialTick, (Frustum) null);
 * }
 * // ② 6 参：Forge 补丁真正实现（多了 Frustum 做逐粒子视锥剔除）
 * public void render(PoseStack, BufferSource$BufferSource, LightTexture, Camera, float, Frustum)
 * ```
 *
 * **① 在 Forge 环境里没有任何调用者**——`LevelRenderer` 直接调 ②：
 * ```
 * invokevirtual ParticleEngine.render:(...;Lnet/minecraft/client/renderer/culling/Frustum;)V
 * ```
 * （已对整个 `forge-1.20.1-47.4.23-srg.jar` 做字节扫描：字符串 `m_107336_`
 *  只出现在 `ParticleEngine` 自身 = 仅有定义，**0 个调用点**。）
 *
 * 1.21 上游不存在这个分叉（没有 Frustum 重载），所以上游按「5 参」写是对的；
 * 我们照搬过来就**打在了永不执行的空壳上** → `renderParticlePass` 一次都不进。
 *
 * <h2>为什么不用 `@At(target = turnOffLightLayer)`</h2>
 *
 * 上游 1.21 用 `@At(value = "INVOKE", target = "...LightTexture;turnOffLightLayer()V",
 * shift = BEFORE)`。在 1.20.1 上该调用紧贴方法末尾的 `return`，
 * `shift = BEFORE` 在 SRG 环境找不到可用跳板 → 注入检查失败 → `require=1` 崩游戏。
 * 改用 `@At("RETURN")`：6 参方法里 `turnOffLightLayer()` 后**只有 1 个 return**，
 * 不会重复触发。
 *
 * <h2>refmap 注意（必须 remap = false）</h2>
 *
 * 6 参重载是 Forge 侧新增、**未参与混淆**，所以它在 dev 与 SRG 环境里都叫 `render`
 * （`output.tsrg` 里只有 5 参 `render → m_107336_`，没有 6 参的条目）。
 * 若让 Mixin 注解处理器去 remap，它会报
 * `Unable to locate obfuscation mapping for @Inject target render` 而**编译失败**；
 * 即便不报错也会错配到 5 参 `m_107336_`（等于没改）。
 * 因此这里用 **`remap = false` + 完整 6 参描述符**，按原样匹配未混淆的 `render`。
 *
 * 时机上安全：`CParticleRenderer.renderInternal` **自己快照并恢复全部 GL 状态**
 * （blend / 深度掩码 / 纹理绑定 / 光照层），即便此时原版已关掉光照层也会按需再开再恢复。
 */
@Mixin(ParticleEngine.class)
public abstract class CParticleEngineForgeMixin {

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("RETURN"),
            remap = false
    )
    private void cooparticlesapi$renderGpuParticles(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            LightTexture lightTexture,
            Camera camera,
            float partialTick,
            Frustum frustum,
            CallbackInfo ci
    ) {


        cn.coostack.cooparticlesapi.dbg.CpDbg.dL(
                "mixin/entry", 60,
                "6参 render(…,Frustum) 注入点已触发 → renderParticlePass(ALL)"
        );

        CParticleSystemManager.renderParticlePass(camera, partialTick, CParticleRenderPass.ALL);
    }
}
