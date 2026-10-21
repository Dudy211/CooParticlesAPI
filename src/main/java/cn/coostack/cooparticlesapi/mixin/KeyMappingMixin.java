package cn.coostack.cooparticlesapi.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让「绑到同一个键」的多个 KeyMapping 状态保持同步。
 *
 * <h2>★ 2026-10-21 重写说明</h2>
 *
 * 原实现用 `@Shadow` 直接读 {@code KeyMapping.ALL} / {@code KeyMapping.MAP} 两个私有静态 Map。
 * 但这两个字段在目标类里是 {@code private static final},而 Mixin 的 {@code attachFields}
 * 要求 @Shadow 字段修饰符**完全一致**;reobf 后字节码里没有 ACC_FINAL,
 * 于是启动直接崩:
 * <pre>
 *   InvalidMixinException: @Shadow field f_90810_ was not located in the target class
 * </pre>
 * 改用 `@Accessor` 也定位不到(静态 final 字段在 reobf 后签名不匹配)。
 *
 * <p><b>现在的做法</b>:完全不碰私有字段 —— 用 1.20.1 的公开 API
 * {@link KeyMapping#m_90850_(KeyMapping)}(= {@code same(KeyMapping)}) 判断两个 mapping
 * 是否绑定到同一个键,遍历集合则用 {@code Minecraft.options.keyMappings}(公开数组)。
 * 语义与原来一致,但没有任何反射/Shadow 依赖,不会再因为修饰符不匹配而崩。
 */
@Mixin(KeyMapping.class)
public class KeyMappingMixin {

    @Inject(method = "click", at = @At("TAIL"))
    private static void cooparticlesapi$broadcastClick(InputConstants.Key key, CallbackInfo ci) {
        cooparticlesapi$forEachAdditionalMappingWithKey(key, KeyMappingMixin::cooparticlesapi$incrementClickCount);
    }

    @Inject(method = "set", at = @At("TAIL"))
    private static void cooparticlesapi$broadcastSet(InputConstants.Key key, boolean isDown, CallbackInfo ci) {
        cooparticlesapi$forEachAdditionalMappingWithKey(key, mapping -> mapping.setDown(isDown));
    }

    /**
     * 遍历所有「除主 mapping 外、还绑在同一个键上」的 mapping。
     *
     * <p>主 mapping 由原版按 {@code MAP} 取到并处理,这里只补那些被「抢键」的。
     */
    private static void cooparticlesapi$forEachAdditionalMappingWithKey(
        InputConstants.Key key,
        Consumer<KeyMapping> action
    ) {
        if (key.getValue() == InputConstants.UNKNOWN.getValue()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (mapping == null) {
                continue;
            }
            if (!cooparticlesapi$usesKey(mapping, key)) {
                continue;
            }
            action.accept(mapping);
        }
    }

    /** 这个 mapping 是否绑在指定键上。 */
    private static boolean cooparticlesapi$usesKey(KeyMapping mapping, InputConstants.Key key) {
        if (key.getValue() == InputConstants.UNKNOWN.getValue()) {
            return false;
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return mapping.matchesMouse(key.getValue());
        }
        if (key.getType() == InputConstants.Type.SCANCODE) {
            return mapping.matches(InputConstants.UNKNOWN.getValue(), key.getValue());
        }
        return mapping.matches(key.getValue(), 0);
    }

    private static void cooparticlesapi$incrementClickCount(KeyMapping mapping) {
        KeyMappingAccessor accessor = (KeyMappingAccessor) mapping;
        accessor.cooparticlesapi$setClickCount(accessor.cooparticlesapi$getClickCount() + 1);
    }
}
