package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.platform.services.IPlatformHelper
import java.util.*
import java.util.function.Supplier

object CooParticlesServices {



    @JvmField
    val PLATFORM: IPlatformHelper = load(IPlatformHelper::class.java)

    @JvmField
    val CLIENT_NETWORK: ClientNetworking = load(ClientNetworking::class.java)

    @JvmField
    val SERVER_NETWORK: ServerNetworking = load(ServerNetworking::class.java)

    @JvmField
    val API_CONFIG_MANAGER: APIConfigManager = load(APIConfigManager::class.java)

    @JvmField
    val COO_REGISTRY = load(CooRegistry::class.java)





    fun <T> load(clazz: Class<T>): T {
        val loadedService = ServiceLoader.load(clazz)
            .findFirst()
            .orElseThrow(Supplier { NullPointerException("Failed to load service for " + clazz.getName()) })
        CooParticlesConstants.logger.debug("Loaded {} for service {}", loadedService, clazz)
        return loadedService!!
    }
}
