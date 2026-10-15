package cn.coostack.cooparticlesapi.mixin.events.entity.player;

import cn.coostack.cooparticlesapi.event.CooEventBus;
import cn.coostack.cooparticlesapi.event.events.entity.player.PlayerItemDestroyEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 玩家物品损坏事件 —— 从上游 `neoforge/` 侧的 `ItemStackMixinNeoForge` 搬来。
 *
 * ⚠️ 1.20.1 差异：`ItemStack.hurtAndBreak` 是 **3 参**
 * `(int, LivingEntity, Consumer)`；上游（1.21）是 4 参，多了 `ServerLevel`。
 */
@Mixin(ItemStack.class)
public class ItemStackMixinForge {

    @Inject(
            method = "hurtAndBreak",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;setDamageValue(I)V")
    )
    private void cooparticlesapi$onBreak(
            int amount,
            LivingEntity entity,
            Consumer<Item> onBreak,
            CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) {
            return;
        }
        ItemStack stack = (ItemStack) (Object) this;
        CooEventBus.call(new PlayerItemDestroyEvent(player, stack.copy()));
    }
}
