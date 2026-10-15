package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.compat.IrisCompat
import cn.coostack.cooparticlesapi.compat.iris.RenderTypeIrisSupposerRegistry
import cn.coostack.cooparticlesapi.display.CooRenderCullMode
import cn.coostack.cooparticlesapi.display.CooRenderDepthTestMode
import cn.coostack.cooparticlesapi.display.CooRenderLightmapMode
import cn.coostack.cooparticlesapi.display.CooRenderTransparencyMode
import cn.coostack.cooparticlesapi.display.CooRenderTypeDescriptor
import cn.coostack.cooparticlesapi.display.CooRenderTypeShaderPreset
import cn.coostack.cooparticlesapi.display.CooRenderTypesProvider
import cn.coostack.cooparticlesapi.display.CooShaderStateResolver
import cn.coostack.cooparticlesapi.renderer.pipeline.CooRenderPipeline
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainEffectComposition
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainMappingBatchKey
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainPipelineManager
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainRenderStateShard
import cn.coostack.cooparticlesapi.renderer.terrain.CooTerrainVertexFormats
import cn.coostack.cooparticlesapi.test.options.display.MCShaders
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.state.BlockState

/**
 * Forge 1.20.1 的 RenderType 提供者。
 *
 * 渲染 API（RenderType / RenderStateShard / VertexFormat）在 Forge 和 Fabric
 * 1.20.1 上完全一致（都来自 Mojang 的 blaze3d），所以本实现与上游 Fabric 版逻辑相同，
 * 只是类名与包名换成 Forge 体系。
 *
 * ⚠️ **不要再用反射读 RenderStateShard 的常量**。
 * 1.20.1 里它们是 `protected static final`，早期移植为了绕开编译错误写成
 * `getDeclaredField("NO_TRANSPARENCY")` —— 但生产环境经过 reobf 后字段叫 `f_110134_`，
 * 反射**必然**抛 `NoSuchFieldException`（实测每帧约 4000 次），
 * 异常会打断 `PoseStack` 的 push/pop 配对 → 进世界即崩 `PoseStack not empty`。
 *
 * 正确做法：在 `accesstransformer.cfg` 里把这些成员打开（AT 用 **SRG 名**），
 * 然后像上游一样**直接引用**。
 */
object ForgeRenderTypesProvider : CooRenderTypesProvider {

    private val cache = LinkedHashMap<CooRenderTypeDescriptor, RenderType>()
    private val entityCutoutEmissiveCache = LinkedHashMap<Pair<ResourceLocation, Float>, RenderType>()
    private val terrainCache = LinkedHashMap<Pair<Any, RenderType>, RenderType>()
    private val glowId = ResourceLocation(CooParticlesConstants.MOD_ID, "glow")

    private val glowDescriptor = CooRenderTypeDescriptor.builder("coo_glow")
        .bufferSize(512)
        .shaderPreset(CooRenderTypeShaderPreset.COO_GLOW)
        .transparencyMode(CooRenderTransparencyMode.ADDITIVE)
        .cullMode(CooRenderCullMode.DISABLED)
        .lightmapMode(CooRenderLightmapMode.DISABLED)
        .depthTestMode(CooRenderDepthTestMode.LEQUAL)
        .build()

    /** 默认加法混合发光 RenderType。 */
    val glow: RenderType
        get() = named(glowId) ?: create(glowDescriptor)

    override fun entityCutoutEmissive(texture: ResourceLocation, brightness: Float): RenderType {
        val resolvedBrightness = brightness.coerceAtLeast(0F)
        val renderType = entityCutoutEmissiveCache.getOrPut(texture to resolvedBrightness) {
            val renderTypeName = "coo_entity_cutout_emissive_$resolvedBrightness"
            RenderTypeIrisSupposerRegistry.register(renderTypeName, "coo_entity_cutout_emissive")
            val state = RenderType.CompositeState.builder()
                .setShaderState(
                    RenderStateShard.ShaderStateShard {





                        MCShaders.ensureInitialized()
                        MCShaders.ENTITY_CUTOUT_EMISSIVE.also { shader ->
                            shader.getUniform("Brightness")?.set(resolvedBrightness)
                            IrisCompat.markUnskippable(shader)
                        }
                    }
                )
                .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true)

            RenderType.create(
                renderTypeName,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                true,
                false,
                state,
            )
        }
        return IrisCompat.wrapEntityRenderType(renderType)
    }

    override fun create(descriptor: CooRenderTypeDescriptor): RenderType {
        val renderType = cache.getOrPut(descriptor) {
            RenderTypeIrisSupposerRegistry.register(descriptor.name, null)
            RenderType.create(
                descriptor.name,
                descriptor.vertexFormat,
                descriptor.mode,
                descriptor.bufferSize,
                descriptor.affectsCrumbling,
                descriptor.sortOnUpload,
                RenderType.CompositeState.builder()
                    .setShaderState(
                        CooShaderStateResolver.customShaderSupplier(descriptor)
                            ?.let { RenderStateShard.ShaderStateShard(it) }
                            ?: RenderStateShard.POSITION_COLOR_SHADER
                    )
                    .setTransparencyState(
                        when (descriptor.transparencyMode) {
                            CooRenderTransparencyMode.NONE -> RenderStateShard.NO_TRANSPARENCY
                            CooRenderTransparencyMode.ADDITIVE -> RenderStateShard.ADDITIVE_TRANSPARENCY
                        }
                    )
                    .setCullState(
                        when (descriptor.cullMode) {
                            CooRenderCullMode.ENABLED -> RenderStateShard.CULL
                            CooRenderCullMode.DISABLED -> RenderStateShard.NO_CULL
                        }
                    )
                    .setLightmapState(
                        when (descriptor.lightmapMode) {
                            CooRenderLightmapMode.ENABLED -> RenderStateShard.LIGHTMAP
                            CooRenderLightmapMode.DISABLED -> RenderStateShard.NO_LIGHTMAP
                        }
                    )
                    .setDepthTestState(
                        when (descriptor.depthTestMode) {
                            CooRenderDepthTestMode.LEQUAL -> RenderStateShard.LEQUAL_DEPTH_TEST
                        }
                    )
                    .createCompositeState(false)
            )
        }
        return IrisCompat.wrapEntityRenderType(renderType)
    }

    override fun terrain(
        pipeline: CooRenderPipeline<BlockState>,
        baseLayer: RenderType,
        batchKey: Any,
    ): RenderType {
        return synchronized(terrainCache) {
            terrainCache.getOrPut(batchKey to baseLayer) {


                val sorted = baseLayer.sortOnUpload
                val mipmap = baseLayer === RenderType.solid() ||
                    baseLayer === RenderType.cutoutMipped() ||
                    baseLayer === RenderType.tripwire()
                lateinit var renderType: RenderType
                renderType = RenderType.create(
                    "coo_terrain_overlay_${pipeline.id.namespace}_${pipeline.id.path.replace('/', '_')}",
                    CooTerrainVertexFormats.BLOCK_EFFECT,
                    VertexFormat.Mode.QUADS,
                    RenderType.BIG_BUFFER_SIZE,
                    baseLayer.affectsCrumbling(),
                    sorted,
                    RenderType.CompositeState.builder()
                        .setShaderState(
                            RenderStateShard.ShaderStateShard {
                                CooTerrainPipelineManager.shaderFor(renderType, baseLayer)
                                    ?.also(IrisCompat::markUnskippable)
                            }
                        )
                        .setTextureState(
                            RenderStateShard.TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, mipmap)
                        )
                        .setTransparencyState(
                            when (batchKey) {
                                is CooTerrainMappingBatchKey -> when (batchKey.composition) {
                                    CooTerrainEffectComposition.REPLACE -> RenderStateShard.NO_TRANSPARENCY
                                    CooTerrainEffectComposition.ALPHA_OVER -> RenderStateShard.TRANSLUCENT_TRANSPARENCY
                                    CooTerrainEffectComposition.ADDITIVE -> RenderStateShard.ADDITIVE_TRANSPARENCY
                                }
                                else -> if (sorted) RenderStateShard.TRANSLUCENT_TRANSPARENCY
                                else RenderStateShard.NO_TRANSPARENCY
                            }
                        )
                        .setCullState(RenderStateShard.CULL)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setLayeringState(CooTerrainRenderStateShard.terrainLayering())
                        .setWriteMaskState(CooTerrainRenderStateShard.terrainWriteMask(baseLayer))
                        .setOutputState(CooTerrainRenderStateShard.terrainOutput(baseLayer))
                        .createCompositeState(true)
                )
                renderType
            }
        }
    }

    override fun clearTerrainCache() {
        synchronized(terrainCache) {
            terrainCache.clear()
        }
    }

    override fun glow(): RenderType = glow
}

/**
 * ServiceLoader 适配器（Kotlin object 需要公共构造器的包装类）。
 * 与上游 `NeoRenderTypesProviderService` 同样的写法。
 */
class ForgeRenderTypesProviderService : CooRenderTypesProvider by ForgeRenderTypesProvider
