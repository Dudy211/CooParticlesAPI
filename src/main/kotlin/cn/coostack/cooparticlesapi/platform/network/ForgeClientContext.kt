package cn.coostack.cooparticlesapi.platform.network

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/**
 * Forge 1.20.1 的客户端包上下文 —— 对应上游 `NeoForgeClientContext`。
 *
 * ⚠️ 上游这个文件在平台侧目录（`neoforge/`），移植时整个目录没搬 →
 * 20 个 S2C 接收器（`client/listener/ 下的各 Handler`）虽然都被我们搬过来了，
 * 但**没有任何实现能构造出 ClientContext** → 它们永远拿不到上下文 → 等于收不到包。
 *
 * 1.20.1 没有 `IPayloadContext`（那是 1.20.5+ 的），
 * 客户端这边本来就能直接拿到 `Minecraft` 实例，所以不需要额外上下文对象。
 */
class ForgeClientContext : ClientContext {

    override fun player(): Player =
        Minecraft.getInstance().player
            ?: throw IllegalStateException("ForgeClientContext.player() 在无玩家时被调用")

    override fun client(): Minecraft = Minecraft.getInstance()
}
