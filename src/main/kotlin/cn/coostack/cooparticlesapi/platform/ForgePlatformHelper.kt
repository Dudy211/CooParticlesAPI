package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.enums.DistType
import cn.coostack.cooparticlesapi.platform.services.IPlatformHelper
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLLoader

/**
 * Forge 1.20.1 的 IPlatformHelper 实现。
 *
 * 与 NeoForge 版差异很小（都是 Forge 系 API）：
 *   NeoForge: net.neoforged.fml.ModList / FMLLoader
 *   Forge:    net.minecraftforge.fml.ModList / FMLLoader
 */
class ForgePlatformHelper : IPlatformHelper {

    override fun getPlatformName(): String = "Forge"

    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)

    override fun isDevelopmentEnvironment(): Boolean = !FMLLoader.isProduction()

    override fun getDistType(): DistType =
        if (FMLLoader.getDist().isClient) DistType.CLIENT else DistType.SERVER
}
