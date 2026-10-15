package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.network.FriendlyByteBuf

/**
 * 1.20.1 兼容层：模拟 1.20.5+ 的 StreamCodec。
 *
 * 上游用 StreamCodec.of(encoder, decoder) 构造，用 encode/decode 调用，
 * 用 map() 做双向转换。这里提供等价的最小实现。
 */
interface StreamCodec<B, V> {
    fun encode(buffer: B, value: V)
    fun decode(buffer: B): V

    /** 把值类型从 V 映射到 O（双向） */
    fun <O> map(to: (V) -> O, from: (O) -> V): StreamCodec<B, O> {
        val self = this
        return object : StreamCodec<B, O> {
            override fun encode(buffer: B, value: O) = self.encode(buffer, from(value))
            override fun decode(buffer: B): O = to(self.decode(buffer))
        }
    }

    /** 顺序执行（ResultFunction） */
    fun <O> apply(operator: StreamCodec<B, (V) -> O>): StreamCodec<B, O> {
        val self = this
        return object : StreamCodec<B, O> {
            override fun encode(buffer: B, value: O) {

                @Suppress("UNCHECKED_CAST")
                self.encode(buffer, value as V)
            }

            override fun decode(buffer: B): O {
                val f = operator.decode(buffer)
                return f(self.decode(buffer))
            }
        }
    }

    companion object {
        @JvmStatic
        fun <B, V> of(encoder: CodecEncoder<B, V>, decoder: CodecDecoder<B, V>): StreamCodec<B, V> =
            object : StreamCodec<B, V> {
                override fun encode(buffer: B, value: V) = encoder.encode(buffer, value)
                override fun decode(buffer: B): V = decoder.decode(buffer)
            }

        /** unit：不编码任何数据 */
        @JvmStatic
        fun <B> unit(): StreamCodec<B, Unit> = of({ _, _ -> }, { })
    }

    fun interface CodecEncoder<B, V> {
        fun encode(buffer: B, value: V)
    }

    fun interface CodecDecoder<B, V> {
        fun decode(buffer: B): V
    }
}

/** 便捷别名，方便 Java 侧调用 */
typealias FriendlyStreamCodec<V> = StreamCodec<FriendlyByteBuf, V>
