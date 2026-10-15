package cn.coostack.cooparticlesapi.network.packet.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 「字节直通」C2S 通道的载荷：`payloadId + bytes`。
 *
 * 上游在 1.20.5+ 上是**每个 payload 一条通道**（`registrar.playToServer(id, CODEC)`），
 * 1.20.1 的 SimpleChannel 做不到按 id 动态分发，所以这里收成一条通道 +
 * 一个 id 字段，服务端拿到后再按 id 分派给对应接收器
 * （当前只有 `PacketKeyActionC2S` → `ServerKeyActionHandler`）。
 *
 * 见 {@link ForgePayloadCompat} 的类注释了解背景。
 */
public class ForgeDirectPayloadC2S {
    public final ResourceLocation id;
    public final byte[] data;

    public ForgeDirectPayloadC2S(ResourceLocation id, byte[] data) {
        this.id = id;
        this.data = data;
    }

    public static void encode(ForgeDirectPayloadC2S packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.id);
        buf.writeByteArray(packet.data);
    }

    public static ForgeDirectPayloadC2S decode(FriendlyByteBuf buf) {
        return new ForgeDirectPayloadC2S(buf.readResourceLocation(), buf.readByteArray());
    }
}
