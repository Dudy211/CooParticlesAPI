package cn.coostack.cooparticlesapi.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link KeyMapping} 的 {@code clickCount} 字段。
 *
 * <p>⚠️ 只保留这一个 —— 静态 final 的 ALL/MAP 用 @Accessor 定位不到
 * (reobf 后签名不匹配),那部分逻辑已改用公开 API(见 KeyMappingMixin)。
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("clickCount")
    int cooparticlesapi$getClickCount();

    @Accessor("clickCount")
    void cooparticlesapi$setClickCount(int clickCount);
}
