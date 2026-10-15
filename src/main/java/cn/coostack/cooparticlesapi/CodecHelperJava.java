package cn.coostack.cooparticlesapi;

import cn.coostack.cooparticlesapi.compat.mc1201.StreamCodec;

import cn.coostack.cooparticlesapi.annotations.codec.CodecHelper;
import net.minecraft.network.FriendlyByteBuf;

public class CodecHelperJava {
    static {
        CodecHelper.register(
                Boolean.class, StreamCodec.of(FriendlyByteBuf::writeBoolean, FriendlyByteBuf::readBoolean)
        );
        CodecHelper.register(
                Integer.class, StreamCodec.of(FriendlyByteBuf::writeInt, FriendlyByteBuf::readInt)
        );
        CodecHelper.register(
                Long.class, StreamCodec.of(FriendlyByteBuf::writeLong, FriendlyByteBuf::readLong)
        );
    }

    public static void init() {
    }
}
