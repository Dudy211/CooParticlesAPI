package cn.coostack.cooparticlesapi.platform.registry

import cn.coostack.cooparticlesapi.compat.mc1201.DataComponentType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import java.util.function.Supplier

class CommonDeferredComponentType<T>(
    val id: ResourceLocation,
    val supplier: Supplier<DataComponentType<T>>
) {

    private var value: DataComponentType<T>? = null

    fun get(): DataComponentType<T> {
        if (value == null) {
            value = supplier.get()
        }
        return value!!
    }

}