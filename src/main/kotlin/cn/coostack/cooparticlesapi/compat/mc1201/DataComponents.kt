package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * 1.20.1 兼容层：模拟 1.20.5+ 的 DataComponent 体系。
 *
 * ⚠ 1.20.1 没有数据组件，物品附加数据一律走 NBT。
 * 这里提供一个最小桥接：
 *   - `CUSTOM_DATA`  -> ItemStack 的 `tag`（即原版 custom_data 组件的等价物）
 *
 * 上游用法（TestBlockBinding.kt）：
 * ```
 * stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
 * stack.remove(DataComponents.CUSTOM_DATA)
 * stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root))
 * ```
 */

/** 组件类型的标记接口 */
interface DataComponentType<T>

/** 一个组件键。持有一个「从 ItemStack 读 / 写」的策略 */
class ComponentKey<T>(
    val id: String,
    private val reader: (ItemStack) -> T?,
    private val writer: (ItemStack, T?) -> Unit,
) : DataComponentType<T> {
    fun read(stack: ItemStack): T? = reader(stack)
    fun write(stack: ItemStack, value: T?) = writer(stack, value)
}

/**
 * 原版 DataComponents 的等价物。
 *
 * 1.20.1 里只映射 `CUSTOM_DATA`（←→ ItemStack.tag）
 */
object DataComponents {

    /** 1.20.1: 直接读写 ItemStack.tag */
    @JvmField
    val CUSTOM_DATA: ComponentKey<CustomData> = ComponentKey(
        id = "custom_data",
        reader = { stack ->
            stack.tag?.let { CustomData(it.copy()) }
        },
        writer = { stack, value ->
            if (value == null || value.tag.isEmpty) {
                stack.tag = null
            } else {
                stack.tag = value.tag.copy()
            }
        },
    )
}

/** CustomData 的最小实现（包装 CompoundTag） */
class CustomData internal constructor(internal val tag: CompoundTag) {

    fun copyTag(): CompoundTag = tag.copy()

    companion object {
        @JvmField
        val EMPTY = CustomData(CompoundTag())

        @JvmStatic
        fun of(tag: CompoundTag): CustomData = CustomData(tag.copy())
    }
}

fun <T> ItemStack.getOrDefault(type: ComponentKey<T>, fallback: T): T =
    type.read(this) ?: fallback

fun <T> ItemStack.get(type: ComponentKey<T>): T? = type.read(this)

fun <T> ItemStack.set(type: ComponentKey<T>, value: T?) = type.write(this, value)

fun <T> ItemStack.remove(type: ComponentKey<T>) = type.write(this, null)
