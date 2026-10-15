package cn.coostack.cooparticlesapi.utils.helper

import cn.coostack.cooparticlesapi.api.controler.Controlable
import cn.coostack.cooparticlesapi.utils.GraphMathHelper
import kotlin.math.*

abstract class AlphaHelper(var minAlpha: Double, var maxAlpha: Double, var alphaTick: Int) : ParticleHelper {
    var current = 0
        protected set
    protected var step = abs(maxAlpha - minAlpha) / alphaTick

    init {

        val temp = min(minAlpha, maxAlpha)
        maxAlpha = max(minAlpha, maxAlpha)
        minAlpha = temp
    }


    fun recalculateStep(): AlphaHelper {
        val temp = min(minAlpha, maxAlpha)
        maxAlpha = max(minAlpha, maxAlpha)
        minAlpha = temp
        step = abs(maxAlpha - minAlpha) / alphaTick
        return this
    }


    fun toggleAlpha(alpha: Double) {
        when {
            alpha <= minAlpha -> resetAlphaMin()
            alpha >= maxAlpha -> {
                current = alphaTick
                resetAlphaMax()
            }

            else -> {
                val point = alpha - minAlpha
                val tick = (point / step).roundToInt()
                current = tick
                doAlphaTo(current)
            }
        }
    }


    fun resetAlphaMin() {
        getLoadedGroup()?.let {
            current = 0
            setAlpha(minAlpha)
        }
    }


    fun resetAlphaMax() {
        getLoadedGroup()?.let {
            current = alphaTick
            setAlpha(maxAlpha)
        }
    }


    fun doAlphaTo(current: Int) {
        getLoadedGroup()?.let {
            val enter = current.coerceAtLeast(0)
            this.current = enter
            when {
                current >= alphaTick -> resetAlphaMax()
                current <= 0 -> resetAlphaMin()
                else -> {
                    val progress = current.toDouble() / alphaTick
                    val alpha = GraphMathHelper.lerp(progress, minAlpha, maxAlpha)
                    setAlpha(alpha)
                }
            }
        }
    }


    fun increaseAlpha() {
        getLoadedGroup()?.takeUnless { over() }?.let {
            current++
            val progress = current.toDouble() / alphaTick
            val alpha = GraphMathHelper.lerp(progress, minAlpha, maxAlpha)
            setAlpha(alpha)
        }
    }


    fun decreaseAlpha() {
        getLoadedGroup()?.takeUnless { isZero() }?.let {
            current--
            val progress = current.toDouble() / alphaTick
            val alpha = GraphMathHelper.lerp(progress, minAlpha, maxAlpha)
            setAlpha(alpha)
        }
    }


    fun over(): Boolean = alphaTick <= current
    fun isZero(): Boolean = current <= 0



    abstract fun getLoadedGroup(): Controlable<*>?


    abstract fun getCurrentAlpha(): Double


    abstract fun setAlpha(alpha: Double)
}