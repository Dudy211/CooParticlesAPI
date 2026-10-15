package cn.coostack.cooparticlesapi.items

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.platform.CooParticlesServices
import cn.coostack.cooparticlesapi.platform.registry.CommonDeferredRegistry
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Forge 1.20.1 侧的物品注册。对应上游 NeoForge 的 `CooItemNeoForge`。
 *
 * <h2>⚠️ 为什么必须有这个文件（真机崩过，勿删）</h2>
 *
 * common 侧的 `CooItems.register(...)` **只把条目放进本地列表，不做真正的注册**
 * （see `CooItems.getRegisterItems()` 的注释「交给对应平台处理后」）：
 *
 * ```kotlin
 * fun register(id: String, item: Supplier<Item>): CommonDeferredItem {
 *     val di = CommonDeferredItem(location, item)
 *     itemsWithID[location] = di
 *     items.add(di)
 *     return di          // ← 这里没有注册进 BuiltInRegistries.ITEM
 * }
 * ```
 *
 * 上游把它交给了平台侧：
 * ```kotlin
 * // neoforge/.../items/CooItemNeoForge.kt
 * object CooItemNeoForge {
 *     val ITEMS = DeferredRegister.createItems(MOD_ID)
 *     fun reg(bus: IEventBus) {
 *         ITEMS.register(bus)
 *         CooItems.getRegisterItems()
 *         CooItems.itemsWithID.forEach { ITEMS.register(it.key.path, Supplier { it.value.getItem() }) }
 *     }
 * }
 * ```
 *
 * 移植到 Forge 时**漏了这一步**，后果是：物品从来没进过注册表，而
 * `CommonDeferredItem.getItem()` 是懒加载 —— 直到打开创造物品栏、
 * `CooItemGroup.API_GROUP` 的 `displayItems` 调它时才去 `new Item()`，
 * 那时注册表早已冻结：
 *
 * ```
 * IllegalStateException: Registry is already frozen
 *     at NamespacedWrapper.m_245419_          // createIntrusiveHolder
 *     at net.minecraft.world.item.Item.<init>(Item.java:61)
 *     at cn.coostack.cooparticlesapi.items.APIGroupTestingItem.<init>(APIGroupTestingItem.kt:25)
 *     at CommonDeferredItem.getItem(CommonDeferredItem.kt:12)
 *     at CooItemGroup.API_GROUP$lambda$4$lambda$3(CooItemGroup.kt:27)
 *     at ForgeHooks.onCreativeModeTabBuildContents
 * ```
 *
 * <h2>实现</h2>
 *
 * 复用项目自己的 `COO_REGISTRY`（它在 `RegisterEvent` 里落到真实注册表），
 * 所以这里只负责「把 itemsWithID 交给注册器」，时机与顺序都不敏感。
 *
 * 注意物品是**懒加载**的：`{ deferred.getItem() }` 这个 lambda 直到
 * Forge 处理 `RegisterEvent` 时才求值 —— 那时 `Item.<init>` 里的
 * `createIntrusiveHolder` 合法。
 */
object CooItemForge {

    fun reg() {


        CooItems.getRegisterItems()

        CooItems.itemsWithID.forEach { (id, deferred) ->
            CooParticlesConstants.logger.info("register item :{}", id.path)
            CooParticlesServices.COO_REGISTRY.register(
                CommonDeferredRegistry(BuiltInRegistries.ITEM, id) { deferred.getItem() }
            )
        }
    }
}
