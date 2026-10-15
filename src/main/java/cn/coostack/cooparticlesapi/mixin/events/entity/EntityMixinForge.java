package cn.coostack.cooparticlesapi.mixin.events.entity;

import cn.coostack.cooparticlesapi.event.CooEventBus;
import cn.coostack.cooparticlesapi.event.events.entity.EntityUnloadType;
import cn.coostack.cooparticlesapi.event.events.entity.client.ClientEntityLoadEvent;
import cn.coostack.cooparticlesapi.event.events.entity.client.ClientEntityUnloadEvent;
import cn.coostack.cooparticlesapi.event.events.entity.server.ServerEntityLoadEvent;
import cn.coostack.cooparticlesapi.event.events.entity.server.ServerEntityUnloadEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体加载 / 卸载事件 —— 从上游 `neoforge/` 侧的 `EntityMixinNeoForge` 搬来
 * （原来整个平台侧目录没搬，所以这些 CooEvent 从来没派发过）。
 *
 * ⚠️ 1.20.1 差异：`Entity.setLevel(Level)` 是 **protected**（1.21 起才是 public），
 * 所以这里用 `(Lnet/minecraft/world/level/Level;)V` 描述符定位，不写 targets。
 */
@Mixin(Entity.class)
public class EntityMixinForge {

    @Inject(method = "setLevel(Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"))
    private void cooparticlesapi$onSetLevel(Level level, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (level == null) {
            return;
        }
        if (level.isClientSide) {
            CooEventBus.call(new ClientEntityLoadEvent(entity));
        } else {
            CooEventBus.call(new ServerEntityLoadEvent(entity));
        }
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void cooparticlesapi$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        Level level = entity.level();
        if (level == null) {
            return;
        }
        EntityUnloadType unloadType = switch (reason) {
            case KILLED -> EntityUnloadType.ENTITY_DEATH;
            case DISCARDED -> EntityUnloadType.ENTITY_DISCARD;
            case UNLOADED_TO_CHUNK -> EntityUnloadType.CHUNK_UNLOAD;
            case UNLOADED_WITH_PLAYER -> EntityUnloadType.QUIT;
            case CHANGED_DIMENSION -> EntityUnloadType.WORLD_CHANGE;
        };
        if (level.isClientSide) {
            CooEventBus.call(new ClientEntityUnloadEvent(entity, unloadType));
        } else {
            CooEventBus.call(new ServerEntityUnloadEvent(entity, unloadType));
        }
    }
}
