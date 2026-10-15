package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.core.RegistryAccess
import net.minecraft.network.FriendlyByteBuf

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 1.20.1 兼容层：补齐 1.20.5+ 才有的 FriendlyByteBuf 扩展方法。
 *
 * 上游大量使用 writeVec3 / readVec3 / writeVector3f / writeQuaternion 等。
 * 以顶层扩展函数形式补齐，调用点无需任何改动。
 *
 * ⚠ 注意：方法名必须与 1.20.1 原有方法区分开，否则会和成员方法冲突或自我递归。
 *    Vec3 / Vector3f / Quaternionf / BlockPos 都是 1.20.1 没有的，安全。
 */

fun FriendlyByteBuf.writeVec3(vec: Vec3) {
    this.writeDouble(vec.x)
    this.writeDouble(vec.y)
    this.writeDouble(vec.z)
}

fun FriendlyByteBuf.readVec3(): Vec3 =
    Vec3(this.readDouble(), this.readDouble(), this.readDouble())

fun FriendlyByteBuf.writeVector3f(v: Vector3f) {
    this.writeFloat(v.x)
    this.writeFloat(v.y)
    this.writeFloat(v.z)
}

fun FriendlyByteBuf.readVector3f(): Vector3f =
    Vector3f(this.readFloat(), this.readFloat(), this.readFloat())

fun FriendlyByteBuf.writeQuaternion(q: Quaternionf) {
    this.writeFloat(q.x)
    this.writeFloat(q.y)
    this.writeFloat(q.z)
    this.writeFloat(q.w)
}

fun FriendlyByteBuf.readQuaternion(): Quaternionf =
    Quaternionf(this.readFloat(), this.readFloat(), this.readFloat(), this.readFloat())

@Suppress("unused")
fun FriendlyByteBuf.writeBlockPosCompat(pos: BlockPos) {
    this.writeBlockPos(pos)
}

@Suppress("unused")
fun FriendlyByteBuf.writeResourceLocationCompat(id: ResourceLocation) {
    this.writeResourceLocation(id)
}

/** 1.20.1 是 readItem / writeItem，1.20.5+ 是 ItemStack.STREAM_CODEC */
fun FriendlyByteBuf.writeItemStackCompat(stack: ItemStack) {
    this.writeItem(stack)
}

fun FriendlyByteBuf.readItemStackCompat(): ItemStack = this.readItem()

/**
 * 1.20.1 兼容：ItemStack.STREAM_CODEC / OPTIONAL_STREAM_CODEC
 * 上游用法：`ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack)` / `.decode(buf)`
 */
val ItemStackStreamCodecsPlaceholder = Unit

/** ItemStack 的 codec（非空） */
@JvmField
val ITEM_STACK_STREAM_CODEC: cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec<FriendlyByteBuf, ItemStack> =
    cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec.of(
        { buf, stack -> buf.writeItem(stack) },
        { buf -> buf.readItem() },
    )

/** ItemStack 的 codec（可空/可空栈） */
@JvmField
val OPTIONAL_ITEM_STACK_STREAM_CODEC: cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec<FriendlyByteBuf, ItemStack> =
    cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec.of(
        { buf, stack -> buf.writeItem(stack) },
        { buf -> buf.readItem() },
    )

/**
 * 1.20.1 兼容：RegistryFriendlyByteBuf 才有 registryAccess()。
 * 普通 FriendlyByteBuf 用线程本地上下文代理（由写包方设置）。
 */
private val registryAccessHolder = ThreadLocal<net.minecraft.core.RegistryAccess?>()

var FriendlyByteBuf.currentRegistryAccess: net.minecraft.core.RegistryAccess?
    get() = registryAccessHolder.get()
    set(value) { registryAccessHolder.set(value) }

fun FriendlyByteBuf.registryAccess(): net.minecraft.core.RegistryAccess =
    registryAccessHolder.get() ?: net.minecraft.core.RegistryAccess.EMPTY
