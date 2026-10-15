package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.world.item.ItemStack

/**
 * 1.20.1 兼容层：ItemStack 的「组件比较」API。
 *
 * 1.20.5+ 引入数据组件后有：
 *   `ItemStack.isSameItemSameComponents(a, b)`、`ItemStack.hashItemAndComponents(stack)`
 * 1.20.1 的等价语义是 **物品 + NBT**。
 *
 * 调用点写法是 `ItemStack.xxx(...)`（Java 风格静态调用），
 * Kotlin 里对应顶层函数，所以调用点已改写为直接函数名。
 */

/** 比较两个 ItemStack 是否「同物品且同数据」 */
fun isSameItemSameComponents(a: ItemStack, b: ItemStack): Boolean =
    ItemStack.isSameItemSameTags(a, b)

/** 物品 + 数据的哈希 */
fun hashItemAndComponents(stack: ItemStack): Int {
    var result = stack.item.hashCode()
    result = 31 * result + (stack.tag?.hashCode() ?: 0)
    return result
}
