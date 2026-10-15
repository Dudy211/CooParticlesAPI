package cn.coostack.cooparticlesapi.entities.renderer

import cn.coostack.cooparticlesapi.compat.mc1201.normalAt
import cn.coostack.cooparticlesapi.compat.mc1201.forFormat
import cn.coostack.cooparticlesapi.compat.mc1201.vertexAt
import cn.coostack.cooparticlesapi.compat.mc1201.setWhiteAlpha
import cn.coostack.cooparticlesapi.compat.mc1201.setLightCompat
import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.entities.TestRenderEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.lighting.LightEngine
import kotlin.math.sin

class TestRenderEntityRenderer(context: EntityRendererProvider.Context) : EntityRenderer<TestRenderEntity>(context) {
    override fun getTextureLocation(location: TestRenderEntity): ResourceLocation {
        return ResourceLocation.fromNamespaceAndPath(CooParticlesConstants.MOD_ID, "NONE")
    }

    override fun render(
        entity: TestRenderEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        poseStack.pushPose()




        val lineType: RenderType = RenderType.LINES as RenderType
        val buffer = bufferSource.getBuffer(lineType).forFormat(lineType.format())
        val pose = poseStack.last()
        buffer.vertexAt(
            pose.pose(), 0f,
            sin(Math.toRadians((entity.level().gameTime.toDouble() + partialTick))).toFloat(),
            0f
        )
            .normalAt(pose.normal(), 0f, 1f, 0f)
            .color(255, 255, 0, 255)
            .setLightCompat(LightEngine.MAX_LEVEL)
        buffer.vertexAt(pose.pose(), 1f, 1f, 1f)
            .normalAt(pose.normal(), 0f, 1f, 0f)
            .color(0, 255, 255, 255)

        buffer.vertexAt(pose.pose(), 1f, 0f, 1f)
            .normalAt(pose.normal(), 0f, 1f, 0f)
            .color(0, 255, 255, 255)

        buffer.vertexAt(pose.pose(), 2f, 2f, 2f)
            .normal(1f, 1f, 1f)
            .color(0, 255, 255, 255)
            .setWhiteAlpha(110)

        poseStack.popPose()

    }
}