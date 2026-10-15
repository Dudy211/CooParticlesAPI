package cn.coostack.cooparticlesapi.mixin.compat.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 降低 Iris/Oculus 粒子着色器的 alpha 阈值，避免自定义粒子的半透明边缘被裁掉。
 *
 * <h2>★ 平台对照（2026-10-05）</h2>
 * MC 1.21.1 NeoForge = Iris；MC 1.20.1 Forge = <b>Oculus</b>（Iris 的 FML 移植）。
 * 两者包名/方法名一致，本 mixin 不需要区分。
 *
 * <p>{@code @Pseudo} + {@code require = 0} 表示：目标类不存在（没装光影）时静默跳过，
 * 不会影响启动。Oculus 1.20.1-1.8.0 实测有
 * {@code public AlphaTest getAlphaTest()} 以及 {@code PARTICLES} / {@code PARTICLES_TRANS}
 * 两个枚举常量，注入点齐全。
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderKey", remap = false)
public abstract class ShaderKeyIrisCompatMixin {
    /**
     * Iris 的 {@code GREATER} Alpha Test 使用的参考值。
     *
     * <p>示例：{@code alpha == 0.001F} 时比较成立并保留片元。
     * 禁止直接改成 {@code 0.001F}，否则等于阈值的片元也会被丢弃。
     */
    @Unique
    private static final float cooparticlesapi$PARTICLE_ALPHA_THRESHOLD = Math.nextDown(0.001F);
    @Unique
    private static volatile boolean cooparticlesapi$particleAlphaTestResolved;
    @Unique
    private static Object cooparticlesapi$particleAlphaTest;

    @Inject(method = "getAlphaTest", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void cooparticlesapi$lowerParticleAlphaThreshold(CallbackInfoReturnable<Object> cir) {
        String shaderKey = ((Enum<?>) (Object) this).name();
        if (!"PARTICLES".equals(shaderKey) && !"PARTICLES_TRANS".equals(shaderKey)) {
            return;
        }
        Object alphaTest = cooparticlesapi$getParticleAlphaTest(cir.getReturnValue());
        if (alphaTest != null) {
            cir.setReturnValue(alphaTest);
        }
    }

    @Unique
    private static Object cooparticlesapi$getParticleAlphaTest(Object original) {
        if (cooparticlesapi$particleAlphaTestResolved) {
            return cooparticlesapi$particleAlphaTest;
        }
        synchronized (ShaderKeyIrisCompatMixin.class) {
            if (cooparticlesapi$particleAlphaTestResolved) {
                return cooparticlesapi$particleAlphaTest;
            }
            cooparticlesapi$particleAlphaTest = cooparticlesapi$createParticleAlphaTest(original);
            cooparticlesapi$particleAlphaTestResolved = true;
            return cooparticlesapi$particleAlphaTest;
        }
    }

    @Unique
    private static Object cooparticlesapi$createParticleAlphaTest(Object original) {
        if (original == null) {
            return null;
        }
        try {
            Class<?> alphaTestClass = original.getClass();
            Method functionAccessor = alphaTestClass.getMethod("function");
            Object function = functionAccessor.invoke(original);
            Constructor<?> constructor = alphaTestClass.getConstructor(function.getClass(), float.class);
            return constructor.newInstance(function, cooparticlesapi$PARTICLE_ALPHA_THRESHOLD);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
