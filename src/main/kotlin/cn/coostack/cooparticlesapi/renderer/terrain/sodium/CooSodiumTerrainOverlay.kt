package cn.coostack.cooparticlesapi.renderer.terrain.sodium

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.compat.IrisCompat
import cn.coostack.cooparticlesapi.compat.IrisShadowPassState
import cn.coostack.cooparticlesapi.compat.mc1201.forFormat
import cn.coostack.cooparticlesapi.renderer.terrain.CooEffectUvResolver
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainVertexFormats
import cn.coostack.cooparticlesapi.renderer.post.OpenGlPostEffectExecutionBackend
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.caffeinemc.mods.sodium.api.util.ColorABGR
import me.jellysquid.mods.sodium.client.render.texture.SpriteUtil
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.GL_DEPTH_FUNC
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK
import org.lwjgl.opengl.GL11.GL_LEQUAL
import org.lwjgl.opengl.GL11.GL_VIEWPORT
import org.lwjgl.opengl.GL11.glGetBoolean
import org.lwjgl.opengl.GL11.glGetIntegerv
import org.lwjgl.opengl.GL11.glIsEnabled
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glGetInteger

/**
 * 管理 Sodium 0.6.x 的 section 级地形覆盖构建、GPU 上传和绘制。
 *
 * 区块工作线程按 [beginBuild]、多次 [captureQuad]、[markRenderPasses]、[finishBuild] 的顺序生成
 * CPU 批次；渲染线程通过 [uploadResults] 将批次绑定到 RenderSection，再由 [renderOrDefer] 绘制。
 * Iris shader pack 激活时绘制会暂存到 [flushDeferred]，等最终合成结束后使用 Iris 深度重放。
 * 所有 CPU 和 GPU 资源分别跟随 ChunkBuildOutput 与 RenderSection 生命周期释放。
 */
internal object CooSodiumTerrainOverlay {
    private val activeBuild = ThreadLocal<BuildCollector?>()
    private val pending = CooIdentityResourceStore<ChunkBuildOutput, BuiltBatch>()
    private val uploaded = CooIdentityResourceStore<RenderSection, UploadedBatch>()
    private val deferredDraws = ArrayList<DeferredDraw>()

    /**
     * 把 Sodium terrain pass 还原为对应的原版 RenderType。
     *
     * @param original 方块原本使用的 RenderType，用于保留 tripwire 或未知 pass
     * @param material Sodium 为当前方块面选择的材质
     * @param pass 当前 Sodium terrain pass
     * @return 与 pass 和材质 mipmap 规则对应的原版 terrain layer
     */
    @JvmStatic
    fun resolveBaseLayer(original: RenderType, material: Material, pass: TerrainRenderPass): RenderType {
        return when (pass) {
            DefaultTerrainRenderPasses.SOLID -> RenderType.solid()
            DefaultTerrainRenderPasses.CUTOUT -> {
                if (material.mipped) RenderType.cutoutMipped() else RenderType.cutout()
            }
            DefaultTerrainRenderPasses.TRANSLUCENT -> {
                if (original === RenderType.tripwire()) RenderType.tripwire() else RenderType.translucent()
            }
            else -> original
        }
    }

    /**
     * 开始当前区块构建线程的一次地形覆盖收集。
     *
     * 同一线程上未结束的旧收集器会先被关闭，避免异常构建遗留本地缓冲。
     */
    @JvmStatic
    fun beginBuild() {
        activeBuild.get()?.close()
        activeBuild.set(BuildCollector())
    }

    /**
     * 把一个已解析为地形 Pipeline 的方块面复制到扩展顶点缓冲。
     *
     * 方法保留原 UV、颜色、AO、光照和法线，并额外写入 EffectUV 与绝对生效 tick。
     * 未处于构建阶段或 RenderType 未登记时直接忽略。
     *
     * ★★ 移植注记（上游 1.21.1 NeoForge → Embeddium 1.20.1）—— 本方法是整个兼容层改动最大的地方：
     *
     *  1) quad 类型：上游 FRAPI 的 `MutableQuadViewImpl` → 本地的 `ModelQuadView`
     *     （Embeddium 的 FRAPI 在 `org.embeddedt.embeddium.render.frapi`，接口完全不同）
     *
     *  2) sprite：上游 `quad.sprite(SpriteFinderCache.forBlockAtlas())`
     *     → 本地 `quad.getSprite()` —— **更简单**。ModelQuadView 自己就带 sprite 引用，
     *     不需要再走 SpriteFinder 反查（那套是给「只有 UV 没有 sprite」的场景准备的）。
     *
     *  3) 法线：上游 `quad.faceNormal()` 返回 Vector3f、`quad.normalX/Y/Z(i)` 是逐顶点。
     *     本地 `ModelQuadView` 只有：
     *       · `getLightFace(): Direction`   —— 面的朝向
     *       · `getForgeNormal(i): Int`       —— **Forge 加的逐顶点法线（packed）**
     *       · `getComputedFaceNormal(): Int` —— 算出来的面法线（packed）
     *     本地**没有现成的 unpack 工具**（ModelQuadUtil 只有 calculateNormal/mergeNormal），
     *     所以这里自己解包：{@link #unpackNormal}。
     *     逐顶点法线为 0（未设置）时退化为面法线。
     *
     *  4) ★ AO：上游 1.21 的 `ChunkVertexEncoder.Vertex` 有 `vertex.ao` 字段；
     *     **1.20.1 的 Vertex 只有 x/y/z/color/u/v/light 七个字段，没有 ao**。
     *     1.20.1 的 AO 实际存在 `QuadLightData.br[i]`（brightness）里 ——
     *     见 BlockRenderer.writeGeometry 字节码：
     *       `out.color = ColorABGR.withAlpha(colors[srcIndex], light.br[srcIndex])`
     *     所以这里改成 `ColorABGR.withAlpha(vertex.color, brightness[index])`，
     *     并把 `QuadLightData` 作为**新参数**传进来（调用方 writeGeometry 手里正好有）。
     *
     * @param renderType 当前方块面解析到的 Coo 地形 RenderType
     * @param pass 当前 Sodium terrain pass
     * @param blockPos 当前方块的世界坐标
     * @param quad 方块面（sprite + 逐顶点法线）
     * @param vertices Sodium 编码前的四个区块顶点
     * @param brightness 逐顶点亮度/AO，来自 QuadLightData.br；为 null 时不做 AO 乘法
     */
    @JvmStatic
    fun captureQuad(
        renderType: RenderType,
        pass: TerrainRenderPass,
        blockPos: BlockPos,
        quad: ModelQuadView,
        vertices: Array<ChunkVertexEncoder.Vertex>,
        brightness: FloatArray? = null
    ) {
        val pipeline = CooTerrainPipelineManager.pipelineFor(renderType) ?: return
        val collector = activeBuild.get() ?: return
        val sprite = quad.sprite ?: return
        val activatedAt = CooTerrainPipelineManager.activationAt(renderType, blockPos)
        collector.append(renderType, pass, sprite, blockPos) { rawBuilder ->

            val wrapped = rawBuilder.forFormat(CooTerrainVertexFormats.BLOCK_EFFECT)
            vertices.forEachIndexed { index, vertex ->

                val packed = quad.getForgeNormal(index)
                val n = if (packed != 0) unpackNormal(packed) else normalOf(quad.lightFace)
                val effectUv = CooEffectUvResolver.resolve(
                    mode = pipeline.effectUvMode,
                    blockPos = blockPos,
                    x = vertex.x,
                    y = vertex.y,
                    z = vertex.z,
                    baseU = vertex.u,
                    baseV = vertex.v,
                    normalX = n[0],
                    normalY = n[1],
                    normalZ = n[2]
                )
                val color = if (brightness != null && index < brightness.size) {
                    ColorABGR.withAlpha(vertex.color, brightness[index])
                } else {
                    vertex.color
                }






















                wrapped.vertex(vertex.x.toDouble(), vertex.y.toDouble(), vertex.z.toDouble())
                    .color(color)
                    .uv(vertex.u, vertex.v)
                    .overlayCoords(CooEffectUvResolver.pack(effectUv.u), CooEffectUvResolver.pack(effectUv.v))
                    .uv2(CooEffectUvResolver.packLightWithActivation(vertex.light, activatedAt))
                    .normal(n[0], n[1], n[2])
            }
        }
    }

    /**
     * 解包 Sodium/Embeddium 的 packed 法线（每分量 1 字节，有符号）。
     *
     * <p>打包格式与 {@code ModelQuadUtil.mergeNormal} 一致：
     * {@code (x & 0xFF) | ((y & 0xFF) << 8) | ((z & 0xFF) << 16)}，
     * 每个分量是 -128..127 的有符号字节，对应单位向量的 x/y/z * 127。
     *
     * <p>⚠️ 1.20.1 的 Embeddium **没有**公开的 unpack 工具
     * （ModelQuadUtil 只有 calculateNormal / mergeNormal / findNormalFace），
     * 所以这里自己写。上游 1.21 有现成的 {@code quad.normalX/Y/Z(i)}，是版本差异。
     */
    @JvmStatic
    fun unpackNormal(packed: Int): FloatArray {
        val x = (packed and 0xFF).toByte().toFloat() / 127.0f
        val y = ((packed ushr 8) and 0xFF).toByte().toFloat() / 127.0f
        val z = ((packed ushr 16) and 0xFF).toByte().toFloat() / 127.0f
        return floatArrayOf(x, y, z)
    }

    /** 把一个 {@link Direction} 转成单位法线分量。 */
    @JvmStatic
    fun normalOf(dir: Direction?): FloatArray = when (dir) {
        null -> floatArrayOf(0f, 1f, 0f)
        Direction.DOWN -> floatArrayOf(0f, -1f, 0f)
        Direction.UP -> floatArrayOf(0f, 1f, 0f)
        Direction.NORTH -> floatArrayOf(0f, 0f, -1f)
        Direction.SOUTH -> floatArrayOf(0f, 0f, 1f)
        Direction.WEST -> floatArrayOf(-1f, 0f, 0f)
        Direction.EAST -> floatArrayOf(1f, 0f, 0f)
    }

    /**
     * 把当前收集器使用的 terrain pass 标记到 Sodium 构建结果。
     *
     * @param renderData 当前 section 的构建元数据
     */
    @JvmStatic
    fun markRenderPasses(renderData: BuiltSectionInfo.Builder) {
        activeBuild.get()?.renderPasses()?.forEach { pass ->
            renderData.addRenderPass(pass)
        }
    }

    /**
     * 结束当前线程的覆盖收集，并把生成批次关联到 ChunkBuildOutput。
     *
     * @param output 成功的 Sodium 区块构建结果；为 `null` 时只释放收集器
     */
    @JvmStatic
    fun finishBuild(output: ChunkBuildOutput?) {
        val collector = activeBuild.get() ?: return
        activeBuild.remove()
        if (output == null) {
            collector.close()
            return
        }
        val batches = collector.build()
        pending.replace(output, batches, BuiltBatch::close)
    }

    /**
     * 丢弃尚未上传的区块构建结果及其 CPU 顶点缓冲。
     *
     * @param output 被 Sodium 取消或替换的构建结果
     */
    @JvmStatic
    fun discardBuildOutput(output: ChunkBuildOutput) {
        pending.release(output, BuiltBatch::close)
    }

    /**
     * 在渲染线程把一批 Sodium 构建结果上传为 section 级 GPU 顶点缓冲。
     *
     * 同一 RenderSection 的旧覆盖批次会在替换时释放。
     *
     * @param outputs 本轮 Sodium 上传队列，非 ChunkBuildOutput 项会被忽略
     * @throws IllegalStateException 当前不在渲染线程时抛出
     */
    @JvmStatic
    fun uploadResults(outputs: Collection<ChunkBuildOutput>) {
        check(RenderSystem.isOnRenderThread()) { "Sodium terrain overlays must upload on the render thread" }
        outputs.forEach { output ->
            if (output !is ChunkBuildOutput) return@forEach
            val built = pending.take(output).orEmpty()
            val gpuBatches = built.mapNotNull { upload(output.render, it) }
            uploaded.replace(output.render, gpuBatches, ::closeUploaded)
        }
    }

    /**
     * 绘制当前 Sodium pass 的可见地形覆盖，或在 Iris 模式下保存为延迟绘制。
     *
     * 阴影 pass 不绘制覆盖层；无法可靠判断 Iris 阴影状态时会触发原版几何降级。
     *
     * @param renderLists Sodium 已排序的可见 section 列表
     * @param matrices 当前区块渲染矩阵
     * @param pass 当前 terrain pass
     * @param cameraX 相机世界 X 坐标
     * @param cameraY 相机世界 Y 坐标
     * @param cameraZ 相机世界 Z 坐标
     */
    @JvmStatic
    fun renderOrDefer(
        renderLists: SortedRenderLists,
        matrices: ChunkRenderMatrices,
        pass: TerrainRenderPass,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ) {
        if (!CooTerrainPipelineManager.isSodiumTerrainOverlayEnabled()) return
        val collectedDrawGroups = collectDrawGroups(renderLists, pass)
        if (collectedDrawGroups.isEmpty()) return
        val drawGroups = LinkedHashMap<RenderType, MutableList<DrawEntry>>()
        collectedDrawGroups.forEach { (renderType, entries) ->
            val visibleEntries = if (!CooTerrainPipelineManager.isMappingRenderType(renderType)) {
                entries
            } else {
                val sectionMaxOffset = 15
                entries.filterTo(ArrayList<DrawEntry>()) { entry ->
                    CooTerrainPipelineManager.isTerrainMappingSectionVisible(
                        renderType,
                        entry.section.originX,
                        entry.section.originY,
                        entry.section.originZ,
                        entry.section.originX + sectionMaxOffset,
                        entry.section.originY + sectionMaxOffset,
                        entry.section.originZ + sectionMaxOffset
                    )
                }
            }
            if (visibleEntries.isNotEmpty()) {
                drawGroups[renderType] = visibleEntries
            }
        }
        if (drawGroups.isEmpty()) return
        val postDrawGroups = LinkedHashMap<RenderType, MutableList<DrawEntry>>()
        val immediateDrawGroups = LinkedHashMap<RenderType, MutableList<DrawEntry>>()
        drawGroups.forEach { (renderType, entries) ->
            if (CooTerrainPipelineManager.isTerrainPostRenderType(renderType)) {
                postDrawGroups[renderType] = entries
            } else {
                immediateDrawGroups[renderType] = entries
            }
        }

        val modelView = Matrix4f(matrices.modelView())
        val projection = Matrix4f(matrices.projection())
        postDrawGroups.forEach { (renderType, entries) ->
            val snapshot = entries.toList()
            CooTerrainPipelineManager.recordPostDraw(renderType) {
                drawSafely(
                    renderType,
                    snapshot,
                    modelView,
                    projection,
                    cameraX,
                    cameraY,
                    cameraZ,
                    false
                )
            }
        }
        if (immediateDrawGroups.isEmpty()) return

        if (CooTerrainPipelineManager.shouldPreserveVanillaTerrainGeometry()) {
            when (IrisCompat.shadowPassState()) {
                IrisShadowPassState.ACTIVE -> return
                IrisShadowPassState.UNKNOWN -> {
                    CooTerrainPipelineManager.handleSodiumOverlayFailure(
                        "Iris shadow-pass detection",
                        immediateDrawGroups.keys.firstOrNull(),
                        IllegalStateException("Iris shadow-pass state is unavailable")
                    )
                    return
                }
                IrisShadowPassState.INACTIVE -> Unit
            }
            synchronized(deferredDraws) {
                deferredDraws += DeferredDraw(
                    immediateDrawGroups.mapValues { (_, entries) -> entries.toList() },
                    modelView,
                    projection,
                    cameraX,
                    cameraY,
                    cameraZ
                )
            }
            return
        }
        renderDrawGroups(
            immediateDrawGroups,
            modelView,
            projection,
            cameraX,
            cameraY,
            cameraZ,
            false
        )
    }

    /**
     * 在 Iris 最终合成后绘制并清空本帧延迟的 Sodium 地形覆盖。
     *
     * 该入口负责包围最终合成状态与 overlay batch；兼容条件失效时只清空队列。
     */
    @JvmStatic
    fun flushDeferred() {
        val draws = synchronized(deferredDraws) {
            deferredDraws.toList().also { deferredDraws.clear() }
        }
        if (draws.isEmpty() ||
            !CooTerrainPipelineManager.isSodiumTerrainOverlayEnabled() ||
            !CooTerrainPipelineManager.shouldPreserveVanillaTerrainGeometry()
        ) {
            return
        }
        val renderTypes = draws.flatMap { it.drawGroups.keys }.distinct()
        CooTerrainPipelineManager.beginFinalCompositeTerrainOverlay()
        try {
            if (!CooTerrainPipelineManager.beginOverlayBatch(renderTypes)) return
            try {
                if (!CooTerrainPipelineManager.isSodiumTerrainOverlayEnabled()) return
                drawLoop@ for (draw in draws) {
                    for ((renderType, entries) in draw.drawGroups) {
                        if (!drawSafely(
                            renderType,
                            entries,
                            draw.modelView,
                            draw.projection,
                            draw.cameraX,
                            draw.cameraY,
                            draw.cameraZ,
                            true
                        )) break@drawLoop
                        CooTerrainPipelineManager.recordPostDraw(renderType) {
                            drawSafely(
                                renderType,
                                entries,
                                draw.modelView,
                                draw.projection,
                                draw.cameraX,
                                draw.cameraY,
                                draw.cameraZ,
                                false
                            )
                        }
                    }
                }
            } finally {
                CooTerrainPipelineManager.endOverlayBatch()
            }
        } finally {
            CooTerrainPipelineManager.endFinalCompositeTerrainOverlay()
        }
    }

    private fun renderDrawGroups(
        drawGroups: LinkedHashMap<RenderType, MutableList<DrawEntry>>,
        modelView: Matrix4f,
        projection: Matrix4f,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        irisComposite: Boolean
    ) {
        val renderTypes = drawGroups.keys.toList()
        if (!CooTerrainPipelineManager.beginOverlayBatch(renderTypes)) return
        try {
            if (!CooTerrainPipelineManager.isSodiumTerrainOverlayEnabled()) return
            for ((renderType, entries) in drawGroups) {
                if (!drawSafely(
                    renderType,
                    entries,
                    modelView,
                    projection,
                    cameraX,
                    cameraY,
                    cameraZ,
                    irisComposite
                )) return
                CooTerrainPipelineManager.recordPostDraw(renderType) {
                    drawSafely(
                        renderType,
                        entries,
                        modelView,
                        projection,
                        cameraX,
                        cameraY,
                        cameraZ,
                        false
                    )
                }
            }
        } finally {
            CooTerrainPipelineManager.endOverlayBatch()
        }
    }

    /**
     * 释放单个 Sodium RenderSection 持有的全部地形覆盖 GPU 资源。
     *
     * @param section 已删除、重建或离开缓存的 RenderSection
     */
    @JvmStatic
    fun releaseSection(section: RenderSection) {
        uploaded.release(section, ::closeUploaded)
    }

    /**
     * 释放所有线程本地构建器、待上传批次、GPU 批次和延迟绘制记录。
     *
     * 由客户端资源重载或关闭生命周期调用。
     */
    @JvmStatic
    fun releaseAll() {
        activeBuild.get()?.close()
        activeBuild.remove()
        pending.clear(BuiltBatch::close)
        uploaded.clear(::closeUploaded)
        synchronized(deferredDraws) {
            deferredDraws.clear()
        }
    }

    private fun upload(section: RenderSection, batch: BuiltBatch): UploadedBatch? {
        val vertexBuffer = VertexBuffer(VertexBuffer.Usage.STATIC)
        return try {












            VertexBuffer.unbind()
            vertexBuffer.bind()
            vertexBuffer.upload(batch.rendered)
            UploadedBatch(batch.renderType, batch.pass, vertexBuffer, batch.sprites)
        } catch (error: RuntimeException) {
            vertexBuffer.close()
            CooParticlesConstants.logger.error(
                "Failed to upload Sodium terrain overlay for section [{}, {}, {}]",
                section.chunkX,
                section.chunkY,
                section.chunkZ,
                error
            )
            CooTerrainPipelineManager.handleSodiumOverlayFailure(
                "section upload",
                batch.renderType,
                error
            )
            null
        } finally {
            VertexBuffer.unbind()
            batch.close()
        }
    }

    private fun collectDrawGroups(
        renderLists: SortedRenderLists,
        pass: TerrainRenderPass
    ): LinkedHashMap<RenderType, MutableList<DrawEntry>> {
        val groups = LinkedHashMap<RenderType, MutableList<DrawEntry>>()
        val reverse = pass.isSorted
        val lists = renderLists.iterator(reverse)
        while (lists.hasNext()) {
            val renderList = lists.next()
            val sections = renderList.sectionsWithGeometryIterator(reverse) ?: continue
            val region = renderList.region
            while (sections.hasNext()) {
                val section = region.getSection(sections.nextByteAsInt()) ?: continue
                uploaded.get(section).orEmpty()
                    .asSequence()
                    .filter { it.pass === pass }
                    .forEach { batch ->
                        groups.getOrPut(batch.renderType) { ArrayList() }
                            .add(DrawEntry(section, batch))
                    }
            }
        }
        return groups
    }

    private fun drawRenderType(
        renderType: RenderType,
        entries: List<DrawEntry>,
        modelView: Matrix4f,
        projection: Matrix4f,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        irisComposite: Boolean
    ) {
        OpenGlPostEffectExecutionBackend.withPreservedGlState {
            drawRenderTypePreserved(
                renderType,
                entries,
                modelView,
                projection,
                cameraX,
                cameraY,
                cameraZ,
                irisComposite
            )
        }
    }

    /**
     * 在调用方 GL 状态保护范围内绘制一个 Sodium 地形覆盖批次。
     *
     * @param renderType 当前覆盖 RenderType
     * @param entries 可见 section 及其 GPU 批次
     * @param modelView 当前模型视图矩阵
     * @param projection 当前投影矩阵
     * @param cameraX 相机世界 X 坐标
     * @param cameraY 相机世界 Y 坐标
     * @param cameraZ 相机世界 Z 坐标
     * @param irisComposite 是否在 Iris 最终合成结果上绘制
     */
    private fun drawRenderTypePreserved(
        renderType: RenderType,
        entries: List<DrawEntry>,
        modelView: Matrix4f,
        projection: Matrix4f,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        irisComposite: Boolean
    ) {
        val drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4)
        glGetIntegerv(GL_VIEWPORT, viewport)
        val previousDepthFunc = glGetInteger(GL_DEPTH_FUNC)
        val previousDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK)
        val depthTestEnabled = glIsEnabled(GL_DEPTH_TEST)
        var setupCompleted = false
        try {
            renderType.setupRenderState()
            setupCompleted = true
            restoreTarget(drawFramebuffer, readFramebuffer, viewport)
            if (irisComposite) {
                RenderSystem.enableDepthTest()
                RenderSystem.depthMask(false)
                RenderSystem.depthFunc(GL_LEQUAL)
            }
            val shader = RenderSystem.getShader() ?: return
            entries.filterNot { it.batch.vertexBuffer.isInvalid() }.forEach { entry ->
                entry.batch.sprites.forEach(SpriteUtil::markSpriteActive)
                resortTranslucent(entry, cameraX, cameraY, cameraZ)
                shader.getUniform("ChunkOffset")?.set(
                    (entry.section.originX - cameraX).toFloat(),
                    (entry.section.originY - cameraY).toFloat(),
                    (entry.section.originZ - cameraZ).toFloat()
                )
                entry.batch.vertexBuffer.bind()
                entry.batch.vertexBuffer.drawWithShader(modelView, projection, shader)
            }
        } finally {
            try {
                VertexBuffer.unbind()
                if (setupCompleted) {
                    renderType.clearRenderState()
                }
            } finally {
                RenderSystem.depthFunc(previousDepthFunc)
                RenderSystem.depthMask(previousDepthMask)
                if (depthTestEnabled) {
                    RenderSystem.enableDepthTest()
                } else {
                    RenderSystem.disableDepthTest()
                }
                restoreTarget(drawFramebuffer, readFramebuffer, viewport)
            }
        }
    }

    /**
     * 半透明批次的「绘制前重排序」。
     *
     * ★★ 1.20.1 里这个函数是**空实现** —— 这是与上游 1.21 最本质的架构差异之一：
     *
     *   1.21：排序信息（SortState）在上传后才生成，因此每帧绘制前还要调
     *         sortState.buildSortedIndexBuffer(buf, sorting) 重新算一次索引，
     *         再 uploadIndexBuffer 覆盖到 GPU。→ 需要 resortTranslucent。
     *
     *   1.20.1：排序在 BufferBuilder.end() **之前** 由 setQuadSorting() 设定，
     *         结果直接烘焙进 RenderedBuffer 的索引缓冲。→ **一次排序，永久生效**，
     *         绘制前无需任何重排动作。所以本函数保留签名但什么都不做。
     *
     *   ⚠️ 副作用：相机移动时半透明叠加顺序**不会**实时更新（1.21 会）。
     *      对地形特效（生命周期 2~6 秒、区域固定）来说完全可接受。
     */
    private fun resortTranslucent(entry: DrawEntry, cameraX: Double, cameraY: Double, cameraZ: Double) {

    }

    private fun drawSafely(
        renderType: RenderType,
        entries: List<DrawEntry>,
        modelView: Matrix4f,
        projection: Matrix4f,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        irisComposite: Boolean
    ): Boolean {
        return try {
            drawRenderType(
                renderType,
                entries,
                modelView,
                projection,
                cameraX,
                cameraY,
                cameraZ,
                irisComposite
            )
            true
        } catch (error: RuntimeException) {
            CooTerrainPipelineManager.handleSodiumOverlayFailure("section draw", renderType, error)
            false
        }
    }

    private fun restoreTarget(drawFramebuffer: Int, readFramebuffer: Int, viewport: IntArray) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }

    private fun closeUploaded(batch: UploadedBatch) {
        removeDeferredBatch(batch)
        if (RenderSystem.isOnRenderThread()) {
            batch.vertexBuffer.close()
        } else {
            RenderSystem.recordRenderCall {
                batch.vertexBuffer.close()
            }
        }
    }

    /** 资源被替换或释放时，从 Iris 延迟队列移除仍引用旧 GPU buffer 的绘制项。 */
    private fun removeDeferredBatch(batch: UploadedBatch) {
        synchronized(deferredDraws) {
            val retainedDraws = ArrayList<DeferredDraw>(deferredDraws.size)
            deferredDraws.forEach { draw ->
                val retainedGroups = LinkedHashMap<RenderType, List<DrawEntry>>()
                draw.drawGroups.forEach { (renderType, entries) ->
                    val retainedEntries = entries.filterNot { it.batch === batch }
                    if (retainedEntries.isNotEmpty()) {
                        retainedGroups[renderType] = retainedEntries
                    }
                }
                if (retainedGroups.isNotEmpty()) {
                    retainedDraws += draw.copy(drawGroups = retainedGroups)
                }
            }
            deferredDraws.clear()
            deferredDraws.addAll(retainedDraws)
        }
    }

    private data class BatchKey(
        val renderType: RenderType,
        val pass: TerrainRenderPass
    )

    private class BuildBuffer(
        val key: BatchKey
    ) : AutoCloseable {
        /**
         * 本批次第一个方块的坐标 —— 半透明排序的参考原点。
         *
         * ★ 1.20.1 的排序在 end() 前做，那时还不知道属于哪个 section，
         *   所以用方块坐标代替 section 原点。同一批次的方块都在同一 section 内，误差可忽略。
         */
        var sortOrigin: BlockPos? = null


        val builder = BufferBuilder(RenderType.BIG_BUFFER_SIZE)
        val sprites = LinkedHashSet<TextureAtlasSprite>()

        /**
         * 根据输入和 `BuildBuffer` 当前配置创建 `build` 结果；返回对象保留本次配置的语义。
         *
         * 示例：`build()`。
         *
         * @return 根据当前输入生成的新对象或数据结果
         */
        /**
         * 结束构建并产出 {@link BufferBuilder.RenderedBuffer}。
         *
         * ★ 1.20.1 与 1.21 的差异：
         *   1.21 是 `ByteBufferBuilder` + `builder.build(): MeshData`
         *   1.20.1 是 `BufferBuilder(int)` + `builder.end(): RenderedBuffer?`
         * 两者语义相同：拿一份可上传的顶点数据快照。
         */
        fun build(): BuiltBatch? {











            val origin = sortOrigin
            if (key.pass.isSorted && origin != null) {
                val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
                builder.setQuadSorting(
                    VertexSorting.byDistance(
                        (camera.x - origin.x).toFloat(),
                        (camera.y - origin.y).toFloat(),
                        (camera.z - origin.z).toFloat()
                    )
                )
            }
            val rendered = builder.end() ?: run {
                close()
                return null
            }
            return BuiltBatch(key.renderType, key.pass, rendered, sprites.toList())
        }

        /**
         * 释放 `BuildBuffer` 在 `close` 中管理的资源；再次使用前必须重新初始化。
         *
         * 示例：`close()`。
         */
        override fun close() {


        }
    }

    private class BuildCollector : AutoCloseable {
        private val buffers = LinkedHashMap<BatchKey, BuildBuffer>()

        /**
         * 执行 `BuildCollector` 定义的 `append` 操作；输入和返回值用于该组件当前的渲染职责。
         *
         * 示例：`append(renderType = renderType, pass = pass, sprite = sprite, append = append)`。
         *
         * @param renderType 当前操作需要的输入值；其语义由方法名和所属组件共同限定
         *
         * @param pass 当前操作需要的输入值；其语义由方法名和所属组件共同限定
         *
         * @param sprite 当前操作需要的输入值；其语义由方法名和所属组件共同限定
         *
         * @param append 当前操作需要的输入值；其语义由方法名和所属组件共同限定
         */
        fun append(
            renderType: RenderType,
            pass: TerrainRenderPass,
            sprite: TextureAtlasSprite?,
            blockPos: BlockPos?,
            append: (BufferBuilder) -> Unit
        ) {
            val key = BatchKey(renderType, pass)
            val buffer = buffers.getOrPut(key) { BuildBuffer(key) }
            sprite?.let(buffer.sprites::add)


            blockPos?.let { buffer.sortOrigin = it }
            append(buffer.builder)
        }

        /**
         * 根据输入和 `BuildCollector` 当前配置创建 `build` 结果；返回对象保留本次配置的语义。
         *
         * 示例：`build()`。
         *
         * @return 根据当前输入生成的新对象或数据结果
         */
        fun build(): List<BuiltBatch> {
            val built = ArrayList<BuiltBatch>(buffers.size)
            return try {
                buffers.values.mapNotNullTo(built, BuildBuffer::build)
                buffers.clear()
                built
            } catch (error: RuntimeException) {
                built.forEach(BuiltBatch::close)
                close()
                throw error
            }
        }

        /**
         * 执行 `BuildCollector` 的 `renderPasses` 渲染操作，处理传入数据并更新当前帧或 GPU 状态。
         *
         * 示例：`renderPasses()`。
         *
         * @return 当前操作计算、更新或查询得到的结果
         */
        fun renderPasses(): Set<TerrainRenderPass> {
            return buffers.keys.mapTo(LinkedHashSet()) { it.pass }
        }

        /**
         * 释放 `BuildCollector` 在 `close` 中管理的资源；再次使用前必须重新初始化。
         *
         * 示例：`close()`。
         */
        override fun close() {
            buffers.values.forEach(BuildBuffer::close)
            buffers.clear()
        }
    }

    private class BuiltBatch(
        val renderType: RenderType,
        val pass: TerrainRenderPass,
        /**
         * ★ 1.20.1：顶点数据的可上传快照。
         * 上游 1.21 用的是 MeshData（带 sortQuads），1.20.1 只有 RenderedBuffer，
         * 排序改由 BufferBuilder.setQuadSorting() 在 end() **之前**完成。
         */
        val rendered: BufferBuilder.RenderedBuffer,
        val sprites: List<TextureAtlasSprite>
    ) : AutoCloseable {
        override fun close() {

            rendered.release()
        }
    }

    private data class UploadedBatch(
        val renderType: RenderType,
        val pass: TerrainRenderPass,
        val vertexBuffer: VertexBuffer,
        val sprites: List<TextureAtlasSprite>


    )

    private data class DrawEntry(
        val section: RenderSection,
        val batch: UploadedBatch
    )

    private data class DeferredDraw(
        val drawGroups: Map<RenderType, List<DrawEntry>>,
        val modelView: Matrix4f,
        val projection: Matrix4f,
        val cameraX: Double,
        val cameraY: Double,
        val cameraZ: Double
    )

}