package cn.coostack.cooparticlesapi.event

import cn.coostack.cooparticlesapi.CooParticlesConstants
import cn.coostack.cooparticlesapi.annotations.events.EventHandler
import cn.coostack.cooparticlesapi.annotations.events.EventListener
import cn.coostack.cooparticlesapi.enums.DistType
import cn.coostack.cooparticlesapi.platform.CooParticlesServices
import cn.coostack.cooparticlesapi.event.api.CooEvent
import cn.coostack.cooparticlesapi.event.api.EventExecutor
import cn.coostack.cooparticlesapi.event.api.EventInterruptible
import cn.coostack.cooparticlesapi.event.api.EventPriority
import cn.coostack.cooparticlesapi.event.events.EventsInitializationEvent
import cn.coostack.cooparticlesapi.reflect.CooAPIScanner
import java.lang.reflect.Modifier
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

object CooEventBus {
    /**
     * 这里记录所有需要创建实例化的Info对象
     */
    private val needListened = ConcurrentHashMap<String, HashSet<String>>()

    private var loaded = false

    private var init = false

    private val handlerLists =
        HashMap<Class<out CooEvent>, TreeMap<EventPriority, MutableList<EventExecutor>>>()

    /**
     * 在进行扫描的时候已经加载完所有类了罢
     * Fabric需要调用此方法进行类扫描
     * NeoForge则不需要再次扫描
     */
    fun scanListeners() {

        if (loaded || init) {
            return
        }
        loaded = true
        val start = System.currentTimeMillis()

        CooAPIScanner.getClassesWithAnnotation(EventListener::class.java)
            .forEach {
                val value = it.getAnnotation(EventListener::class.java)
                val dist = value?.dist ?: DistType.BOTH
                if (dist != DistType.BOTH && dist != CooParticlesServices.PLATFORM.getDistType()) {
                    return@forEach
                }
                appendListenerTarget(value?.modId ?: CooParticlesConstants.MOD_ID, it.name)
            }
        val end = System.currentTimeMillis()
        CooParticlesConstants.logger.info("事件扫描完毕 耗时 ${end - start} ms")
    }

    fun appendListenerTarget(modId: String, target: String) {
        needListened.getOrPut(modId) { HashSet() }.add(target)
    }

    @JvmStatic
    fun <T : CooEvent> call(event: T): T {
        if (!init) {
            return event
        }

        var currentEvent: Class<*> = event::class.java
        singleEvent@ while (CooEvent::class.java.isAssignableFrom(currentEvent)) {
            val handleList = handlerLists[currentEvent] ?: let {
                currentEvent = currentEvent.superclass
                continue
            }
            handleList.forEach {
                for (executor in it.value) {
                    runCatching {
                        executor.executor.accept(event)
                    }.onFailure { err ->
                        CooParticlesConstants.logger.error(
                            "处理 事件:${event::class.java.name} 时出现错误: 监听模组：${executor.modId}",
                            err
                        )
                    }

                    if (event is EventInterruptible && event.isInterrupted) {
                        currentEvent = currentEvent.superclass
                        continue@singleEvent
                    }
                }
            }
            currentEvent = currentEvent.superclass
        }
        return event
    }

    fun initListeners() {
        if (init) {
            return
        }
        init = true
        needListened.forEach { (modId, eventListeners) ->






            eventListeners.forEach {
                findListenerHandlers(it, modId)
            }
        }


        call(EventsInitializationEvent())
    }

    private fun findListenerHandlers(target: String, modId: String) {
        val clazz = Class.forName(target)

        val instance =
            clazz.declaredFields.find { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }?.get(null)
                ?: clazz.getDeclaredConstructor()
                    .apply { isAccessible = true }
                    .newInstance()
        clazz.declaredMethods.filter {
            it.isAnnotationPresent(EventHandler::class.java) &&
                    it.parameterCount == 1 &&
                    CooEvent::class.java.isAssignableFrom(it.parameterTypes[0])
        }.forEach {
            it.isAccessible = true
            val eventType = it.parameterTypes[0] as Class<out CooEvent>
            val handlerAnnotation = it.getAnnotation(EventHandler::class.java)!!
            handlerLists.getOrPut(eventType) { TreeMap() }
                .getOrPut(handlerAnnotation.priority) {
                    ArrayList()
                }.add(EventExecutor(modId) { e ->
                    it.invoke(instance, e)
                })
        }
    }

}
