package cn.coostack.cooparticlesapi.compat.iris

/**
 * Iris 渲染状态快照。
 *
 * 上游缺失文件，依据引用点反推补写。
 *
 * 调用点：
 * `IrisCompat.kt`：
 *   `state.terrainDepthTextureId() / terrainDepthWidth() / terrainDepthHeight()`
 *   `state.sceneDepthTextureId() / sceneDepthWidth() / sceneDepthHeight()`
 *   `state.noHandDepthTextureId() / noHandDepthWidth() / noHandDepthHeight()`
 *   `state.finalColorTextureId() / finalColorWidth() / finalColorHeight()`
 *
 * `ClientRenderPipelineManager.kt`：
 *   `CooIrisRenderState.clear()`
 *   `CooIrisRenderState.clearFinalColor()`
 *   `it.finalColorTextureId() > 0 && it.finalColorFramebufferId() > 0`
 *
 * ⚠ 保守降级实现：始终返回「无纹理」（id = 0），
 * 调用方都有 `takeIf { id > 0 }` 保护，行为等价于「Iris 兼容未启用」，不会崩溃。
 */
object CooIrisRenderState {

    /** 不可变快照 */
    class Snapshot internal constructor(
        private val terrainDepth: TextureRef,
        private val sceneDepth: TextureRef,
        private val noHandDepth: TextureRef,
        private val finalColor: TextureRef,
        private val finalColorFbo: Int,
    ) {
        fun terrainDepthTextureId(): Int = terrainDepth.id
        fun terrainDepthWidth(): Int = terrainDepth.width
        fun terrainDepthHeight(): Int = terrainDepth.height

        fun sceneDepthTextureId(): Int = sceneDepth.id
        fun sceneDepthWidth(): Int = sceneDepth.width
        fun sceneDepthHeight(): Int = sceneDepth.height

        fun noHandDepthTextureId(): Int = noHandDepth.id
        fun noHandDepthWidth(): Int = noHandDepth.width
        fun noHandDepthHeight(): Int = noHandDepth.height

        fun finalColorTextureId(): Int = finalColor.id
        fun finalColorWidth(): Int = finalColor.width
        fun finalColorHeight(): Int = finalColor.height

        /** final pass 的 framebuffer 对象 id */
        fun finalColorFramebufferId(): Int = finalColorFbo
    }

    /** 一个纹理引用（0 表示无效） */
    data class TextureRef(val id: Int, val width: Int, val height: Int) {
        companion object {
            val NONE = TextureRef(0, 0, 0)
        }
    }

    private val EMPTY = Snapshot(
        TextureRef.NONE, TextureRef.NONE, TextureRef.NONE, TextureRef.NONE, 0,
    )

    @Volatile
    private var current: Snapshot = EMPTY

    @JvmStatic
    fun snapshot(): Snapshot = current

    /** 记录当前阶段探测到的纹理 */
    @JvmStatic
    fun update(
        terrainDepth: TextureRef = TextureRef.NONE,
        sceneDepth: TextureRef = TextureRef.NONE,
        noHandDepth: TextureRef = TextureRef.NONE,
        finalColor: TextureRef = TextureRef.NONE,
        finalColorFbo: Int = 0,
    ) {
        current = Snapshot(terrainDepth, sceneDepth, noHandDepth, finalColor, finalColorFbo)
    }

    /**
     * 记录 Iris final pass 的颜色输出（由 mixin 在渲染后调用）。
     *
     * 引用：`IrisRenderingPipelineMixin.java:89`
     *   `CooIrisRenderState.captureFinalColor(texId, fboId, width, height)`
     */
    @JvmStatic
    fun captureFinalColor(textureId: Int, framebufferId: Int, width: Int, height: Int) {
        val s = current
        current = Snapshot(
            TextureRef(s.terrainDepthTextureId(), s.terrainDepthWidth(), s.terrainDepthHeight()),
            TextureRef(s.sceneDepthTextureId(), s.sceneDepthWidth(), s.sceneDepthHeight()),
            TextureRef(s.noHandDepthTextureId(), s.noHandDepthWidth(), s.noHandDepthHeight()),
            TextureRef(textureId, width, height),
            framebufferId,
        )
    }

    /**
     * 记录 terrain 深度纹理。
     */
    @JvmStatic
    fun captureTerrainDepth(textureId: Int, width: Int, height: Int) {
        val s = current
        current = Snapshot(
            TextureRef(textureId, width, height),
            TextureRef(s.sceneDepthTextureId(), s.sceneDepthWidth(), s.sceneDepthHeight()),
            TextureRef(s.noHandDepthTextureId(), s.noHandDepthWidth(), s.noHandDepthHeight()),
            TextureRef(s.finalColorTextureId(), s.finalColorWidth(), s.finalColorHeight()),
            s.finalColorFramebufferId(),
        )
    }

    /**
     * 记录场景深度纹理。
     */
    @JvmStatic
    fun captureSceneDepth(textureId: Int, width: Int, height: Int) {
        val s = current
        current = Snapshot(
            TextureRef(s.terrainDepthTextureId(), s.terrainDepthWidth(), s.terrainDepthHeight()),
            TextureRef(textureId, width, height),
            TextureRef(s.noHandDepthTextureId(), s.noHandDepthWidth(), s.noHandDepthHeight()),
            TextureRef(s.finalColorTextureId(), s.finalColorWidth(), s.finalColorHeight()),
            s.finalColorFramebufferId(),
        )
    }

    /** 清空全部状态 */
    @JvmStatic
    fun clear() {
        current = EMPTY
    }

    /** 只清 final color（final pass 结束后调用） */
    @JvmStatic
    fun clearFinalColor() {
        val s = current
        current = Snapshot(
            TextureRef(s.terrainDepthTextureId(), s.terrainDepthWidth(), s.terrainDepthHeight()),
            TextureRef(s.sceneDepthTextureId(), s.sceneDepthWidth(), s.sceneDepthHeight()),
            TextureRef(s.noHandDepthTextureId(), s.noHandDepthWidth(), s.noHandDepthHeight()),
            TextureRef.NONE,
            0,
        )
    }
}
