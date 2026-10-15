package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.config.APIConfig

/**
 * Forge 1.20.1 的配置管理实现。
 *
 * 上游 NeoForge 版用 `ModConfigSpec`；这里先用**内存配置**（不落盘），
 * 保证 API 可用。后续要持久化可换成 ForgeConfigSpec。
 */
class ForgeAPIConfigManager : APIConfigManager {

    private var config: APIConfig? = null

    override fun getConfig(): APIConfig {
        if (config == null) loadConfig()
        return config!!
    }

    override fun loadConfig() {
        config = APIConfig()
    }

    override fun saveConfig() {

    }
}
