package cn.coostack.cooparticlesapi.compat.mc1201

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * 1.20.1 兼容层：**按顶点格式自动重排**的顶点写入器。
 *
 * ## 为什么需要它
 *
 * 上游是 1.21，那里 VertexConsumer 的写入是**无序**的：
 * 每个 setter 自己找到对应元素写入，endVertex 已被删除，
 * 顶点数据在下一个 vertex(...) 到来时提交。
 *
 * **1.20.1 完全是另一套语义**：
 *
 * 1. BufferVertexConsumer 的每个 setter 只看「当前元素」—— 对不上就
 *    **静默 return（既不写入也不推进）**；
 * 2. 推进元素**只发生在** endVertex() 里（BufferBuilder.endVertex 检查
 *    elementIndex == 0，不为 0 直接抛 Not filled all elements of the vertex，
 *    为 0 才调 nextElement()）；
 * 3. 所以 1.20.1 **必须**按 VertexFormat 声明的**精确顺序**写，每个顶点末尾补
 *    endVertex()。
 *
 * 上游代码（1.21 写法）是 vertex -> color -> uv -> overlayCoords -> uv2 -> normal，
 * 而 1.20.1 的 NEW_ENTITY 是 POSITION -> COLOR -> UV0 -> UV1 -> UV2 -> NORMAL。
 * 一执行就会发现：
 * - uv(...) 撞上 UV1 -> 忽略；
 * - overlayCoords(...) 撞上 UV2 -> 用 min(u,16) 写入（**污染真实光照**）；
 * - uv2(...)（真实光照）撞上 NORMAL -> 静默丢弃；
 * - normal(...) 撞上 PADDING -> 静默丢弃；
 * - endVertex() 时 elementIndex 停在 NORMAL != 0 -> **抛异常**。
 *
 * ## 做法
 *
 * 这个包装器**捕获**调用方按任意顺序写下的数据（一次一个顶点），
 * 在顶点结束时**按 VertexFormat 的真实元素顺序回放**。
 * 于是所有上游调用点（顺序是 1.21 的）在 1.20.1 上都能正确落盘。
 *
 * 约束：包装器会自己调 endVertex()，调用方不要再调（调了也幂等）。
 */
fun VertexConsumer.forFormat(format: VertexFormat): VertexConsumer =
    FormatOrderedVertexConsumer(this, format)

/**
 * 允许「收尾时把还没提交的那个顶点刷出去」。
 *
 * 场景：一次绘制写了奇数个顶点（例如 LINES 模式下少写一条线），
 * 最后一个顶点会一直滞留到下一次 `vertex()` 才提交；
 * 若这一帧没有下一次，它就会以「未完成顶点」的形式被 `endBatch` 撞上，
 * 原版会抛 `Not filled all elements of the vertex`。
 */
internal interface PendingVertexHolder {
    fun flushPending()
}

private class FormatOrderedVertexConsumer(
    private val delegate: VertexConsumer,
    private val format: VertexFormat,
) : VertexConsumer, PendingVertexHolder {

    private var px = 0f; private var py = 0f; private var pz = 0f
    private var hasPos = false

    private var cr = 255; private var cg = 255; private var cb = 255; private var ca = 255

    private var u0 = 0f; private var v0 = 0f
    private var u1 = 0; private var v1 = 0
    private var u2 = 0; private var v2 = 0

    private var nx = 0f; private var ny = 0f; private var nz = 0f

    private val elements: List<VertexFormatElement> = format.elements

    private fun flush() {
        for (el in elements) {
            when (el.usage) {
                VertexFormatElement.Usage.POSITION ->
                    delegate.vertex(px.toDouble(), py.toDouble(), pz.toDouble())
                VertexFormatElement.Usage.COLOR ->
                    delegate.color(cr, cg, cb, ca)
                VertexFormatElement.Usage.NORMAL ->
                    delegate.normal(nx, ny, nz)
                VertexFormatElement.Usage.UV -> when (el.index) {
                    0 -> delegate.uv(u0, v0)
                    1 -> delegate.overlayCoords(u1, v1)
                    2 -> delegate.uv2(u2, v2)
                    else -> {}
                }
                else -> {}
            }
        }
        delegate.endVertex()
        hasPos = false
    }

    private fun maybeFlush() {
        if (hasPos) flush()
    }

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
        maybeFlush()
        px = x.toFloat(); py = y.toFloat(); pz = z.toFloat(); hasPos = true
        return this
    }

    override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
        cr = r; cg = g; cb = b; ca = a
        return this
    }

    override fun uv(u: Float, v: Float): VertexConsumer {
        u0 = u; v0 = v
        return this
    }

    override fun overlayCoords(u: Int, v: Int): VertexConsumer {
        u1 = u; v1 = v
        return this
    }

    override fun uv2(u: Int, v: Int): VertexConsumer {
        u2 = u; v2 = v
        return this
    }

    override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
        nx = x; ny = y; nz = z
        return this
    }

    override fun endVertex() {
        maybeFlush()
    }

    override fun flushPending() {
        maybeFlush()
    }

    override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = delegate.defaultColor(r, g, b, a)

    override fun unsetDefaultColor() = delegate.unsetDefaultColor()
}

/**
 * 把一个 Matrix4f 变换应用到 (x,y,z) 再写顶点。
 *
 * 与上游 1.21 的 vertex(Matrix4f, ...) 语义一致：只做位置变换。
 * 1.20.1 的 vertex(double,double,double) 没有 w 分量概念 —— 对世界渲染用的
 * 仿射矩阵两者等价。
 */
fun VertexConsumer.vertexAt(matrix: Matrix4f, x: Float, y: Float, z: Float): VertexConsumer {
    val v = Vector3f(x, y, z)
    matrix.transformPosition(v)
    this.vertex(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
    return this
}

fun VertexConsumer.normalAt(matrix: Matrix3f, x: Float, y: Float, z: Float): VertexConsumer {
    val v = Vector3f(x, y, z)
    matrix.transform(v)
    if (v.lengthSquared() > 0f) v.normalize()
    this.normal(v.x, v.y, v.z)
    return this
}

fun VertexConsumer.vertexAt(pose: PoseStack.Pose, x: Float, y: Float, z: Float): VertexConsumer =
    this.vertexAt(pose.pose(), x, y, z)

fun VertexConsumer.normalAt(pose: PoseStack.Pose, x: Float, y: Float, z: Float): VertexConsumer =
    this.normalAt(pose.normal(), x, y, z)
