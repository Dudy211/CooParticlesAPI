package cn.coostack.cooparticlesapi.mixin.events.entity;

import cn.coostack.cooparticlesapi.event.CooEventBus;
import cn.coostack.cooparticlesapi.event.events.entity.EntityMoveEvent;
import cn.coostack.cooparticlesapi.event.events.entity.EntityPreMoveEvent;
import cn.coostack.cooparticlesapi.event.events.entity.EntityPostTickEvent;
import cn.coostack.cooparticlesapi.event.events.entity.EntityPreTickEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 modifyMovePos(Vec3 original) {
        Entity entity = (Entity) (Object) this;

        var event = new EntityPreMoveEvent(entity, original);
        CooEventBus.call(event);
        if (event.isCancelled()) {
            return Vec3.ZERO;
        }
        return event.getMovement();
    }

    /**
     * 1.20.1 Forge 移植改动：@ModifyArgs -> @Redirect。
     *
     * @ModifyArgs 需要 Mixin 在转换期生成
     * `org.spongepowered.asm.synthetic.args.Args$N`，但它落在 Mixin 自己的
     * 安全模块里，Forge 的 JPMS 类加载器注入不进去 —— 启动阶段直接
     * `NoClassDefFoundError: org/spongepowered/asm/synthetic/args/Args$1`。
     *
     * @Redirect 不生成新类，语义等价（拦截 Vec3 的三参构造）。
     */
    @Redirect(
            method = "setPosRaw",
            at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 onEntityMove(double x, double y, double z) {
        Entity entity = (Entity) (Object) this;
        Vec3 moveTo = new Vec3(x, y, z);
        Vec3 movement = moveTo.subtract(entity.position());
        var event = new EntityMoveEvent(entity, movement, moveTo);
        CooEventBus.call(event);
        return event.isCancelled() ? entity.position() : event.getMoveTo();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTickStart(CallbackInfo ci) {

        Entity entity = (Entity) (Object) this;
        var event = new EntityPreTickEvent(entity);
        CooEventBus.call(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void onTickEnd(CallbackInfo ci) {

        Entity entity = (Entity) (Object) this;
        var event = new EntityPostTickEvent(entity);
        CooEventBus.call(event);
    }
}
