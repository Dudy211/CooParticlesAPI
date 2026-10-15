package cn.coostack.cooparticlesapi.dbg

/**
 * 渲染诊断开关 —— **保留的工具箱**（2026-10-03 加，2026-10-05 定稿保留）。
 *
 * 由**游戏内指令**控制（原本的 JVM 属性要改启动器 + 重启，试错成本太高）：
 *
 * ```
 * /cooparticles debug                      查看当前状态
 * /cooparticles debug cull  <true|false>   关闭背面剔除
 * /cooparticles debug depth <true|false>   关闭深度测试
 * /cooparticles debug view  <模式>          切换 uView 构造方式（见 [ViewMode]）
 * /cooparticles debug reset                全部恢复默认
 * ```
 *
 * ⚠️ 这些开关**默认全是关的**，不敲指令就零影响、零开销 —— 所以正式版保留。
 * 以后再遇到「粒子提交了但看不见」，直接敲指令就能二分定位，不必重新加探针。
 *
 * <h2>[ViewMode] 的意义</h2>
 *
 * shader 是 `gl_Position = uProj * uView * vec4(rel, 1)`，`rel = 粒子世界坐标 - 相机位置`。
 *
 * ★ 2026-10-05 结论：**主路径已固定用 [ViewMode.MODELVIEW]**，这一项只是留作对照。
 *   九月底那次「粒子看不见」的真凶是**探针采样时机错误**（在函数入口读
 *   `RenderSystem.getModelViewMatrix()` 得到单位阵，但 `render()` 调用在函数**末尾**，
 *   那时全局 modelViewStack 已填充），不是 view 矩阵本身有问题 —— 详见
 *   `deliver/mc/README.txt` 的 ROLLBACK 记录。
 *
 * 历史实测（2026-10-03，用于理解各模式差异）：
 * - `IDENTITY` → 能看到法阵（形状/颜色都对），但固定在屏幕上随视角移动
 * - `R180_INV` → 完全看不到
 * - `MODELVIEW` → ✅ 正确（9-30 基线 + FIX9 收口版本）
 */
object CParticleRenderDiag {

    /**
     * `uView` 的构造方式候选。
     *
     * 记 `R = camera.rotation()`（该四元数把 (0,0,1) 送到相机世界前向，
     * 见 `Camera.setRotation`），`R⁻¹` 为其逆，`Ry180` 为绕世界 Y 轴 180°。
     */
    enum class ViewMode {
        /** `Ry(180)·R⁻¹`。当前默认（推测方向，但实测**完全看不到**）。 */
        R180_INV,

        /** `R⁻¹`。世界 → 相机的标准逆旋转（不含额外 180°）。 */
        INV,

        /** `R`。正旋转（2026-10-03 首测时主人反馈「跟屏幕旋转反着来」）。 */
        R,

        /** 单位阵。实测**能看到法阵**但固定在屏幕上。 */
        IDENTITY,

        /** `RenderSystem.getModelViewMatrix()`。9-30 基线；实测**面朝 -Z 时正确**、其它朝向缺相机旋转。 */
        MODELVIEW,
        ;

        /** 指令里用的短名。 */
        val token: String
            get() = when (this) {
                R180_INV -> "r180inv"
                INV -> "inv"
                R -> "r"
                IDENTITY -> "identity"
                MODELVIEW -> "modelview"
            }
    }

    /** 关闭背面剔除（`GL_CULL_FACE`）。 */
    @JvmStatic
    var disableCull: Boolean = false

    /** 关闭深度测试（`GL_DEPTH_TEST`）。粒子会画在所有东西前面。 */
    @JvmStatic
    var disableDepth: Boolean = false

    /** `uView` 构造方式。 */
    @JvmStatic
    var viewMode: ViewMode = ViewMode.R180_INV

    /** 兼容旧调用点：`identity` 等价于 [ViewMode.IDENTITY]。 */
    @JvmStatic
    var identityView: Boolean
        get() = viewMode == ViewMode.IDENTITY
        set(value) {
            viewMode = if (value) ViewMode.IDENTITY else ViewMode.R180_INV
        }

    /** 恢复全部默认值。 */
    @JvmStatic
    fun reset() {
        disableCull = false
        disableDepth = false
        viewMode = ViewMode.R180_INV
    }

    /** 按 [ViewMode] 解析出指令里的短名；无法识别返回 `null`。 */
    @JvmStatic
    fun parseViewMode(token: String): ViewMode? =
        ViewMode.entries.firstOrNull { it.token.equals(token, ignoreCase = true) }

    /** 全部可用的 view 模式短名，供指令提示与回显。 */
    @JvmStatic
    fun viewModeTokens(): String = ViewMode.entries.joinToString("|") { it.token }

    /** 当前状态摘要，供指令回显。 */
    @JvmStatic
    fun describe(): String {
        return "cull=" + (if (disableCull) "OFF" else "ON") +
            " depth=" + (if (disableDepth) "OFF" else "ON") +
            " view=" + viewMode.name
    }
}
