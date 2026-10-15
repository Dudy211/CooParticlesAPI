package cn.coostack.cooparticlesapi.cparticle.render

import cn.coostack.cooparticlesapi.CooParticlesAPIClient
import cn.coostack.cooparticlesapi.compat.IrisCompat
import cn.coostack.cooparticlesapi.cparticle.CParticleAppearanceDescriptors
import cn.coostack.cooparticlesapi.cparticle.CParticleColorCurve
import cn.coostack.cooparticlesapi.cparticle.CParticleCurve
import cn.coostack.cooparticlesapi.cparticle.CParticleIndexedBlendState
import cn.coostack.cooparticlesapi.cparticle.CParticleRenderLayer
import cn.coostack.cooparticlesapi.cparticle.CParticleRenderPass
import cn.coostack.cooparticlesapi.cparticle.CParticleSprites
import cn.coostack.cooparticlesapi.cparticle.CParticleSystem
import cn.coostack.cooparticlesapi.cparticle.CParticleSystemManager
import cn.coostack.cooparticlesapi.cparticle.CParticleTextureBindingKey
import cn.coostack.cooparticlesapi.cparticle.CParticleTextureResolver
import cn.coostack.cooparticlesapi.renderer.post.OpenGlPostEffectExecutionBackend
import cn.coostack.cooparticlesapi.renderer.shader.AdvancedShaderProgramBuilder
import cn.coostack.cooparticlesapi.renderer.shader.ShaderProgramRegistry
import cn.coostack.cooparticlesapi.renderer.shader.api.CooShaderProgram
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33.*

/**
 * GPU 粒子渲染器: 每个系统一次 instanced draw，同层系统按基础纹理和蒙版纹理连续绘制。
 *
 * 无光影时直接使用 CParticle shader。Iris 光影启用后，常规层展开为原版粒子顶点，
 * 需要完整片元 Alpha 的有界 Screen 层只写 Iris framebuffer 的主颜色附件。
 * screen-only Terrain Mapping 活跃时，CParticle 延迟到 Mapping 最终合成后按原 layer 语义重放；
 * Iris shader pack 激活时不参与 Terrain Mapping 分流，直接保留 Iris 原生粒子阶段。
 */
object CParticleRenderer {

    private var program: CooShaderProgram? = null
    private val emptyCurve = FloatArray(16)
    private val emptyCurveHandles = FloatArray(CParticleCurve.MAX_KEYS * 4)
    private val emptyColorTimes = FloatArray(CParticleColorCurve.MAX_KEYS)
    private val emptyColorValues = FloatArray(CParticleColorCurve.MAX_KEYS * 3)
    private val emptyColorHandles = FloatArray(CParticleColorCurve.MAX_KEYS * 4)
    private val drawLayers = arrayOf(
        CParticleRenderLayer.OPAQUE,
        CParticleRenderLayer.TRANSLUCENT,
        CParticleRenderLayer.PARTICLE_SHEET_TRANSLUCENT_NO_DEPTH_WRITE,
        CParticleRenderLayer.ADDITION_BLEND_TRANSLUCENT_NOT_HDR,
        CParticleRenderLayer.ADDITION_BLEND_TRANSLUCENT_NOT_HDR_NO_DEPTH_WRITE,
        CParticleRenderLayer.ADDITION_BLEND_NOT_HDR,
        CParticleRenderLayer.ADDITION_BLEND_NOT_HDR_NO_DEPTH_WRITE,
        CParticleRenderLayer.ADDITION_BLEND_TRANSLUCENT,
        CParticleRenderLayer.ADDITION_BLEND,
        CParticleRenderLayer.ADDITION_BLEND_TRANSLUCENT_NO_DEPTH_WRITE,
    )

    /**
     * 把 CParticle 图形 program 加入统一注册表，不触发 GL 编译。
     *
     * 示例：[CooParticlesAPIClient.init] 在 loader 客户端注册阶段调用。
     * 禁止：本方法不能调用 `init()`，否则早期客户端初始化可能还没有 GL 上下文。
     *
     * @return 已注册的共享图形 program
     */
    internal fun registerProgram(): CooShaderProgram {
        program?.let { return ShaderProgramRegistry.register(it) }
        return AdvancedShaderProgramBuilder()
            .vertex("core/vertex/cparticle.vsh")
            .fragment("core/fragment/cparticle.fsh")
            .attributeLocation("iPosAge", 0)
            .attributeLocation("iPrevMaxAge", 1)
            .attributeLocation("iVelFlags", 2)
            .attributeLocation("iSizeRot", 3)
            .attributeLocation("iAxisRoll", 4)
            .attributeLocation("iAnimation", 5)
            .attributeLocation("iColor", 6)
            .attributeLocation("iAngularEpoch", 7)
            .attributeLocation("iAppearance", 8)
            .transformFeedbackVaryings("tfPosition", "tfUv", "tfPacked")
            .managedId("cparticle/render")
            .build()
            .also { program = it }
    }

    /**
     * 返回可绘制的 CParticle program，遗漏客户端预注册时仍保留兼容兜底。
     *
     * 示例：正常路径直接返回渲染初始化阶段已经编译的 program。
     * 禁止：只能在持有 GL 上下文的渲染线程调用。
     *
     * @return 已完成编译的共享图形 program
     */
    private fun ensureProgram(): CooShaderProgram {
        return registerProgram().also { current ->
            if (current.program == 0) current.init()
        }
    }

    /**
     * 绘制所有系统 (渲染线程, 世界渲染阶段).
     *
     * 示例：粒子引擎 pass 通过 [CParticleSystemManager.renderParticlePass] 调用此方法。
     * 禁止：不要从非渲染线程调用，也不要绕过调用方提供的粒子 pass。
     *
     * @param view LevelRenderer 的 frustum(model-view) 矩阵
     * @param proj 当前世界投影矩阵
     * @param camera 当前渲染相机
     * @param partial tick 插值
     * @param pass 本次允许绘制的粒子覆盖范围
     */
    fun render(
        systems: Collection<CParticleSystem>,
        view: Matrix4f,
        proj: Matrix4f,
        camera: Camera,
        partial: Float,
        pass: CParticleRenderPass = CParticleRenderPass.ALL,
    ) {
        renderInternal(systems, view, proj, camera, partial, pass, coverageOnly = false, forceDirectShader = false)
    }

    /** 在 Terrain Mapping 之后使用 Coo shader 和原 layer 状态重放完整 CParticle 前景。 */
    internal fun renderTerrainForeground(
        systems: Collection<CParticleSystem>,
        view: Matrix4f,
        proj: Matrix4f,
        camera: Camera,
        partial: Float,
    ) {
        renderInternal(
            systems,
            view,
            proj,
            camera,
            partial,
            CParticleRenderPass.ALL,
            coverageOnly = false,
            forceDirectShader = true,
        )
    }

    /** 在 Terrain Mapping 执行前按当前帧投影生成全部可见 CParticle 的隔离覆盖蒙版。 */
    internal fun renderTerrainCoverageMask(
        systems: Collection<CParticleSystem>,
        view: Matrix4f,
        proj: Matrix4f,
        camera: Camera,
        partial: Float,
    ): Boolean {
        return renderInternal(
            systems,
            view,
            proj,
            camera,
            partial,
            CParticleRenderPass.ALL,
            coverageOnly = true,
            forceDirectShader = true,
        )
    }

    private fun renderInternal(
        systems: Collection<CParticleSystem>,
        view: Matrix4f,
        proj: Matrix4f,
        camera: Camera,
        partial: Float,
        pass: CParticleRenderPass,
        coverageOnly: Boolean,
        forceDirectShader: Boolean,
    ): Boolean {
        if (systems.isEmpty() || pass == CParticleRenderPass.NONE) return coverageOnly
        val cameraPos = camera.position

        var dropPass = 0
        var dropReleased = 0
        var dropEmpty = 0
        var dropInvisible = 0
        val visibleSystems = systems.filter {
            if (!pass.accepts(it.layer)) { dropPass++; return@filter false }
            if (it.released) { dropReleased++; return@filter false }
            if (it.store.highWater <= 0) { dropEmpty++; return@filter false }
            if (!it.isVisible(cameraPos)) { dropInvisible++; return@filter false }
            true
        }
        cn.coostack.cooparticlesapi.dbg.CpDbg.dEvery("draw/vis", 5, 40) {
            "in=${systems.size} → visible=${visibleSystems.size} | pass=$pass coverageOnly=$coverageOnly " +
                "| drop(noPass=$dropPass released=$dropReleased empty=$dropEmpty invisible=$dropInvisible)"
        }
        if (visibleSystems.isEmpty()) return coverageOnly

        val shader = ensureProgram()

        cn.coostack.cooparticlesapi.dbg.CpDbg.dEvery("draw/shader", 10, 30) {
            "program=${shader.program} forceDirect=$forceDirectShader"
        }
        if (shader.program == 0) return false
        val frameId = CParticleSystemManager.currentRenderFrameId
        val irisShaderPackActive = CooParticlesAPIClient.checkIrisShaderPackUsed()
        val useIrisParticleShader = irisShaderPackActive && !coverageOnly && !forceDirectShader
        val uniformScratch = UniformScratch()


        val prevProgram = glGetInteger(GL_CURRENT_PROGRAM)
        val prevShaderTexture0 = RenderSystem.getShaderTexture(0)
        val prevActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE)
        RenderSystem.activeTexture(GL_TEXTURE0)
        val prevTex0 = glGetInteger(GL_TEXTURE_BINDING_2D)
        RenderSystem.activeTexture(GL_TEXTURE1)
        val prevTex1 = glGetInteger(GL_TEXTURE_BINDING_2D)
        RenderSystem.activeTexture(GL_TEXTURE2)
        val prevTex2Buffer = glGetInteger(GL_TEXTURE_BINDING_BUFFER)
        RenderSystem.activeTexture(GL_TEXTURE3)
        val prevTex3Buffer = glGetInteger(GL_TEXTURE_BINDING_BUFFER)
        RenderSystem.activeTexture(GL_TEXTURE4)
        val prevTex4 = glGetInteger(GL_TEXTURE_BINDING_2D)
        RenderSystem.activeTexture(prevActiveTexture)
        val blendEnabled = glIsEnabled(GL_BLEND)
        val blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB)
        val blendDstRgb = glGetInteger(GL_BLEND_DST_RGB)
        val blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA)
        val blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA)
        val blendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB)
        val blendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA)
        val indexedBlendStateAvailable = irisShaderPackActive && CParticleIndexedBlendState.isAvailable()
        val indexedBlendStateCount = if (indexedBlendStateAvailable) glGetInteger(GL_MAX_DRAW_BUFFERS) else 0
        val indexedBlendEnabled = BooleanArray(indexedBlendStateCount) { GL30.glIsEnabledi(GL_BLEND, it) }
        val indexedBlendSrcRgb = IntArray(indexedBlendStateCount) { GL30.glGetIntegeri(GL_BLEND_SRC_RGB, it) }
        val indexedBlendDstRgb = IntArray(indexedBlendStateCount) { GL30.glGetIntegeri(GL_BLEND_DST_RGB, it) }
        val indexedBlendSrcAlpha = IntArray(indexedBlendStateCount) { GL30.glGetIntegeri(GL_BLEND_SRC_ALPHA, it) }
        val indexedBlendDstAlpha = IntArray(indexedBlendStateCount) { GL30.glGetIntegeri(GL_BLEND_DST_ALPHA, it) }
        val indexedBlendEquationRgb = IntArray(indexedBlendStateCount) {
            GL30.glGetIntegeri(GL_BLEND_EQUATION_RGB, it)
        }
        val indexedBlendEquationAlpha = IntArray(indexedBlendStateCount) {
            GL30.glGetIntegeri(GL_BLEND_EQUATION_ALPHA, it)
        }
        val depthEnabled = glIsEnabled(GL_DEPTH_TEST)
        val depthMask = glGetBoolean(GL_DEPTH_WRITEMASK)
        val depthFunc = glGetInteger(GL_DEPTH_FUNC)
        val colorMask = IntArray(4)
        glGetIntegeri_v(GL_COLOR_WRITEMASK, 0, colorMask)
        val cullEnabled = glIsEnabled(GL_CULL_FACE)
        val cullFaceMode = glGetInteger(GL_CULL_FACE_MODE)
        val frontFaceMode = glGetInteger(GL_FRONT_FACE)
        val lightTexture = Minecraft.getInstance().gameRenderer.lightTexture()
        val lightmapWasEnabled = RenderSystem.getShaderTexture(2) != 0

        try {

            if (!lightmapWasEnabled) {
                RenderSystem.activeTexture(GL_TEXTURE0)
                lightTexture.turnOnLightLayer()
            }
            val lightmapId = RenderSystem.getShaderTexture(2)
            RenderSystem.activeTexture(GL_TEXTURE1)
            RenderSystem.bindTexture(lightmapId)

            for (system in visibleSystems) {
                system.ensureGl()
                system.prepareDynamicVisuals(frameId)
            }
            CParticleSprites.bindLookup(2)
            CParticleAppearanceDescriptors.bindLookup(3)

            glUseProgram(shader.program)
            shader.setMatrix4("uView", view)
            shader.setMatrix4("uProj", proj)
            shader.setFloat("uPartial", partial)
            shader.setInt("uMainTexture", 0)
            shader.setInt("uLightmap", 1)
            shader.setInt("uAnimationLookup", 2)
            shader.setInt("uAppearanceLookup", 3)
            shader.setInt("uMaskTexture", 4)
            shader.setInt("uDepthOnly", 0)
            shader.setInt("uCoverageMask", 0)
            shader.setInt("uPremultiplyRgbByAlpha", 0)




















            val left = camera.leftVector
            val up = camera.upVector
            shader.setFloat3("uCamLeft", Vector3f(left.x(), left.y(), left.z()))
            shader.setFloat3("uCamUp", Vector3f(up.x(), up.y(), up.z()))


            val fogColor = RenderSystem.getShaderFogColor()
            shader.setFloat("uFogStart", RenderSystem.getShaderFogStart())
            shader.setFloat("uFogEnd", RenderSystem.getShaderFogEnd())
            shader.setFloat4(
                "uFogColor",
                Vector4f(fogColor[0], fogColor[1], fogColor[2], fogColor[3]),
            )
            shader.setInt("uFogShape", RenderSystem.getShaderFogShape().index)






            val diag = cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag
            if (diag.disableDepth) glDisable(GL_DEPTH_TEST) else {
                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_LEQUAL)
            }
            if (diag.disableCull) glDisable(GL_CULL_FACE) else {
                glEnable(GL_CULL_FACE)
                glCullFace(GL_BACK)
                glFrontFace(GL_CCW)
            }

            if (coverageOnly) {
                return renderCoverage(shader, visibleSystems, cameraPos, partial, uniformScratch)
            }
            if (useIrisParticleShader) {
                renderWithIrisParticleShader(
                    shader,
                    visibleSystems,
                    view,
                    proj,
                    cameraPos,
                    partial,
                    pass,
                    uniformScratch,
                )
            } else {
                shader.setInt("uIrisExpansion", 0)

                val deferredDepthSystems = ArrayList<CParticleSystem>()
                for (layer in drawLayers) {
                    if (!pass.accepts(layer)) continue
                    val layerSystems = visibleSystems.asSequence()
                        .filter { it.layer == layer }
                        .sortedWith(compareBy(CParticleSystem::textureBindingKey)
                            .thenBy(CParticleSystem::maskTextureBindingKey))
                        .toList()

                    cn.coostack.cooparticlesapi.dbg.CpDbg.dEvery("draw/batch", 20, 40) {
                        "layer=$layer n=${layerSystems.size}"
                    }
                    if (layerSystems.isEmpty()) continue
                    if (forceDirectShader && irisShaderPackActive) {
                        layer.applyIndexedState(0)
                    } else {
                        layer.applyState()
                    }
                    shader.setInt("uPremultiplyRgbByAlpha", if (layer.premultiplyRgbByAlpha) 1 else 0)
                    if (layer.requiresDeferredDepthWrite) {
                        glDepthMask(false)
                        deferredDepthSystems.addAll(layerSystems)
                    }
                    val drawLayerSystems = {
                        var boundMainTexture: CParticleTextureBindingKey? = null
                        var boundMaskTexture: CParticleTextureBindingKey? = null
                        for (system in layerSystems) {
                            if (system.textureBindingKey != boundMainTexture) {
                                RenderSystem.activeTexture(GL_TEXTURE0)
                                RenderSystem.bindTexture(CParticleTextureResolver.textureId(system.textureBindingKey))
                                boundMainTexture = system.textureBindingKey
                            }
                            val maskBinding = system.maskTextureBindingKey
                            if (maskBinding != null && maskBinding != boundMaskTexture) {
                                RenderSystem.activeTexture(GL_TEXTURE4)
                                RenderSystem.bindTexture(CParticleTextureResolver.textureId(maskBinding))
                                boundMaskTexture = maskBinding
                            }

                            applySystemUniforms(shader, system, cameraPos, partial, uniformScratch)

                            cn.coostack.cooparticlesapi.dbg.CpDbg.dEvery("draw/exec", 10, 60) {
                                "layer=${system.layer} highWater=${system.store.highWater} " +
                                    "alive=${system.store.aliveCount} init=${system.glBuffer.initialized} " +
                                    "cap=${system.glBuffer.capacity} tex=${system.textureBindingKey}"
                            }


                            run {
                                val store = system.store
                                val slot = (0 until store.highWater.toInt()).firstOrNull { store.isAlive(it) }
                                if (slot != null) {
                                    val b = slot * cn.coostack.cooparticlesapi.cparticle.storage.CParticleStore.STRIDE
                                    val d = store.data
                                    val org = system.origin
                                    val rx = (d[b] + (org.x - cameraPos.x)).toFloat()
                                    val ry = (d[b + 1] + (org.y - cameraPos.y)).toFloat()
                                    val rz = (d[b + 2] + (org.z - cameraPos.z)).toFloat()

                                    val v = Vector4f(rx, ry, rz, 1f)
                                    view.transform(v)
                                    val afterView = "(${"%.3f".format(v.x)},${"%.3f".format(v.y)},${"%.3f".format(v.z)},${"%.3f".format(v.w)})"
                                    proj.transform(v)
                                    val afterProj = "(${"%.3f".format(v.x)},${"%.3f".format(v.y)},${"%.3f".format(v.z)},${"%.3f".format(v.w)})"
                                    val ndc = if (kotlin.math.abs(v.w) > 1e-6f) {
                                        "${"%.2f".format(v.x / v.w)},${"%.2f".format(v.y / v.w)},${"%.2f".format(v.z / v.w)}"
                                    } else "w≈0"

                                    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                                    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                                    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                                    var cnt = 0
                                    for (sl in 0 until store.highWater.toInt()) {
                                        if (!store.isAlive(sl)) continue
                                        val bb = sl * cn.coostack.cooparticlesapi.cparticle.storage.CParticleStore.STRIDE
                                        val px = d[bb]; val py = d[bb + 1]; val pz = d[bb + 2]
                                        if (px < minX) minX = px; if (px > maxX) maxX = px
                                        if (py < minY) minY = py; if (py > maxY) maxY = py
                                        if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
                                        cnt++
                                    }
                                    cn.coostack.cooparticlesapi.dbg.CpDbg.dL(
                                        "draw/spread",
                                        40,
                                        "sys=${system.name.take(40)} alive=$cnt " +
                                            "localX=[${"%.2f".format(minX)},${"%.2f".format(maxX)}] " +
                                            "localY=[${"%.2f".format(minY)},${"%.2f".format(maxY)}] " +
                                            "localZ=[${"%.2f".format(minZ)},${"%.2f".format(maxZ)}] " +
                                            "origin=(${"%.1f".format(org.x)},${"%.1f".format(org.y)},${"%.1f".format(org.z)}) " +
                                            "camPos=(${"%.1f".format(cameraPos.x)},${"%.1f".format(cameraPos.y)},${"%.1f".format(cameraPos.z)})"
                                    )
                                    cn.coostack.cooparticlesapi.dbg.CpDbg.dL(
                                        "draw/vertex",
                                        2,
                                        "slot=$slot rel=(${"%.2f".format(rx)},${"%.2f".format(ry)},${"%.2f".format(rz)}) " +
                                            "\n    afterView=$afterView " +
                                            "\n    afterProj=$afterProj ndc=$ndc " +
                                            "\n    viewRow0=(${"%.3f".format(view.m00())},${"%.3f".format(view.m01())},${"%.3f".format(view.m02())}) " +
                                            "viewRow1=(${"%.3f".format(view.m10())},${"%.3f".format(view.m11())},${"%.3f".format(view.m12())}) " +
                                            "viewRow2=(${"%.3f".format(view.m20())},${"%.3f".format(view.m21())},${"%.3f".format(view.m22())}) " +
                                            "proj00=${"%.3f".format(proj.m00())} proj22=${"%.3f".format(proj.m22())} proj32=${"%.3f".format(proj.m32())} " +
                                            "\n    alpha=${"%.2f".format(d[b + 27])} alive=${store.aliveCount}"
                                    )
                                }
                            }

                            run {
                                val pid = glGetInteger(GL_CURRENT_PROGRAM)
                                val uViewLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(pid, "uView")
                                val uProjLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(pid, "uProj")
                                val uOriginLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(pid, "uOriginRelCam")
                                val gm = FloatArray(16)
                                if (uViewLoc >= 0) org.lwjgl.opengl.GL20.glGetUniformfv(pid, uViewLoc, gm)
                                val gp = FloatArray(16)
                                if (uProjLoc >= 0) org.lwjgl.opengl.GL20.glGetUniformfv(pid, uProjLoc, gp)
                                val go = FloatArray(3)
                                if (uOriginLoc >= 0) org.lwjgl.opengl.GL20.glGetUniformfv(pid, uOriginLoc, go)
                                val uGroupLoc = org.lwjgl.opengl.GL20.glGetUniformLocation(pid, "uGroupMat")
                                val gq = FloatArray(16)
                                if (uGroupLoc >= 0) org.lwjgl.opengl.GL20.glGetUniformfv(pid, uGroupLoc, gq)
                                if (uGroupLoc < 0) gq.fill(0f)
                                cn.coostack.cooparticlesapi.dbg.CpDbg.dL(
                                    "gpu/uniform",
                                    6,
                                    "pid=$pid locs(view=$uViewLoc proj=$uProjLoc origin=$uOriginLoc) " +
                                        "\n    GPU_uView[0..3]=(${"%.3f".format(gm[0])},${"%.3f".format(gm[1])},${"%.3f".format(gm[2])},${"%.3f".format(gm[3])}) " +
                                        "[4..7]=(${"%.3f".format(gm[4])},${"%.3f".format(gm[5])},${"%.3f".format(gm[6])},${"%.3f".format(gm[7])}) " +
                                        "\n    GPU_uProj[0..3]=(${"%.3f".format(gp[0])},${"%.3f".format(gp[1])},${"%.3f".format(gp[2])},${"%.3f".format(gp[3])}) " +
                                        "[5]=${"%.3f".format(gp[5])} [10]=${"%.3f".format(gp[10])} [11]=${"%.3f".format(gp[11])} " +
                                        "\n    GPU_uOrigin=(${"%.2f".format(go[0])},${"%.2f".format(go[1])},${"%.2f".format(go[2])}) " +
                                        "\n    uGroupMat=(" + (0..15).joinToString(",") { "${"%.2f".format(gq[it])}" } + ")" +
                                        "CPU_view[0..3]=(${"%.3f".format(view.m00())},${"%.3f".format(view.m01())},${"%.3f".format(view.m02())},${"%.3f".format(view.m03())})"
                                )
                            }
                            system.glBuffer.draw(system.store.highWater)
                        }
                    }
                    if (forceDirectShader && irisShaderPackActive) {
                        withPrimaryColorWriteOnly(drawLayerSystems)
                    } else {
                        drawLayerSystems()
                    }
                }

                if (deferredDepthSystems.isNotEmpty()) {
                    shader.setInt("uDepthOnly", 1)
                    glColorMaski(0, false, false, false, false)
                    glDepthMask(true)
                    var boundMainTexture: CParticleTextureBindingKey? = null
                    var boundMaskTexture: CParticleTextureBindingKey? = null
                    for (system in deferredDepthSystems.sortedWith(
                        compareBy(CParticleSystem::textureBindingKey)
                            .thenBy(CParticleSystem::maskTextureBindingKey)
                    )) {
                        if (system.textureBindingKey != boundMainTexture) {
                            RenderSystem.activeTexture(GL_TEXTURE0)
                            RenderSystem.bindTexture(CParticleTextureResolver.textureId(system.textureBindingKey))
                            boundMainTexture = system.textureBindingKey
                        }
                        val maskBinding = system.maskTextureBindingKey
                        if (maskBinding != null && maskBinding != boundMaskTexture) {
                            RenderSystem.activeTexture(GL_TEXTURE4)
                            RenderSystem.bindTexture(CParticleTextureResolver.textureId(maskBinding))
                            boundMaskTexture = maskBinding
                        }
                        applySystemUniforms(shader, system, cameraPos, partial, uniformScratch)
                        system.glBuffer.draw(system.store.highWater)
                    }
                }
            }
        } finally {

            if (!lightmapWasEnabled) lightTexture.turnOffLightLayer()
            RenderSystem.activeTexture(GL_TEXTURE4)
            RenderSystem.bindTexture(prevTex4)
            RenderSystem.activeTexture(GL_TEXTURE3)
            glBindTexture(GL_TEXTURE_BUFFER, prevTex3Buffer)
            RenderSystem.activeTexture(GL_TEXTURE2)
            glBindTexture(GL_TEXTURE_BUFFER, prevTex2Buffer)
            RenderSystem.activeTexture(GL_TEXTURE1)
            RenderSystem.bindTexture(prevTex1)
            RenderSystem.activeTexture(GL_TEXTURE0)
            RenderSystem.bindTexture(prevTex0)
            RenderSystem.setShaderTexture(0, prevShaderTexture0)
            RenderSystem.activeTexture(prevActiveTexture)
            if (blendEnabled) glEnable(GL_BLEND) else glDisable(GL_BLEND)
            glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
            if (indexedBlendStateAvailable) {
                repeat(indexedBlendStateCount) { drawBuffer ->
                    if (indexedBlendEnabled[drawBuffer]) {
                        glEnablei(GL_BLEND, drawBuffer)
                    } else {
                        glDisablei(GL_BLEND, drawBuffer)
                    }
                    CParticleIndexedBlendState.setFactors(
                        drawBuffer,
                        indexedBlendSrcRgb[drawBuffer],
                        indexedBlendDstRgb[drawBuffer],
                        indexedBlendSrcAlpha[drawBuffer],
                        indexedBlendDstAlpha[drawBuffer],
                    )
                    CParticleIndexedBlendState.setEquation(
                        drawBuffer,
                        indexedBlendEquationRgb[drawBuffer],
                        indexedBlendEquationAlpha[drawBuffer],
                    )
                }
            }
            if (depthEnabled) glEnable(GL_DEPTH_TEST) else glDisable(GL_DEPTH_TEST)
            glDepthMask(depthMask)
            glDepthFunc(depthFunc)
            glColorMaski(
                0,
                colorMask[0] != 0,
                colorMask[1] != 0,
                colorMask[2] != 0,
                colorMask[3] != 0,
            )
            glCullFace(cullFaceMode)
            glFrontFace(frontFaceMode)
            if (cullEnabled) glEnable(GL_CULL_FACE) else glDisable(GL_CULL_FACE)
            if (prevProgram > 0 && glIsProgram(prevProgram)) glUseProgram(prevProgram) else glUseProgram(0)
        }
        return true
    }

    /** 在独立 framebuffer 中把全部可见 CParticle 光栅化为颜色与覆盖度，不写回场景深度。 */
    private fun renderCoverage(
        shader: CooShaderProgram,
        systems: List<CParticleSystem>,
        cameraPos: Vec3,
        partial: Float,
        uniformScratch: UniformScratch,
    ): Boolean {
        if (systems.isEmpty()) return true
        return OpenGlPostEffectExecutionBackend.captureCParticleCoverage {
            glUseProgram(shader.program)
            shader.setInt("uIrisExpansion", 0)
            shader.setInt("uDepthOnly", 0)
            shader.setInt("uCoverageMask", 1)
            shader.setInt("uPremultiplyRgbByAlpha", 0)
            if (CooParticlesAPIClient.checkIrisShaderPackUsed() && CParticleIndexedBlendState.isAvailable()) {

                GL30.glEnablei(GL_BLEND, 0)
                CParticleIndexedBlendState.setEquation(0, GL_MAX, GL_MAX)
                CParticleIndexedBlendState.setFactors(0, GL_ONE, GL_ONE, GL_ONE, GL_ONE)
            } else {
                glEnable(GL_BLEND)
                glBlendEquationSeparate(GL_MAX, GL_MAX)
                glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ONE, GL_ONE)
            }
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_LEQUAL)
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glFrontFace(GL_CCW)
            try {
                drawInstancedSystems(shader, systems, cameraPos, partial, uniformScratch)
            } finally {
                shader.setInt("uCoverageMask", 0)
            }
        }
    }

    /**
     * 在 Iris 粒子 framebuffer 中按渲染层选择兼容绘制路径。
     *
     * 示例：常规层使用 `PARTICLES_TRANS` 处理展开顶点；有界透明 Screen 层直接采样纹理和 mask。
     * 禁止让直接绘制写入主颜色之外的 shaderpack 附件。
     *
     * @param shader 用于 transform feedback 和有界透明 Screen 层直接绘制的 CParticle program
     * @param systems 当前 pass 中可见且已准备好的系统
     * @param view 原版粒子 model-view 矩阵
     * @param proj 当前世界投影矩阵
     * @param cameraPos 当前相机世界坐标
     * @param partial tick 插值
     * @param pass 当前 Iris 粒子分流 pass
     */
    private fun renderWithIrisParticleShader(
        shader: CooShaderProgram,
        systems: List<CParticleSystem>,
        view: Matrix4f,
        proj: Matrix4f,
        cameraPos: Vec3,
        partial: Float,
        pass: CParticleRenderPass,
        uniformScratch: UniformScratch,
    ) {
        shader.setInt("uIrisExpansion", 1)
        shader.setInt("uPremultiplyRgbByAlpha", 0)
        for (system in systems) {
            if (system.layer.premultiplyRgbByAlpha) continue
            applySystemUniforms(shader, system, cameraPos, partial, uniformScratch)
            system.glBuffer.expandForParticleShader(system.store.highWater)
        }
        shader.setInt("uIrisExpansion", 0)

        val firstTexture = systems.firstOrNull()?.textureBindingKey ?: return
        RenderSystem.setShaderTexture(0, CParticleTextureResolver.textureId(firstTexture))
        IrisCompat.runWithParticleShader(pass, view, proj) {
            val irisParticleProgram = glGetInteger(GL_CURRENT_PROGRAM)
            val deferredExpandedSystems = ArrayList<CParticleSystem>()
            val deferredDirectSystems = ArrayList<CParticleSystem>()
            for (layer in drawLayers) {
                if (!pass.accepts(layer)) continue
                val layerSystems = systems.asSequence()
                    .filter { it.layer == layer }
                    .sortedWith(compareBy(CParticleSystem::textureBindingKey)
                        .thenBy(CParticleSystem::maskTextureBindingKey))
                    .toList()
                if (layerSystems.isEmpty()) continue

                layer.applyIndexedState(0)
                if (layer.requiresDeferredDepthWrite) {
                    glDepthMask(false)
                    if (layer.premultiplyRgbByAlpha) {
                        deferredDirectSystems.addAll(layerSystems)
                    } else {
                        deferredExpandedSystems.addAll(layerSystems)
                    }
                }

                if (layer.premultiplyRgbByAlpha) {
                    glUseProgram(shader.program)
                    try {

                        RenderSystem.activeTexture(GL_TEXTURE1)
                        RenderSystem.bindTexture(RenderSystem.getShaderTexture(2))
                        CParticleSprites.bindLookup(2)
                        CParticleAppearanceDescriptors.bindLookup(3)
                        shader.setInt("uPremultiplyRgbByAlpha", 1)
                        withPrimaryColorWriteOnly {
                            drawInstancedSystems(
                                shader,
                                layerSystems,
                                cameraPos,
                                partial,
                                uniformScratch,
                            )
                        }
                    } finally {
                        shader.setInt("uPremultiplyRgbByAlpha", 0)
                        glUseProgram(irisParticleProgram)
                    }
                } else {
                    drawExpandedSystems(layerSystems)
                }
            }

            if (deferredExpandedSystems.isNotEmpty() || deferredDirectSystems.isNotEmpty()) {
                withAllColorWritesDisabled {
                    glDepthMask(true)
                    if (deferredExpandedSystems.isNotEmpty()) {
                        glUseProgram(irisParticleProgram)
                        drawExpandedSystems(deferredExpandedSystems)
                    }
                    if (deferredDirectSystems.isNotEmpty()) {
                        glUseProgram(shader.program)
                        try {
                            RenderSystem.activeTexture(GL_TEXTURE1)
                            RenderSystem.bindTexture(RenderSystem.getShaderTexture(2))
                            CParticleSprites.bindLookup(2)
                            CParticleAppearanceDescriptors.bindLookup(3)
                            shader.setInt("uDepthOnly", 1)
                            drawInstancedSystems(
                                shader,
                                deferredDirectSystems,
                                cameraPos,
                                partial,
                                uniformScratch,
                            )
                        } finally {
                            shader.setInt("uDepthOnly", 0)
                            glUseProgram(irisParticleProgram)
                        }
                    }
                }
            }
            glUseProgram(irisParticleProgram)
        }
    }

    private fun drawInstancedSystems(
        shader: CooShaderProgram,
        systems: Collection<CParticleSystem>,
        cameraPos: Vec3,
        partial: Float,
        uniformScratch: UniformScratch,
    ) {
        var boundMainTexture: CParticleTextureBindingKey? = null
        var boundMaskTexture: CParticleTextureBindingKey? = null
        for (system in systems.sortedWith(
            compareBy(CParticleSystem::textureBindingKey)
                .thenBy(CParticleSystem::maskTextureBindingKey)
        )) {
            if (system.textureBindingKey != boundMainTexture) {
                RenderSystem.activeTexture(GL_TEXTURE0)
                RenderSystem.bindTexture(CParticleTextureResolver.textureId(system.textureBindingKey))
                boundMainTexture = system.textureBindingKey
            }
            val maskBinding = system.maskTextureBindingKey
            if (maskBinding != null && maskBinding != boundMaskTexture) {
                RenderSystem.activeTexture(GL_TEXTURE4)
                RenderSystem.bindTexture(CParticleTextureResolver.textureId(maskBinding))
                boundMaskTexture = maskBinding
            }
            applySystemUniforms(shader, system, cameraPos, partial, uniformScratch)
            system.glBuffer.draw(system.store.highWater)
        }
    }

    private fun drawExpandedSystems(systems: Collection<CParticleSystem>) {
        var boundMainTexture: CParticleTextureBindingKey? = null
        for (system in systems.sortedBy(CParticleSystem::textureBindingKey)) {
            if (system.textureBindingKey != boundMainTexture) {
                val textureId = CParticleTextureResolver.textureId(system.textureBindingKey)
                RenderSystem.setShaderTexture(0, textureId)
                RenderSystem.activeTexture(GL_TEXTURE0)
                RenderSystem.bindTexture(textureId)
                boundMainTexture = system.textureBindingKey
            }
            system.glBuffer.drawExpanded(system.store.highWater)
        }
    }

    private fun withPrimaryColorWriteOnly(draw: () -> Unit) {
        withColorMasks({ drawBuffer -> drawBuffer == 0 }, draw)
    }

    private fun withAllColorWritesDisabled(draw: () -> Unit) {
        withColorMasks({ false }, draw)
    }

    private fun withColorMasks(enabled: (Int) -> Boolean, draw: () -> Unit) {
        val colorMasks = Array(glGetInteger(GL_MAX_DRAW_BUFFERS)) { drawBuffer ->
            IntArray(4).also { glGetIntegeri_v(GL_COLOR_WRITEMASK, drawBuffer, it) }
        }
        try {
            colorMasks.indices.forEach { drawBuffer ->
                if (!enabled(drawBuffer)) {
                    glColorMaski(drawBuffer, false, false, false, false)
                }
            }
            draw()
        } finally {
            colorMasks.forEachIndexed { drawBuffer, mask ->
                glColorMaski(
                    drawBuffer,
                    mask[0] != 0,
                    mask[1] != 0,
                    mask[2] != 0,
                    mask[3] != 0,
                )
            }
        }
    }

    private fun applySystemUniforms(
        shader: CooShaderProgram,
        system: CParticleSystem,
        cameraPos: Vec3,
        partial: Float,
        uniformScratch: UniformScratch,
    ) {
        shader.setFloat3(
            "uOriginRelCam", uniformScratch.origin.set(
                (system.origin.x - cameraPos.x).toFloat(),
                (system.origin.y - cameraPos.y).toFloat(),
                (system.origin.z - cameraPos.z).toFloat()
            )
        )
        shader.setMatrix4("uPrevGroupMat", system.previousGroupTransform)
        shader.setMatrix4("uGroupMat", system.currentGroupTransform)
        shader.setInt(
            "uTransformParticleGeometry",
            if (system.transformsSimulatedParticleSpace) 1 else 0,
        )

        setScalarCurve(shader, "uAlpha", system.alphaCurve)
        setScalarCurve(shader, "uScale", system.scaleCurve)
        setColorCurve(shader, "uColor", system.colorCurve)
        val systemTime = system.tickCount + partial
        shader.setFloat("uSystemTime", systemTime)
        shader.setInt("uSystemTick", system.tickCount)
        shader.setInt("uHasMask", if (system.maskTextureBindingKey == null) 0 else 1)
        shader.setFloat("uCurveCycleTicks", system.curveCycleTicks)
        shader.setFloat("uColorCycleTicks", system.colorCycleTicks)
        shader.setFloat("uColorCycleSpatialScale", system.colorCycleSpatialScale)

        val transition = system.visualTransition
        val transitionProgress = transition?.progressAt(systemTime)
        shader.setInt("uTransitionEnabled", if (transitionProgress == null) 0 else 1)
        setScalarCurve(shader, "uTransitionAlpha", transition?.alphaCurve)
        setScalarCurve(shader, "uTransitionScale", transition?.scaleCurve)
        if (transition != null && transitionProgress != null) {
            shader.setFloat4(
                "uTransitionParams",
                uniformScratch.params.set(
                    1F,
                    1F,
                    transitionProgress,
                    if (transition.hasColor) 1F else 0F,
                )
            )
            if (transition.hasColor) {
                shader.setFloat3("uTransitionColorFrom", transition.colorFrom)
                shader.setFloat3("uTransitionColorTo", transition.colorTo)
            }
        }
        val alphaTransition = system.alphaTransition
        val alphaTransitionProgress = alphaTransition?.progressAt(systemTime)
        setScalarCurve(
            shader,
            "uAlphaTransition",
            alphaTransition?.alphaCurve?.takeIf { alphaTransitionProgress != null },
        )
        shader.setFloat("uAlphaTransitionProgress", alphaTransitionProgress ?: 0F)
    }

    /**
     * 按系统与 transition 路径共用的 uniform 命名上传一条标量曲线。
     *
     * 示例：前缀 `uAlpha` 会写入 `uAlphaKeys`、`uAlphaCurveType` 和两组数据数组。
     * 禁止：曲线缺失时必须上传 0 个关键帧，不能让旧 uniform 继续生效。
     *
     * @param shader 当前 CParticle shader
     * @param prefix uniform 组的前缀
     * @param curve 要上传的曲线；`null` 会上传 0 个关键帧，由对应 shader 路径使用默认值
     */
    private fun setScalarCurve(shader: CooShaderProgram, prefix: String, curve: CParticleCurve?) {
        shader.setInt("${prefix}Keys", curve?.keyCount ?: 0)
        shader.setInt("${prefix}CurveType", curve?.interpolation?.wireId ?: 0)
        shader.setFloatArray("${prefix}Curve", curve?.packedData ?: emptyCurve)
        shader.setFloat4Array("${prefix}CurveHandles", curve?.packedHandleData ?: emptyCurveHandles)
    }

    /**
     * 上传一条 RGB 曲线及其两组值控制柄。
     *
     * 示例：前缀 `uColor` 会写入锚点以及独立的 RGB 出入控制柄。
     * 禁止：曲线缺失时必须上传 0 个关键帧，让 shader 保留粒子原色。
     *
     * @param shader 当前 CParticle shader
     * @param prefix uniform 组的前缀
     * @param curve 要上传的曲线；`null` 表示 RGB 倍率恒为 1
     */
    private fun setColorCurve(shader: CooShaderProgram, prefix: String, curve: CParticleColorCurve?) {
        shader.setInt("${prefix}Keys", curve?.keyCount ?: 0)
        shader.setInt("${prefix}CurveType", curve?.interpolation?.wireId ?: 0)
        shader.setFloatArray("${prefix}CurveTimes", curve?.packedTimeData ?: emptyColorTimes)
        shader.setFloat3Array("${prefix}CurveValues", curve?.packedColorData ?: emptyColorValues)
        shader.setFloat4Array("${prefix}CurveOutHandles", curve?.packedOutHandleData ?: emptyColorHandles)
        shader.setFloat4Array("${prefix}CurveInHandles", curve?.packedInHandleData ?: emptyColorHandles)
    }

    private class UniformScratch {
        val origin = Vector3f()
        val params = Vector4f()
    }

    /**
     * 释放 CParticle 图形 program 和外观查找表，并从统一注册表注销。
     *
     * 示例：[CParticleSystemManager.releaseAll] 完全关闭 CParticle 子系统时调用。
     * 禁止：普通资源重载不能调用本方法，注册表需要保留同一对象并重新编译。
     */
    fun release() {
        program?.let(ShaderProgramRegistry::unregister)
        program = null
        CParticleAppearanceDescriptors.release()
    }
}
