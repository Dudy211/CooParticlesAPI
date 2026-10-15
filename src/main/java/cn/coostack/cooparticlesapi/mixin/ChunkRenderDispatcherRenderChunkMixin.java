package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 让渲染区块也持有「自定义地形 RenderType」的顶点缓冲。
 *
 * <h2>★★ 移植注记（上游 1.21 → 1.20.1）</h2>
 *
 * <table>
 *   <tr><th>上游（1.21）</th><th>本地（1.20.1）</th></tr>
 *   <tr><td>{@code SectionRenderDispatcher.RenderSection}</td>
 *       <td><b>{@code ChunkRenderDispatcher.RenderChunk}</b>（嵌套类改名）</td></tr>
 *   <tr><td>{@code getBuffer(RenderType): VertexBuffer}</td>
 *       <td>✅ 同名同签名（javap: {@code public VertexBuffer getBuffer(RenderType)}，
 *           SRG {@code m_112807_}）</td></tr>
 *   <tr><td>{@code releaseBuffers()}</td>
 *       <td>✅ 同名同签名（javap: {@code public void releaseBuffers()}，
 *           SRG {@code m_112838_}）</td></tr>
 * </table>
 *
 * <p><b>实现与上游逐行一致</b>（buffer 存在本 mixin 自己的 {@code @Unique} 字段里，
 * 而不是塞进 {@code CooTerrainPipelineManager}）—— 这样：
 * <ul>
 *   <li>不污染共享管理器的状态；</li>
 *   <li>区块卸载时跟着实例一起被 GC / 显式 close。</li>
 * </ul>
 *
 * <p>★ 为什么需要它：原版只为 {@code RenderType.chunkBufferLayers()} 里那几种类型
 * 准备 {@link VertexBuffer}，我们自定义的地形 RenderType 不在其中，
 * 直接调原版 {@code getBuffer} 会 NPE。
 *
 * <p>⚠️ 必须在 render 线程创建 {@link VertexBuffer}（它持有 GL 对象）。
 * 渲染区块编译可能发生在工作线程，所以这里照搬上游的
 * {@code RenderSystem.recordRenderCall + CompletableFuture.join()} 方案。
 */
@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class ChunkRenderDispatcherRenderChunkMixin {

    @Unique
    private final Map<RenderType, VertexBuffer> cooParticlesAPI$terrainBuffers =
            Collections.synchronizedMap(new IdentityHashMap<>());

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void cooParticlesAPI$getTerrainBuffer(
            RenderType renderType,
            CallbackInfoReturnable<VertexBuffer> callback
    ) {
        if (!CooTerrainPipelineManager.isTerrainRenderType(renderType)) {
            return;
        }
        VertexBuffer buffer = cooParticlesAPI$terrainBuffers.get(renderType);
        if (buffer != null) {
            callback.setReturnValue(buffer);
            return;
        }
        if (RenderSystem.isOnRenderThread()) {
            callback.setReturnValue(cooParticlesAPI$createTerrainBuffer(renderType));
            return;
        }

        CompletableFuture<VertexBuffer> created = new CompletableFuture<>();
        RenderSystem.recordRenderCall(() -> {
            try {
                created.complete(cooParticlesAPI$createTerrainBuffer(renderType));
            } catch (Throwable error) {
                created.completeExceptionally(error);
            }
        });
        callback.setReturnValue(created.join());
    }

    @Unique
    private VertexBuffer cooParticlesAPI$createTerrainBuffer(RenderType renderType) {
        return cooParticlesAPI$terrainBuffers.computeIfAbsent(
                renderType,
                ignored -> new VertexBuffer(VertexBuffer.Usage.STATIC)
        );
    }

    @Inject(method = "releaseBuffers", at = @At("TAIL"))
    private void cooParticlesAPI$releaseTerrainBuffers(CallbackInfo callback) {
        synchronized (cooParticlesAPI$terrainBuffers) {
            cooParticlesAPI$terrainBuffers.values().forEach(VertexBuffer::close);
            cooParticlesAPI$terrainBuffers.clear();
        }
    }
}
