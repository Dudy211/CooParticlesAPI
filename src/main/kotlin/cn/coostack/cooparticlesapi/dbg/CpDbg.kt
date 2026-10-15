package cn.coostack.cooparticlesapi.dbg

/**
 * 弹幕/渲染链路诊断探针。
 *
 * ★ 2026-10-05：改为「保留能力，默认关闭」。
 *
 * 之前（2026-09-30 ~ 10-03）是硬编码输出，定位完「有声音没画面」和 uView 那两个
 * 大坑之后，13 个业务类里仍留着 26 个探针 key —— 正式版会持续刷日志。
 *
 * 现在的行为：
 *  - [ENABLED] 默认 **false** → 全部探针零输出、零字符串拼接开销（提前 return）。
 *  - 日志级别从 `info` 降到 `debug` → 即使打开，也只进 `debug.log`，
 *    不会污染 `latest.log`。
 *  - 需要排查时改 [ENABLED] 为 true 重新构建即可，探针代码与调用点全部保留。
 *
 * 诊断契约保持不变（key 与调用点一一对应）：
 * 弹幕视觉链：BarrageManager.spawn → ParticleEmittersManager.spawnEmitters
 *   → updateClientVisible（按 256 格距离）→ addView（发 CREATE 包）
 *   → 客户端 ClientParticleEmittersPacketHandler.decode（查 codec 注册表）
 *   → ParticleEmittersManager.createClient → doTickClient → spawnParticle（GPU 粒子）
 *   → CParticleSystemManager 建 system → tick → renderParticlePass → CParticleRenderer 绘制
 *
 * PROBE4（全面版）三类打点：
 *  - [d]      普通探针，每 key 最多 [LIMIT] 条。
 *  - [dL]     普通探针 + 自定义条数上限（Java 侧 mixin 打点用）。
 *  - [dEvery] 高频探针（每帧/每 tick）节流：每 [every] 次调用输出一条，最多 [limit] 条。
 *
 * 全链路 key 顺序（排查时按这个看断点）：
 *   barrage/spawn → emitter/spawn → visible/check → net/addView → client/handleCreate
 *   → client/codec（★未注册 codec 才会出现）→ client/create → emit/produce → emit/gen
 *   → gpu/spawn → system/create → tick/frame → mixin/entry → pass/enter
 *   →（render/skip 或）pass/draw → draw/vis → draw/shader → draw/batch → draw/exec
 */
object CpDbg {

    /**
     * ★ 总开关。默认关闭。
     *
     * 排查粒子问题时改成 `true` 重新构建即可；日常/正式版保持 false。
     * 关闭时所有探针在进入方法后立即 return，无字符串拼接、无 map 读写。
     */
    @JvmField
    var ENABLED: Boolean = false

    private const val LIMIT = 25
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val seqs = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @JvmStatic
    fun d(key: String, msg: String) {
        if (!ENABLED) return
        val n = counts.merge(key, 1, Int::plus) ?: 1
        if (n <= LIMIT) {
            cn.coostack.cooparticlesapi.CooParticlesConstants.logger.debug("[cp-dbg] {}: {}", key, msg)
        }
    }

    /** 同 [d]，但可自定义条数上限。 */
    @JvmStatic
    fun dL(key: String, limit: Int, msg: String) {
        if (!ENABLED) return
        val n = counts.merge(key, 1, Int::plus) ?: 1
        if (n <= limit) {
            cn.coostack.cooparticlesapi.CooParticlesConstants.logger.debug("[cp-dbg] {}: {}", key, msg)
        }
    }

    /** 高频探针节流：每 [every] 次调用输出一条，总共最多 [limit] 条。 */
    @JvmStatic
    fun dEvery(key: String, every: Int, limit: Int, msg: () -> String) {
        if (!ENABLED) return
        val s = seqs.merge(key, 1, Int::plus) ?: 1
        if (s % every != 0) return
        val n = counts.merge(key, 1, Int::plus) ?: 1
        if (n <= limit) {
            cn.coostack.cooparticlesapi.CooParticlesConstants.logger.debug("[cp-dbg] {}: {}", key, msg())
        }
    }
}
