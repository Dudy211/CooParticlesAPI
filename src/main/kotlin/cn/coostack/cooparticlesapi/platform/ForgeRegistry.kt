package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.platform.registry.CommonDeferredRegistry
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.RegisterEvent

/**
 * Forge 1.20.1 的注册器实现。
 *
 * `CommonDeferredRegistry` 是上游自制的延迟注册包装；
 * 在 Forge 上通过 `RegisterEvent` 落到真实注册表。
 *
 * 实现要点：
 *   RegisterEvent.register(ResourceKey<Registry<T>>, Consumer<RegisterHelper<T>>)
 *   —— T 在编译期未知，所以这里用 @Suppress + 强制转型。
 */
class ForgeRegistry : CooRegistry {

    /** registry key → 待注册条目 */
    private val pending = HashMap<Any, MutableList<CommonDeferredRegistry<*>>>()

    private var bus: IEventBus? = null
    private var listenersInstalled = false

    override fun <T : Any> register(registry: CommonDeferredRegistry<T>): CommonDeferredRegistry<T> {
        val key = registry.type.key()
        pending.getOrPut(key) { mutableListOf() }.add(registry)
        return registry
    }

    override fun init(any: Any?) {
        if (any !is IEventBus) {
            CooParticlesConstants.logger.warn("ForgeRegistry.init 收到非 IEventBus 参数: {}", any?.javaClass)
            return
        }
        bus = any
        installListeners(any)
    }

    @Suppress("UNCHECKED_CAST")
    private fun installListeners(bus: IEventBus) {
        if (listenersInstalled) return
        listenersInstalled = true

        bus.addListener { event: RegisterEvent ->
            val key = event.registryKey
            val list = pending[key] ?: return@addListener


            (event as RegisterEvent).register(
                key as ResourceKey<out Registry<Any>>,
            ) { helper ->
                list.forEach { reg ->
                    runCatching {
                        val deferred = reg as CommonDeferredRegistry<Any>
                        (helper as net.minecraftforge.registries.RegisterEvent.RegisterHelper<Any>)
                            .register(deferred.id, deferred.get())
                    }.onFailure {
                        CooParticlesConstants.logger.error(
                            "注册失败: {}", reg.id, it,
                        )
                    }
                }
            }
        }

        CooParticlesConstants.logger.info("ForgeRegistry 监听器已安装")
    }
}
