package cn.coostack.cooparticlesapi.mixin;

import cn.coostack.cooparticlesapi.CooParticlesConstants;
import cn.coostack.cooparticlesapi.particles.ControlableParticle;
import cn.coostack.cooparticlesapi.particles.CooParticleTextureSheet;
import cn.coostack.cooparticlesapi.particles.control.RemoveReason;
import cn.coostack.cooparticlesapi.platform.CooParticlesServices;
import com.google.common.collect.EvictingQueue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleGroup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * @author CooStack
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Final
    @Shadow
    private Map<ParticleRenderType, Queue<Particle>> particles;

    @Final
    @Shadow
    private Queue<Particle> particlesToAdd;

    @Shadow
    protected abstract void updateCount(ParticleGroup group, int count);

    @Shadow
    @Final
    @Mutable
    private static List<ParticleRenderType> RENDER_ORDER;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;"))
    public Object changeMaxParticles(Queue<Object> queue) {

        Particle particle;
        int limit = CooParticlesServices.API_CONFIG_MANAGER.getConfig().getParticleCountLimit();
        while ((particle = particlesToAdd.poll()) != null) {
            Queue<Particle> queue1 = particles.computeIfAbsent(particle.getRenderType(),
                    sheet -> EvictingQueue.create(limit));

            if (queue1.size() == limit) {

                Particle poll = queue1.poll();
                if (poll != null) {
                    cooParticlesAPI$onEvict(poll);
                }
            }
            queue1.add(particle);
        }
        return null;
    }

    @Inject(method = "clearParticles", at = @At("HEAD"))
    public void onClear(CallbackInfo ci) {

        particles.values().forEach(q -> q.forEach((p) -> {
            if (p != null) {
                cooParticlesAPI$onEvict(p);
            }
        }));
        particlesToAdd.forEach((p) -> {
            if (p != null) {
                cooParticlesAPI$onEvict(p);
            }
        });

        CooParticlesConstants.logger.info("ParticleManager: clearParticles invoked");
    }

    @Unique
    private void cooParticlesAPI$onEvict(Particle p) {



        if (p.isAlive()) {
            if (p instanceof ControlableParticle) {
                ((ControlableParticle) p).getControler().remove(RemoveReason.QUEUE);
            } else {
                p.remove();
            }
            p.getParticleGroup().ifPresent(group -> updateCount(group, -1));
        }
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void onStaticRenderOrderInit(CallbackInfo ci) {

        var newOrder = new ArrayList<>(RENDER_ORDER);
        newOrder.addAll(
                CooParticleTextureSheet.getSheets()
        );

        RENDER_ORDER = newOrder;
    }

}
