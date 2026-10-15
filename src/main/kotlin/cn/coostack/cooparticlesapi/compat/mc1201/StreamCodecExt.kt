package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * 1.20.1 兼容层：补 ByteBufCodecs.idMapper 等。
 *
 * 上游用法（CodecHelper.kt:190、ControlableFallingDustEffect.kt:68）：
 * ```
 * ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)          // 无泛型
 * ByteBufCodecs.idMapper<BlockState>(Block.BLOCK_STATE_REGISTRY)  // 带泛型
 * ```
 * 1.20.1 用 `Block.getId(state)` / `Block.stateById(id)` 即可。
 */

/**
 * 兼容 `ByteBufCodecs.idMapper<T>(registry)` 的调用形式。
 *
 * 用泛型 + 默认实现覆盖 BlockState 场景（上游唯一的实际用途）。
 */
@Suppress("UNCHECKED_CAST")
fun <T> idMapper(registryOrAnything: Any?): StreamCodec<FriendlyByteBuf, T> =
    StreamCodec.of(
        { buf, value ->
            when (value) {
                is BlockState -> buf.writeVarInt(Block.getId(value))
                is Int -> buf.writeVarInt(value)
                else -> buf.writeVarInt(0)
            }
        },
        { buf ->
            val id = buf.readVarInt()
            Block.stateById(id) as T
        },
    )

/** BlockPos 的 stream codec（1.20.1 没有 STREAM_CODEC 字段） */
@JvmField
val BLOCK_POS_STREAM_CODEC: StreamCodec<FriendlyByteBuf, BlockPos> =
    StreamCodec.of(
        { buf, pos -> buf.writeBlockPos(pos) },
        { buf -> buf.readBlockPos() },
    )

/** BlockState 的 stream codec */
@JvmField
val BLOCK_STATE_STREAM_CODEC: StreamCodec<FriendlyByteBuf, BlockState> =
    StreamCodec.of(
        { buf, state -> buf.writeVarInt(Block.getId(state)) },
        { buf -> Block.stateById(buf.readVarInt()) },
    )
