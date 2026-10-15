package cn.coostack.cooparticlesapi.cparticle.force

/**
 * 力场资源的全局解析缓存表。
 *
 * 上游缺失文件，依据引用点反推：
 * - `CParticleSystemManager.kt:797` → `CParticleForceResourceRegistry.clearResolvedBindings()`
 * - `CParticleSystemManager.kt:814` → 同上（releaseAll 时）
 *
 * 两处调用都发生在「资源重载 / 完全释放」时，
 * 用于丢弃已解析的资源绑定（例如把 ResourceLocation 解析成 GL 纹理 id 的结果）。
 */
object CParticleForceResourceRegistry {

    /** 已解析绑定的缓存：资源 key → 已解析对象（如 GL 纹理 id） */
    private val resolved = LinkedHashMap<Any, Any>()

    /**
     * 记录一个已解析的绑定。
     */
    @JvmStatic
    fun putResolvedBinding(key: Any, value: Any) {
        resolved[key] = value
    }

    /**
     * 取已解析的绑定；不存在返回 null。
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> getResolvedBinding(key: Any): T? = resolved[key] as? T

    /**
     * 清空所有已解析绑定。
     *
     * 资源重载（图集 UV 变化）或系统完全释放时调用，
     * 避免持有失效的 GL 对象引用。
     */
    @JvmStatic
    fun clearResolvedBindings() {
        resolved.clear()
    }

    /** 当前缓存条目数（调试用） */
    @JvmStatic
    val size: Int get() = resolved.size
}
