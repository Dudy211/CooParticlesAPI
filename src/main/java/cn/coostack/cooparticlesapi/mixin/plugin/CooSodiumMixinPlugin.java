package cn.coostack.cooparticlesapi.mixin.plugin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 条件加载：只有装了 Embeddium（1.20.1 Forge 的 Sodium）才应用这一组 mixin。
 *
 * <h2>★ 为什么不是 Sodium</h2>
 *
 * Sodium 官方**在 1.20.1 上没有 Forge/NeoForge 版**（Modrinth 实查：1.20.1 的 13 个发布
 * 全是 fabric/quilt；Forge 线从 1.21.1 才开始）。1.20.1 Forge 的等价物是
 * <b>Embeddium</b>（Rubidium 的官方后继者，Rubidium 已停更）。
 *
 * <p>好消息：Embeddium 沿用 Sodium 的**旧包名** {@code me.jellysquid.mods.sodium.*}
 * （不是上游 1.21 用的 {@code net.caffeinemc.mods.sodium.*}），
 * 所以这里检测路径必须写成 {@code me/jellysquid/...}，与 mixin 源码里的 import 保持一致。
 *
 * <h2>★ 三个别名都检测</h2>
 *
 * 用 {@code SodiumWorldRenderer} 这个类作为「Embeddium 是否存在」的探针 ——
 * 它是 Sodium 系模组的核心类，Embeddium / Rubidium 都有同名类（只是包名可能不同）。
 * 这里只检测 Embeddium 的包名；若将来要加 Rubidium，在这里补一个路径即可
 * （架构上已经留好：只要 shouldApplyMixin 返回 true，全组 mixin 一起上）。
 *
 * <p>⚠️ 这段代码由 Mixin 在**极早期**（ClassLoader 阶段）调用，
 * 不能引用任何 Minecraft / Forge 类，只能用 ClassLoader.getResource。
 */
public class CooSodiumMixinPlugin implements IMixinConfigPlugin {

    /**
     * Embeddium 的类探测路径（= Sodium 旧包名）。
     *
     * <p>Embeddium 0.3.31 实测（javap 确认）：
     * {@code me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.class}
     */
    private static final String EMBEDDIUM_PROBE =
            "me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.class";

    /** 备用探测路径：Rubidium 0.5.x 之前用的老包名（其实与 Embeddium 相同，留作将来扩展）。 */
    private static final String[] PROBES = {
            EMBEDDIUM_PROBE,
    };

    @Override
    public void onLoad(String mixinPackage) {
    }

    /**
     * 返回 null = 用 mixins.json 里声明的 refmap。
     *
     * <p>⚠️ 绝不能返回任意字符串：Mixin 会把它当资源文件名直接读，读不到就退化成空表，
     * 所有 @Shadow/@Inject 的 official 名字在 SRG 环境里查不到 → 启动即崩。
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        ClassLoader cl = CooSodiumMixinPlugin.class.getClassLoader();
        for (String probe : PROBES) {
            if (cl.getResource(probe) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName,
                         ClassNode targetClass,
                         String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName,
                          ClassNode targetClass,
                          String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
