package cn.coostack.cooparticlesapi.compat.mc1201

import net.minecraft.client.gui.screens.Screen

import net.minecraft.client.gui.GuiGraphics

/**
 * 1.20.1 兼容层：Screen 的 GUI API。
 *
 * 差异：
 *   1.21: renderBackground(GuiGraphics, int, int, float)   ← 带鼠标坐标
 *         mouseScrolled(double, double, double, double)
 *         clearFocus() 是 protected
 *   1.20.1: renderBackground(GuiGraphics)                   ← 只有 1 个参数
 *           mouseScrolled(double, double, double)
 *           clearFocus() 是 private
 */

/** 1.21 的多参 renderBackground → 1.20.1 单参 */
fun Screen.renderBackgroundCompat(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
    this.renderBackground(guiGraphics)

/** 1.21 的 4 参 mouseScrolled → 1.20.1 3 参 */
fun Screen.mouseScrolledCompat(
    mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double,
): Boolean = this.mouseScrolled(mouseX, mouseY, deltaY)
