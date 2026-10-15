package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.client.particle.Particle

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import org.joml.Quaternionf

/**
 * 1.20.1 兼容层：FacingCameraMode。
 *
 * 1.21 的 `Particle` 有 `facingCameraMode` 字段（枚举 DEFAULT/LOOKAT_XYZ/...），
 * 用它计算粒子的朝向四元数。1.20.1 用 `Particle.render` 里直接的内联逻辑，
 * 没有这个抽象。
 *
 * 上游用法（ControlableParticle.kt:491）：
 *   `this.facingCameraMode.setRotation(q, camera, tickDelta)`
 *
 * 这里提供等价接口：DEFAULT 模式下 billboard 朝向相机即可。
 */
enum class FacingCameraMode {
    /** 默认：面向相机（billboard） */
    DEFAULT,

    /** 朝向相机但不做 Y 轴补正 */
    LOOKAT_XYZ,

    /** 平躺（仅 Y 轴旋转） */
    LOOKAT_Y,

    /** 朝向相机位置的反向 */
    LOOKAT_DIRECTION,
    ;

    /**
     * 把朝向写入给定四元数。
     *
     * 1.20.1 的等价做法：用相机朝向构造 quaternionf。
     */
    fun setRotation(rotation: Quaternionf, camera: Camera, partialTicks: Float) {
        when (this) {
            DEFAULT, LOOKAT_XYZ -> {
                rotation.set(camera.rotation())
            }
            LOOKAT_Y -> {

                val yaw = camera.yRot
                rotation.setAngleAxis(Math.toRadians(-yaw.toDouble()), 0.0, 1.0, 0.0)
            }
            LOOKAT_DIRECTION -> {
                rotation.set(camera.rotation())
            }
        }
    }
}

/**
 * 1.20.1 兼容层：给 Particle 补 facingCameraMode 属性。
 *
 * 用 ThreadLocal 存一份「每粒子」的朝向模式（默认 DEFAULT），
 * 语义上与 1.21 的字段一致，且改动最小。
 */
private val facingModes = java.util.WeakHashMap<Particle, FacingCameraMode>()

var Particle.facingCameraMode: FacingCameraMode
    get() = facingModes[this] ?: FacingCameraMode.DEFAULT
    set(value) {
        facingModes[this] = value
    }
