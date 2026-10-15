package cn.coostack.cooparticlesapi.platform

import cn.coostack.cooparticlesapi.utils.api.ModelPartPointCollector
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Forge 1.20.1 的 ModelPart 采样点收集器。
 *
 * 用途：把实体模型（ModelPart 树）按三角形采样成世界坐标点列，
 * 供粒子沿模型表面生成。
 *
 * 算法与上游 NeoForge 版一致：递归遍历 ModelPart 的 cube 列表，
 * 对每个面按 density 采样，再用 PoseStack 的矩阵变换到世界空间。
 *
 * ⚠️ 1.20.1 的 `ModelPart.cubes` / `children` 与 `Cube.minX..maxZ` 都是 private，
 * 靠 `accesstransformer.cfg` 打开后**直接访问**。
 * **不要改回 `getDeclaredField(...)` 反射**：生产环境 reobf 后字段名是 `f_104212_` 等，
 * 反射必然抛 NoSuchFieldException（每帧上千次），是「进世界即崩 PoseStack not empty」的成因之一。
 */
class ForgeModelPartPointCollector : ModelPartPointCollector {

    override fun collectSamplePoints(
        root: ModelPart,
        poseStack: PoseStack,
        density: Int,
        pixelToUnit: Boolean,
    ): List<Vec3> {
        val out = ArrayList<Vec3>(4096)
        val matrix: Matrix4f = poseStack.last().pose()

        fun emit(x: Float, y: Float, z: Float) {
            val v = Vector3f(x, y, z)
            if (pixelToUnit) v.mul(1f / 16f)
            matrix.transformPosition(v)
            out.add(Vec3(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()))
        }

        /** 一个矩形面按 density×density 采样 */
        fun sampleQuad(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
        ) {
            val n = density.coerceIn(1, 64)
            for (i in 0..n) {
                val u = i.toFloat() / n
                for (j in 0..n) {
                    val v = j.toFloat() / n

                    val ax = x0 + (x1 - x0) * u
                    val ay = y0 + (y1 - y0) * u
                    val az = z0 + (z1 - z0) * u
                    val bx = x3 + (x2 - x3) * u
                    val by = y3 + (y2 - y3) * u
                    val bz = z3 + (z2 - z3) * u
                    emit(
                        ax + (bx - ax) * v,
                        ay + (by - ay) * v,
                        az + (bz - az) * v,
                    )
                }
            }
        }

        fun walk(part: ModelPart) {

            for (cube in part.cubes) {
                val x0 = cube.minX; val y0 = cube.minY; val z0 = cube.minZ
                val x1 = cube.maxX; val y1 = cube.maxY; val z1 = cube.maxZ


                sampleQuad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
                sampleQuad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0)
                sampleQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
                sampleQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0)
                sampleQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1)
                sampleQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0)
            }


            for (child in part.children.values) {
                walk(child)
            }
        }

        walk(root)
        return out
    }
}
