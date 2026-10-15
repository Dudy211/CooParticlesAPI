package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType

/**
 * 把 [MultiBufferSource] 包装成「按 RenderType 缓存 + 可安全统一 flush」的样子。
 *
 * ## 背景（2026-09-23 崩溃根因之二）
 *
 * 1.20.1 的 `RenderLevelStageEvent` 里能拿到的，是
 * `Minecraft.renderBuffers().bufferSource()` —— 一个**全局共享**的
 * `BufferSource`。它的 `endBatch()`（无参）会：
 *
 * 1. `lastState = null` —— 清掉「当前正在用哪个 RenderType」的共享状态；
 * 2. 把**所有**已缓存的 builder 一次性 endBatch（含原版自己正在用的）。
 *
 * 而本 mod 原来在事件回调末尾直接 `buffer.endBatch()`。后果：
 * 后续原版渲染（例如紧接着画**僵尸**模型）拿到的 BufferBuilder 状态错乱
 * → `IndexOutOfBoundsException`（实测：崩在 `ModelPart$Cube` 里）。
 *
 * 上游 1.21 完全不需要这个调用（上游源码里 `endBatch` 出现 0 次），
 * 是移植时为了"保险"加的 —— 结果反而炸了原版渲染。
 *
 * ## 做法
 *
 * 本包装器把每个 RenderType 的 consumer **缓存在自己身上**（并且套上
 * [forFormat] 做顶点顺序重排），flush 时**只对「自己碰过的」RenderType**
 * 调 `endBatch(type)`，绝不整体 endBatch，从而不越界影响原版。
 */
class BufferedRenderTypes(private val delegate: MultiBufferSource) : MultiBufferSource {

    private val buffers = LinkedHashMap<RenderType, VertexConsumer>()

    override fun getBuffer(renderType: RenderType): VertexConsumer =
        buffers.getOrPut(renderType) {
            delegate.getBuffer(renderType).forFormat(renderType.format())
        }

    /**
     * 只刷自己用过的那些 RenderType。
     *
     * - 用 `endBatch(type)`（定点刷）而不是 `endBatch()`（全量刷）
     * - 每个 type 用 runCatching 兜底：某个 type 刷失败不能影响其它 type
     */
    fun flush() {
        val source = delegate as? MultiBufferSource.BufferSource


        buffers.values.forEach { c ->
            runCatching { (c as? PendingVertexHolder)?.flushPending() }
        }
        if (source != null) {
            buffers.keys.forEach { type ->
                runCatching { source.endBatch(type) }
            }
        }
        buffers.clear()
    }
}
