package cn.coostack.cooparticlesapi.utils

import cn.coostack.cooparticlesapi.utils.builder.FourierSeriesBuilder
import org.joml.Vector2d
import org.joml.Vector2i
import java.awt.image.BufferedImage
import kotlin.math.*

/**
 *
 * 欸 爱 神 力 Made By ChatGPT 5.2
 *
 * 多轮廓版：图片(透明通道) -> 所有连通块轮廓 -> 每个轮廓生成一个 FourierSeriesBuilder
 *
 * 输出点在 XZ 平面：
 * - x = (pixelX - centerX) * step
 * - z = (centerY - pixelY) * step
 *
 * 复数用 Vector2d 表示：x=Re(实部), y=Im(虚部)
 */
object FourierPhotoUtil {





    /**
     * 单轮廓：只取“最大连通块”生成 builder（适合你想要一个主体的时候）
     */
    fun toFourierBuilder(
        image: BufferedImage,
        alphaThreshold: Int = 1,
        sampleCount: Int = 1024,
        harmonics: Int = 150,
        step: Double = 1.0,
        centerMode: Boolean = true,
        sortByAmplitude: Boolean = true
    ): FourierSeriesBuilder {
        val builders = toFourierBuilders(
            image, alphaThreshold, sampleCount, harmonics, step, centerMode, sortByAmplitude
        )
        require(builders.isNotEmpty()) { "未找到任何轮廓：请检查图片透明通道/alphaThreshold=$alphaThreshold" }

        return builders.first()
    }

    /**
     * 多轮廓：返回每个连通块一个 FourierSeriesBuilder
     *
     * @param maxComponents 限制最多处理多少个连通块（防止噪点太多），默认 16
     * @param minComponentPixels 过滤太小的连通块（比如抗锯齿噪点），默认 20
     */
    fun toFourierBuilders(
        image: BufferedImage,
        alphaThreshold: Int = 1,
        sampleCount: Int = 1024,
        harmonics: Int = 150,
        step: Double = 1.0,
        centerMode: Boolean = true,
        sortByAmplitude: Boolean = true,
        maxComponents: Int = 16,
        minComponentPixels: Int = 20
    ): List<FourierSeriesBuilder> {
        val mask = buildSolidMask(image, alphaThreshold)
        val components = findConnectedComponents(mask, image.width, image.height, minComponentPixels)
            .sortedByDescending { it.size }
            .take(maxComponents)

        val builders = ArrayList<FourierSeriesBuilder>(components.size)

        for (comp in components) {
            val contour = extractOrderedContourFromComponent(
                width = image.width,
                height = image.height,
                componentPixels = comp
            )


            if (contour.size < 8) continue

            val resampled = resampleClosedPath(contour, sampleCount)
            val samples = mapToXZ(resampled, image.width, image.height, step, centerMode)

            val coeffs = dft(samples)
            val series = coeffsToFouriers(coeffs, harmonics, sortByAmplitude)

            val builder = FourierSeriesBuilder()
                .count(sampleCount)
                .scale(1.0)

            series.forEach { f ->
                builder.addFourier(r = f.r, w = f.w, startAngle = f.startAngle)
            }
            builders.add(builder)
        }

        return builders
    }

    /**
     * 多轮廓：返回每个连通块一个 FourierSeriesBuilder，并返回该连通块相对于“全图中心”的 offset（XZ平面）
     *
     * key   = offset(RelativeLocation)  -> (offX, 0, offZ)
     * value = FourierSeriesBuilder      -> build() 得到的是“以连通块自身中心为原点”的局部点
     *
     * 渲染时用：pos = origin + offset + point
     */
    fun toFourierBuildersWithOffset(
        image: BufferedImage,
        alphaThreshold: Int = 1,
        sampleCount: Int = 1024,
        harmonics: Int = 150,
        step: Double = 1.0,
        sortByAmplitude: Boolean = true,
        maxComponents: Int = 16,
        minComponentPixels: Int = 20
    ): Map<RelativeLocation, FourierSeriesBuilder> {

        val mask = buildSolidMask(image, alphaThreshold)
        val components = findConnectedComponents(mask, image.width, image.height, minComponentPixels)
            .sortedByDescending { it.size }
            .take(maxComponents)


        val result = LinkedHashMap<RelativeLocation, FourierSeriesBuilder>(components.size)


        val globalCx = image.width / 2.0
        val globalCy = image.height / 2.0

        for (comp in components) {
            val contour = extractOrderedContourFromComponent(
                width = image.width,
                height = image.height,
                componentPixels = comp
            )
            if (contour.size < 8) continue


            val (compCx, compCy) = computeComponentCenterPx(comp, image.width)


            val offX = (compCx - globalCx) * step
            val offZ = (globalCy - compCy) * step
            val offset = RelativeLocation(offX, 0.0, offZ)



            val resampled = resampleClosedPath(contour, sampleCount)
            val localSamples = resampled.map { p ->
                val x = (p.x - compCx) * step
                val z = (compCy - p.y) * step
                Vector2d(x, z)
            }

            val coeffs = dft(localSamples)
            val series = coeffsToFouriers(coeffs, harmonics, sortByAmplitude)

            val builder = FourierSeriesBuilder()
                .count(sampleCount)
                .scale(1.0)

            series.forEach { f ->
                builder.addFourier(r = f.r, w = f.w, startAngle = f.startAngle)
            }

            result[offset] = builder
        }

        return result
    }

    /**
     * 计算连通块中心（像素坐标）
     * compPixels: IntArray 存的是 y*w + x
     */
    private fun computeComponentCenterPx(compPixels: IntArray, w: Int): Pair<Double, Double> {
        var sx = 0.0
        var sy = 0.0
        for (p in compPixels) {
            sx += (p % w).toDouble()
            sy += (p / w).toDouble()
        }
        val n = compPixels.size.coerceAtLeast(1)
        return Pair(sx / n, sy / n)
    }

    /**
     * 多轮廓：直接得到点（按轮廓分组）
     */
    fun toFourierPointsMulti(
        image: BufferedImage,
        alphaThreshold: Int = 1,
        sampleCount: Int = 1024,
        harmonics: Int = 150,
        step: Double = 1.0,
        centerMode: Boolean = true,
        sortByAmplitude: Boolean = true,
        maxComponents: Int = 16,
        minComponentPixels: Int = 20
    ): List<List<RelativeLocation>> {
        return toFourierBuilders(
            image = image,
            alphaThreshold = alphaThreshold,
            sampleCount = sampleCount,
            harmonics = harmonics,
            step = step,
            centerMode = centerMode,
            sortByAmplitude = sortByAmplitude,
            maxComponents = maxComponents,
            minComponentPixels = minComponentPixels
        ).map { it.build() }
    }





    private fun buildSolidMask(image: BufferedImage, alphaThreshold: Int): BooleanArray {
        val w = image.width
        val h = image.height
        val out = BooleanArray(w * h)

        val hasAlpha = image.colorModel.hasAlpha()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val solid = if (!hasAlpha) {
                    true
                } else {
                    val pixel = image.getRGB(x, y)
                    val a = (pixel ushr 24) and 0xFF
                    a > alphaThreshold
                }
                out[y * w + x] = solid
            }
        }
        return out
    }





    private fun findConnectedComponents(
        mask: BooleanArray,
        w: Int,
        h: Int,
        minPixels: Int
    ): List<IntArray> {
        val visited = BooleanArray(mask.size)
        val comps = ArrayList<IntArray>()

        val neighbors = intArrayOf(
            -1, 0, 1, 0, 0, -1, 0, 1,
            -1, -1, 1, -1, -1, 1, 1, 1
        )

        fun inBounds(x: Int, y: Int) = x in 0 until w && y in 0 until h
        fun idx(x: Int, y: Int) = y * w + x

        val queue = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val start = idx(x, y)
            if (!mask[start] || visited[start]) continue

            var qs = 0
            var qe = 0
            queue[qe++] = start
            visited[start] = true

            val pixels = ArrayList<Int>(256)

            while (qs < qe) {
                val cur = queue[qs++]
                pixels.add(cur)
                val cx = cur % w
                val cy = cur / w

                var n = 0
                while (n < neighbors.size) {
                    val nx = cx + neighbors[n]
                    val ny = cy + neighbors[n + 1]
                    n += 2
                    if (!inBounds(nx, ny)) continue
                    val ni = idx(nx, ny)
                    if (visited[ni] || !mask[ni]) continue
                    visited[ni] = true
                    queue[qe++] = ni
                }
            }

            if (pixels.size >= minPixels) {
                val arr = IntArray(pixels.size)
                for (i in pixels.indices) arr[i] = pixels[i]
                comps.add(arr)
            }
        }

        return comps
    }





    private fun extractOrderedContourFromComponent(
        width: Int,
        height: Int,
        componentPixels: IntArray
    ): List<Vector2i> {


        val compMask = BooleanArray(width * height)
        for (p in componentPixels) compMask[p] = true

        fun isSolid(x: Int, y: Int): Boolean {
            if (x !in 0 until width || y !in 0 until height) return false
            return compMask[y * width + x]
        }


        var start: Vector2i? = null
        for (p in componentPixels) {
            val x = p % width
            val y = p / width
            if (isEdgePixel(x, y, ::isSolid)) {
                start = Vector2i(x, y)
                break
            }
        }
        if (start == null) return emptyList()

        return traceMoore(start!!, ::isSolid, width, height)
    }

    private fun isEdgePixel(x: Int, y: Int, isSolid: (Int, Int) -> Boolean): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (!isSolid(x + dx, y + dy)) return true
        }
        return false
    }

    /**
     * Moore-Neighbor tracing（简化版）：从 start 开始沿边缘走一圈
     */
    private fun traceMoore(
        start: Vector2i,
        isSolid: (Int, Int) -> Boolean,
        w: Int,
        h: Int
    ): List<Vector2i> {
        val dir = arrayOf(
            Vector2i(1, 0), Vector2i(1, 1), Vector2i(0, 1), Vector2i(-1, 1),
            Vector2i(-1, 0), Vector2i(-1, -1), Vector2i(0, -1), Vector2i(1, -1)
        )

        fun inside(p: Vector2i) = p.x in 0 until w && p.y in 0 until h

        var current = Vector2i(start)
        var backDirIndex = 4
        val contour = ArrayList<Vector2i>(4096)
        contour.add(Vector2i(current))

        val maxSteps = w * h * 4
        var steps = 0

        while (steps++ < maxSteps) {
            var foundNext: Vector2i? = null
            var foundDirIndex = -1

            for (i in 1..8) {
                val idx = (backDirIndex + i) and 7
                val nxt = Vector2i(current.x + dir[idx].x, current.y + dir[idx].y)
                if (!inside(nxt)) continue
                if (!isSolid(nxt.x, nxt.y)) continue
                foundNext = nxt
                foundDirIndex = idx
                break
            }

            if (foundNext == null) break

            val next = foundNext
            if (next.x == start.x && next.y == start.y && contour.size > 10) break

            contour.add(Vector2i(next))
            backDirIndex = (foundDirIndex + 4) and 7
            current.set(next)
        }

        return contour
    }





    private fun resampleClosedPath(path: List<Vector2i>, targetCount: Int): List<Vector2d> {
        val pts = path.map { Vector2d(it.x.toDouble(), it.y.toDouble()) }
        if (pts.size <= targetCount) return pts

        val n = pts.size
        val dist = DoubleArray(n + 1)
        dist[0] = 0.0
        for (i in 1..n) {
            val a = pts[i - 1]
            val b = pts[i % n]
            dist[i] = dist[i - 1] + hypot(b.x - a.x, b.y - a.y)
        }

        val total = dist[n]
        val step = total / targetCount

        val out = ArrayList<Vector2d>(targetCount)
        var seg = 0
        for (k in 0 until targetCount) {
            val d = k * step
            while (seg + 1 <= n && dist[seg + 1] < d) seg++

            val a = pts[seg % n]
            val b = pts[(seg + 1) % n]
            val da = dist[seg]
            val db = dist[seg + 1]
            val t = if (db - da <= 1e-9) 0.0 else (d - da) / (db - da)

            out.add(
                Vector2d(
                    a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t
                )
            )
        }
        return out
    }






    private fun mapToXZ(
        pts: List<Vector2d>,
        width: Int,
        height: Int,
        step: Double,
        centerMode: Boolean
    ): List<Vector2d> {
        var cx = width / 2.0
        var cy = height / 2.0

        if (centerMode) {
            var sx = 0.0
            var sy = 0.0
            pts.forEach { p -> sx += p.x; sy += p.y }
            cx = sx / pts.size
            cy = sy / pts.size
        }

        return pts.map { p ->
            val x = (p.x - cx) * step
            val z = (cy - p.y) * step
            Vector2d(x, z)
        }
    }







    private fun dft(samples: List<Vector2d>): Array<Vector2d> {
        val n = samples.size
        val out = Array(n) { Vector2d(0.0, 0.0) }

        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            val coef = -2.0 * Math.PI * k / n

            for (i in 0 until n) {
                val angle = coef * i
                val c = cos(angle)
                val s = sin(angle)
                val x = samples[i]

                re += x.x * c - x.y * s
                im += x.x * s + x.y * c
            }

            out[k].set(re / n, im / n)
        }
        return out
    }









    private fun coeffsToFouriers(
        coeffs: Array<Vector2d>,
        harmonics: Int,
        sortByAmplitude: Boolean
    ): List<FourierSeriesBuilder.Fourier> {
        val n = coeffs.size

        fun kToW(k: Int): Int = if (k <= n / 2) k else k - n

        val all = ArrayList<FourierSeriesBuilder.Fourier>(n)
        for (k in 0 until n) {
            val w = kToW(k).toDouble()
            val c = coeffs[k]
            val r = hypot(c.x, c.y)
            val angRad = atan2(c.y, c.x)
            val startAngleDeg = Math.toDegrees(angRad)
            all.add(FourierSeriesBuilder.Fourier(w = w, r = r, startAngle = startAngleDeg))
        }

        val zero = all.firstOrNull { it.w == 0.0 } ?: FourierSeriesBuilder.Fourier(0.0, 0.0, 0.0)
        val others = all.filter { it.w != 0.0 }

        val selected = if (sortByAmplitude) {
            others.sortedByDescending { it.r }.take(harmonics)
        } else {
            val h = harmonics.coerceAtMost(n / 2 - 1).coerceAtLeast(1)
            val freqList = ArrayList<FourierSeriesBuilder.Fourier>(2 * h)
            for (w in -h..-1) freqList.add(all.first { it.w == w.toDouble() })
            for (w in 1..h) freqList.add(all.first { it.w == w.toDouble() })
            freqList
        }

        return buildList {
            add(zero)
            addAll(selected)
        }
    }
}
