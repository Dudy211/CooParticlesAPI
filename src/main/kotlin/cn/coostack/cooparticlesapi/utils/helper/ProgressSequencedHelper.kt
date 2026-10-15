package cn.coostack.cooparticlesapi.utils.helper

import cn.coostack.cooparticlesapi.network.particle.style.SequencedParticleStyle
import cn.coostack.cooparticlesapi.api.controler.Controlable
import kotlin.math.*

abstract class ProgressSequencedHelper(var maxCount: Int, var progressMaxTick: Int) : ParticleHelper {
    var current = 0
        protected set
    protected var step = maxCount.toDouble() / progressMaxTick
    private var remainder = 0.0

    init {
        require(maxCount > 0) { "maxCount 必须大于 0" }
        require(progressMaxTick > 0) { "progressMaxTick 必须大于 0" }
        recalculateStep()
    }


    fun recalculateStep(): ProgressSequencedHelper {
        step = maxCount.toDouble() / progressMaxTick
        remainder = 0.0
        return this
    }


    fun setProgress(percent: Double) {
        val clamped = percent.coerceIn(0.0, 1.0)
        val targetTick = (clamped * progressMaxTick).roundToInt()
        doProgressTo(targetTick)
    }


    fun increaseProgress() {
        if (over()) return

        getLoadedStyle() ?: return
        val actualStep = calculateActualStep(true)

        if (actualStep > 0) {
            addMultiple(actualStep)
            current++
        }
    }


    fun decreaseProgress() {
        if (isZero()) return

        getLoadedStyle() ?: return
        val actualStep = calculateActualStep(false)

        if (actualStep > 0) {
            removeMultiple(actualStep)
            current--
        }
    }


    fun doProgressTo(targetTick: Int) {
        getLoadedStyle() ?: return
        val target = targetTick.coerceIn(0, progressMaxTick)

        when {
            target > current -> {
                val addCount = calculateTotalStep(current, target)
                addMultiple(addCount)
            }

            target < current -> {
                val removeCount = calculateTotalStep(target, current)
                removeMultiple(removeCount)
            }
        }
        current = target
    }


    fun over(): Boolean = current >= progressMaxTick
    fun isZero(): Boolean = current <= 0



    private fun calculateActualStep(isAdding: Boolean): Int {
        val raw = step + remainder
        val integerPart = raw.toInt()
        remainder = raw - integerPart

        return when {
            isAdding -> integerPart
            else -> integerPart
        }.coerceAtLeast(1)
    }


    private fun calculateTotalStep(from: Int, to: Int): Int {
        val delta = to - from
        return (delta * step).roundToInt()
    }

    abstract fun addMultiple(count: Int)

    abstract fun removeMultiple(count: Int)



    abstract fun getLoadedStyle(): Controlable<*>?


    protected abstract fun changeStatusBatch(indexes: IntArray, status: Boolean)
}