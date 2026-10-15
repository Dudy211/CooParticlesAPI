package cn.coostack.cooparticlesapi.mixin.plugin;

import cn.coostack.cooparticlesapi.platform.CooParticlesServices;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * @author CooStack
 */
public class CooParticleMixinLoaderPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    /**
     * 返回 null = 让 Mixin 使用 mixins.json 里的 "refmap" 字段（cooparticlesapi.refmap.json）。
     *
     * <p>⚠️ 这里**绝不能**返回任意字符串：Mixin 的 {@code MixinConfig.onLoad} 会把它当
     * <b>资源文件名</b>直接 {@code ReferenceMapper.read(name)}，读不到就退化成空表 → 所有
     * {@code @Shadow}/{@code @Inject} 的字符串字面量（official 名）原样拿去查 SRG 环境，
     * 生产环境启动即崩（"@Shadow field xxx was not located in the target class"）。
     * 上游 1.21 里这个返回值同样无效，只是 dev 环境掩盖了它。
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if ("cn.coostack.cooparticlesapi.mixin.ParticleEngineMixin".equals(mixinClassName)) {
            return CooParticlesServices.API_CONFIG_MANAGER.getConfig().getEnabledParticleCountInject();
        }
        if ("cn.coostack.cooparticlesapi.mixin.ParticleManagerRenderMixin".equals(mixinClassName)) {
            return CooParticlesServices.API_CONFIG_MANAGER.getConfig().getEnabledParticleAsync();
        }
        if ("cn.coostack.cooparticlesapi.mixin.SectionCompilerMixin".equals(mixinClassName)) {
            return CooParticleMixinLoaderPlugin.class.getClassLoader().getResource(
                    "net/neoforged/neoforge/common/NeoForge.class"
            ) == null;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

}
