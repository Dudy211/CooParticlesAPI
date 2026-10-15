package cn.coostack.cooparticlesapi

import cn.coostack.cooparticlesapi.entities.CooModEntityTypes
import cn.coostack.cooparticlesapi.items.CooItemForge
import cn.coostack.cooparticlesapi.items.group.CooItemGroup
import cn.coostack.cooparticlesapi.network.CooNetworkRegistry
import cn.coostack.cooparticlesapi.network.packet.compat.ForgePacketChannels
import cn.coostack.cooparticlesapi.particles.CooModParticles
import cn.coostack.cooparticlesapi.platform.CooParticlesServices
import cn.coostack.cooparticlesapi.reflect.CooAPIScanner
import cn.coostack.cooparticlesapi.reflect.SimpleClassInfo
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.registries.RegisterEvent
import thedarkcolour.kotlinforforge.forge.DIST
import thedarkcolour.kotlinforforge.forge.MOD_BUS

/**
 * CooParticlesAPI 的 Forge 1.20.1 主入口（服务端 + 客户端共用）。
 *
 * 关键：用 KFF 的 `MOD_BUS`，**不能**用 `FMLJavaModLoadingContext.get().modEventBus`
 * —— 后者内部有 `(FMLJavaModLoadingContext) ModLoadingContext.get().extension()`
 * 的强转，而 KFF 注册的是 `KotlinModLoadingContext`，会抛 ClassCastException。
 *
 * KFF 4.12.0 (kfflib) 的 `ForgeKt` 提供：
 *   MOD_BUS    —— mod 事件总线
 *   FORGE_BUS  —— forge 总线
 *   DIST       —— 当前 dist
 */
@Mod(CooParticlesConstants.MOD_ID)
object CooParticlesAPIForge {

    init {
        CooParticlesConstants.logger.info("CooParticlesAPI Forge 入口初始化 (dist={})", DIST)

        MOD_BUS.addListener(::onCommon)
        MOD_BUS.addListener(::onRegister)


































        if (DIST.isClient) {
            runCatching { CooParticlesAPIForgeClient.registerListeners() }
                .onFailure { CooParticlesConstants.logger.error("客户端监听器注册失败", it) }
        }








        runCatching { cn.coostack.cooparticlesapi.listener.forge.CooParticlesForgeCommonListeners.register() }
            .onFailure { CooParticlesConstants.logger.error("通用监听器注册失败", it) }
        runCatching { cn.coostack.cooparticlesapi.listener.forge.CooParticlesForgeServerListeners.register() }
            .onFailure { CooParticlesConstants.logger.error("服务端监听器注册失败", it) }


        CooAPIScanner.registerPacket(CooParticlesConstants.MOD_ID)
        CooAPIScanner.registerPacket("cn.coostack")


        runCatching { CooParticlesAPI.init() }
            .onFailure { CooParticlesConstants.logger.error("CooParticlesAPI.init 失败", it) }


        runCatching { CooItemGroup.reg() }
            .onFailure { CooParticlesConstants.logger.warn("CooItemGroup.reg 失败: {}", it.message) }




        runCatching { CooItemForge.reg() }
            .onFailure { CooParticlesConstants.logger.error("CooItemForge.reg 失败", it) }


        runCatching { CooParticlesServices.COO_REGISTRY.init(MOD_BUS) }
            .onFailure { CooParticlesConstants.logger.warn("COO_REGISTRY.init 失败: {}", it.message) }
    }

    /** 所有模组加载完毕 */
    private fun onCommon(event: FMLCommonSetupEvent) {
        CooParticlesConstants.logger.info("所有模组加载完毕 CooParticlesAPI->Called")





        event.enqueueWork {




            runCatching { ForgePacketChannels.register() }
                .onFailure { CooParticlesConstants.logger.error("直通包登记失败", it) }


            runCatching { CooNetworkRegistry.register() }
                .onFailure { CooParticlesConstants.logger.error("网络通道注册失败", it) }




            if (DIST.isClient) {
                runCatching { CooParticlesAPIForgeClient.setupClient() }
                    .onFailure { CooParticlesConstants.logger.error("客户端初始化失败", it) }
            }












            var scanImported = 0
            runCatching {
                collectScanData().forEach { (name, annots) ->
                    CooAPIScanner.inputScanResult(SimpleClassInfo(name, annots))
                    scanImported++
                }
                CooParticlesConstants.logger.info("Forge 扫描数据导入完成（$scanImported 个类）")
            }.onFailure {
                CooParticlesConstants.logger.warn("allScanData 导入失败: {}", it.message)
            }

            if (scanImported > 0) {

                CooAPIScanner.neoLoaded()
            } else {

                runCatching { CooAPIScanner.scan() }
                    .onFailure { CooParticlesConstants.logger.error("ClassGraph 扫描失败", it) }
            }















            runCatching { CooParticlesAPI.loadScannerPackages() }
                .onFailure { CooParticlesConstants.logger.error("loadScannerPackages 失败", it) }

            CooParticlesConstants.logger.info("CooParticlesAPI 初始化流程完成")
        }
    }

    /** 从 Forge 的 ModFileScanData 收集「类名 → 注解集合」 */
    private fun collectScanData(): Map<String, HashSet<String>> {
        val out = HashMap<String, HashSet<String>>()
        ModList.get().allScanData.forEach { scan ->
            scan.annotations.forEach { anno ->
                out.getOrPut(anno.clazz.className) { HashSet() }
                    .add(anno.annotationType.className)
            }
        }
        return out
    }

    /** 注册表事件：粒子类型 + 实体类型 */
    private fun onRegister(event: RegisterEvent) {
        runCatching {
            val key = event.registryKey
            if (key == BuiltInRegistries.PARTICLE_TYPE.key()) {
                @Suppress("UNCHECKED_CAST")
                event.register(
                    BuiltInRegistries.PARTICLE_TYPE.key()
                        as net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<net.minecraft.core.particles.ParticleType<*>>>
                ) { helper ->
                    CooModParticles.particleTypes.forEach { type ->
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            (helper as net.minecraftforge.registries.RegisterEvent.RegisterHelper<net.minecraft.core.particles.ParticleType<*>>)
                                .register(type.id, type.get())
                        }
                    }
                }
            }
            if (key == BuiltInRegistries.ENTITY_TYPE.key()) {
                @Suppress("UNCHECKED_CAST")
                event.register(
                    BuiltInRegistries.ENTITY_TYPE.key()
                        as net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<net.minecraft.world.entity.EntityType<*>>>
                ) { helper ->
                    CooModEntityTypes.types.forEach { type ->
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            (helper as net.minecraftforge.registries.RegisterEvent.RegisterHelper<net.minecraft.world.entity.EntityType<*>>)
                                .register(type.id, type.get())
                        }
                    }
                }
            }
        }.onFailure {
            CooParticlesConstants.logger.error("注册表事件处理失败", it)
        }
    }
}
