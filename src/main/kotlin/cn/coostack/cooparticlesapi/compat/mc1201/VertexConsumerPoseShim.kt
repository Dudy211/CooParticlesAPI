package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer

import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * 1.20.1 兼容层：带矩阵的顶点写入 —— **已迁到 [VertexBuilderCompat.kt]**。
 *
 * 这里只保留一个转发注释，真正的实现（含按格式重排的关键逻辑）见同名包下的
 * `forFormat(...)` / `vertexAt(...)` / `normalAt(...)`。
 *
 * 历史：本文件最早直接把 1.21 的调用顺序照搬到 1.20.1，导致
 * `Not filled all elements of the vertex` / `IndexOutOfBoundsException`，
 * 详见 VertexBuilderCompat.kt 的类注释。
 */
