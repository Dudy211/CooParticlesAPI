package cn.coostack.cooparticlesapi.commands

import cn.coostack.cooparticlesapi.CooParticlesAPI
import cn.coostack.cooparticlesapi.network.packet.api.CooServerPacketManager
import cn.coostack.cooparticlesapi.network.packet.status.PacketPerformanceStatusControlS2C
import cn.coostack.cooparticlesapi.performance.PerformanceStatusControlAction
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/** 注册 CooParticlesAPI 管理命令及客户端 Status 会话控制命令。 */
object APICommand {
    /** 在指定 dispatcher 同时注册新根命令与兼容旧根命令。 */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(root(ROOT_COMMAND))
        dispatcher.register(root(LEGACY_ROOT_COMMAND))
    }

    /** 构造包含 clean 与 status 子树的权限受控命令根。 */
    private fun root(name: String): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal(name)
            .requires { source -> source.hasPermission(2) }
            .then(
                Commands.literal(CLEAN_COMMAND)
                    .then(Commands.literal(ALL_COMMAND).executes {
                        CooParticlesAPI.clearTransientState()
                        1
                    })
            )
            .then(debugBranch())
            .then(
                Commands.literal(STATUS_COMMAND)
                    .then(Commands.literal(START_COMMAND).executes { context ->
                        sendStatusControl(context, PerformanceStatusControlAction.START)
                    })
                    .then(Commands.literal(STOP_COMMAND).executes { context ->
                        sendStatusControl(context, PerformanceStatusControlAction.STOP)
                    })
                    .then(Commands.literal(GUI_COMMAND).executes { context ->
                        sendStatusControl(context, PerformanceStatusControlAction.GUI)
                    })
            )
    }

    /**
     * ★ 渲染诊断子树（2026-10-03，定位「粒子提交绘制但看不见」，确认后删）。
     *
     * `cull <bool>` / `depth <bool>` / `view <camera|identity>` / `reset` / 无参回显状态。
     */
    private fun debugBranch(): LiteralArgumentBuilder<CommandSourceStack> {
        fun boolArg(name: String, setter: (Boolean) -> Unit) =
            Commands.literal(name)
                .then(Commands.literal("true").executes { ctx -> applyDebug(ctx) { setter(true) } })
                .then(Commands.literal("false").executes { ctx -> applyDebug(ctx) { setter(false) } })

        return Commands.literal(DEBUG_COMMAND)
            .executes { ctx ->
                ctx.source.sendSuccess(
                    { Component.literal("CooParticles 渲染诊断: " + cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.describe()) },
                    false,
                )
                1
            }
            .then(boolArg(CULL_COMMAND) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.disableCull = !it })
            .then(boolArg(DEPTH_COMMAND) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.disableDepth = !it })
            .then(
                Commands.literal(VIEW_COMMAND)

                    .then(Commands.literal(R180_INV_COMMAND).executes { ctx ->
                        applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.viewMode = R180_INV_MODE }
                    })
                    .then(Commands.literal("inv").executes { ctx ->
                        applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.viewMode = INV_MODE }
                    })
                    .then(Commands.literal("r").executes { ctx ->
                        applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.viewMode = R_MODE }
                    })
                    .then(Commands.literal(IDENTITY_COMMAND).executes { ctx ->
                        applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.viewMode = IDENTITY_MODE }
                    })

                    .then(Commands.literal(CAMERA_COMMAND).executes { ctx ->
                        applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.viewMode = R180_INV_MODE }
                    })

                    .then(Commands.literal("modelview").executes { ctx ->
                        applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.viewMode = MODELVIEW_MODE }
                    })
            )
            .then(Commands.literal(RESET_COMMAND).executes { ctx ->
                applyDebug(ctx) { cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.reset() }
            })
    }

    /** 执行一次诊断开关变更并回显当前状态。 */
    private fun applyDebug(context: CommandContext<CommandSourceStack>, change: () -> Unit): Int {
        change()
        context.source.sendSuccess(
            { Component.literal("CooParticles 渲染诊断 → " + cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.describe()) },
            false,
        )
        return 1
    }

    /** 向执行命令的玩家客户端发送 Status 控制动作。 */
    private fun sendStatusControl(
        context: CommandContext<CommandSourceStack>,
        action: PerformanceStatusControlAction,
    ): Int {
        val source = context.source
        val player = source.playerOrException
        if (!CooServerPacketManager.sendTo(player, PacketPerformanceStatusControlS2C(action))) {
            source.sendFailure(Component.literal("CooParticles Status 控制包发送失败"))
            return 0
        }
        source.sendSuccess(
            { Component.literal("CooParticles Status: ${action.name.lowercase()}") },
            false,
        )
        return 1
    }

    /** 稳定命令注册 ID。 */
    private const val ROOT_COMMAND = "cooparticles"

    /** 兼容已有脚本和用户习惯的旧命令注册 ID。 */
    private const val LEGACY_ROOT_COMMAND = "cooparticlesapi"

    /** 清理子命令注册 ID。 */
    private const val CLEAN_COMMAND = "clean"

    /** 全部目标子命令注册 ID。 */
    private const val ALL_COMMAND = "all"

    /** Status 子命令注册 ID。 */
    private const val STATUS_COMMAND = "status"

    /** 开始采集子命令注册 ID。 */
    private const val START_COMMAND = "start"

    /** 停止采集子命令注册 ID。 */
    private const val STOP_COMMAND = "stop"

    /** 打开实时界面子命令注册 ID。 */
    private const val GUI_COMMAND = "gui"


    private const val DEBUG_COMMAND = "debug"
    private const val CULL_COMMAND = "cull"
    private const val DEPTH_COMMAND = "depth"
    private const val VIEW_COMMAND = "view"
    private const val CAMERA_COMMAND = "camera"

    /** view 模式：`Ry(180)·R⁻¹`。 */
    private const val R180_INV_COMMAND = "r180inv"
    private const val IDENTITY_COMMAND = "identity"


    private val R180_INV_MODE = cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.ViewMode.R180_INV
    private val INV_MODE = cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.ViewMode.INV
    private val R_MODE = cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.ViewMode.R
    private val IDENTITY_MODE = cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.ViewMode.IDENTITY
    private val MODELVIEW_MODE = cn.coostack.cooparticlesapi.dbg.CParticleRenderDiag.ViewMode.MODELVIEW
    private const val RESET_COMMAND = "reset"
}
