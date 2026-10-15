package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * 让 {@link ChunkBufferBuilderPack} 也能为「自定义地形 RenderType」分配缓冲。
 *
 * <h2>★★ 移植注记（上游 1.21 → 1.20.1）</h2>
 *
 * <table>
 *   <tr><th>上游（1.21）</th><th>本地（1.20.1）</th></tr>
 *   <tr><td>{@code SectionBufferBuilderPack}</td>
 *       <td><b>{@code ChunkBufferBuilderPack}</b>（类改名）</td></tr>
 *   <tr><td>{@code buffer(RenderType): ByteBufferBuilder}</td>
 *       <td><b>{@code builder(RenderType): BufferBuilder}</b>
 *           （方法改名 + 返回类型不同）</td></tr>
 *   <tr><td>{@code Map&lt;RenderType, ByteBufferBuilder&gt; buffers}</td>
 *       <td><b>{@code Map&lt;RenderType, BufferBuilder&gt; builders}</b>（字段名与类型都变）</td></tr>
 * </table>
 *
 * <p>★ 1.20.1 的模型**更简单**：直接返回 {@code BufferBuilder}，不像 1.21 那样
 * 还要把 {@code ByteBufferBuilder} 交给调用方自己包一层。
 * 所以这里只要把「不认识的自定义 RenderType」也塞进 map 就行。
 *
 * <p>字节码实证（mapped_official jar）：
 * <pre>
 *   public com.mojang.blaze3d.vertex.BufferBuilder builder(RenderType);
 *   private final java.util.Map&lt;RenderType, BufferBuilder&gt; builders;
 * </pre>
 */
@Mixin(ChunkBufferBuilderPack.class)
public abstract class ChunkBufferBuilderPackMixin {

    @Shadow
    @Final
    private Map<RenderType, BufferBuilder> builders;

    @Inject(method = "builder", at = @At("HEAD"), cancellable = true)
    private void cooParticlesAPI$allocateTerrainPipelineBuffer(
            RenderType renderType,
            CallbackInfoReturnable<BufferBuilder> callback
    ) {
        if (!CooTerrainPipelineManager.isTerrainRenderType(renderType)) {
            return;
        }


        callback.setReturnValue(builders.computeIfAbsent(
                renderType,
                key -> new BufferBuilder(key.bufferSize())
        ));
    }
}
