package cn.coostack.cooparticlesapi.test.options.particle.emitter

import cn.coostack.cooparticlesapi.annotations.CodecField
import cn.coostack.cooparticlesapi.annotations.CooAutoRegister
import cn.coostack.cooparticlesapi.network.particle.emitters.AutoParticleEmitters
import cn.coostack.cooparticlesapi.network.particle.emitters.ControlableParticleData
import cn.coostack.cooparticlesapi.network.particle.emitters.SimpleRandomParticleData
import cn.coostack.cooparticlesapi.network.particle.emitters.command.ParticleCommandQueue
import cn.coostack.cooparticlesapi.network.particle.emitters.command.ParticleDragCommand
import cn.coostack.cooparticlesapi.network.particle.emitters.command.ParticleNoiseCommand
import cn.coostack.cooparticlesapi.particles.control.ParticleControler
import cn.coostack.cooparticlesapi.utils.GraphMathHelper
import cn.coostack.cooparticlesapi.utils.RelativeLocation
import cn.coostack.cooparticlesapi.utils.builder.PointsBuilder
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

@CooAutoRegister
class TestWaveEmitters : AutoParticleEmitters {



    /** 冲击波最大半径 */
    @CodecField
    var maxRadius: Double = 12.0

    /** 每圈点数 */
    @CodecField
    var ringPoints: Int = 96

    /** 发射持续时间（tick），同时作为 maxTick */
    @CodecField
    var duration: Int = 18

    /** 冲击波离地高度（避免穿模/闪烁） */
    @CodecField
    var groundOffsetY: Double = 0.08

    /** 初始向外速度 */
    @CodecField
    var outwardSpeed: Double = 0.65

    /** 是否开启次级内环（更好看） */
    @CodecField
    var enableInnerRing: Boolean = true

    /** 内环半径比例（相对外环） */
    @CodecField
    var innerRadiusMul: Double = 0.78

    /**
     * 粒子基础模板（颜色/大小/alpha/贴图/亮度/可视距离等）
     * - 只在生成时生效的字段就放这里
     * - 每次 genParticles 时 clone 一份给每个粒子用
     */
    @CodecField
    var baseData: ControlableParticleData = ControlableParticleData().apply {
        size = 0.18f
        alpha = 0.85f
        color = Vector3f(0.70f, 0.90f, 1.00f)
        maxAge = 18
        visibleRange = 128f
        light = 15
        speedLimit = 12.0
        faceToCamera = true
        setTextureSheet("ADDITION_BLEND_TRANSLUCENT")
    }

    /**
     * 随机控制（可选）
     * - 例如随机粒子数量/寿命/大小/速度
     * - 你想不随机就把区间设成相同即可
     */
    @CodecField
    var random: SimpleRandomParticleData = SimpleRandomParticleData().apply {
        minAge = 16
        maxAge = 22
        minSize = 0.14
        maxSize = 0.22
        minSpeed = 0.55
        maxSpeed = 0.75
        minCount = 1
        maxCount = 1
    }



    @Transient
    private val queue: ParticleCommandQueue = ParticleCommandQueue()
        .add(ParticleDragCommand(damping = 0.16, minSpeed = 0.0, linear = 0.0))
        .add(
            ParticleNoiseCommand(
                strength = 0.08,
                frequency = 0.22,
                speed = 0.11,
                affectY = 0.15,
                clampSpeed = 1.2,
            )
        )

    constructor() : super(Vec3.ZERO, null)
    constructor(pos: Vec3, world: Level?) : super(pos, world)

    override fun start() {
        maxTick = duration.coerceAtLeast(1)
        super.start()
    }

    override fun doTick() {


    }

    override fun genParticles(lerpProgress: Float): List<Pair<ControlableParticleData, RelativeLocation>> {

        val p = if (maxTick > 0) (tick.toFloat() / maxTick.toFloat()).coerceIn(0f, 1f) else 1f
        val eased = GraphMathHelper.smoothStep(0f, 1f, p)
        val radiusNow = GraphMathHelper.lerp(eased.toDouble(), 0.35, maxRadius)

        val outer = buildRing(radiusNow, ringPoints, groundOffsetY, sign = 0)
        if (!enableInnerRing) return outer

        val inner = buildRing(
            radiusNow * innerRadiusMul,
            (ringPoints * 0.55).toInt().coerceAtLeast(12),
            groundOffsetY + 0.05,
            sign = 1
        )
        return outer + inner
    }

    private fun buildRing(
        radius: Double,
        count: Int,
        y: Double,
        sign: Int
    ): List<Pair<ControlableParticleData, RelativeLocation>> {
        val points = PointsBuilder()
            .addCircle(1.0, count.coerceAtLeast(8))
            .pointsOnEach {
                it.multiply(radius)
                it.y += y
            }
            .createWithoutClone()

        return points.map { offset ->
            val d = baseData.clone().apply {
                this.sign = sign
                this.maxAge = random.getRandomParticleMaxAge()
                this.size = random.getRandomSize()
            }
            d to offset
        }
    }

    override fun singleParticleAction(
        controler: ParticleControler,
        data: ControlableParticleData,
        spawnPos: RelativeLocation,
        spawnWorld: Level,
        particleLerpProgress: Float,
        posLerpProgress: Float,
    ) {

        val radial = spawnPos.clone().apply { y = 0.0 }.toVector()
        val dir = if (radial.lengthSqr() < 1e-8) Vec3(1.0, 0.0, 0.0) else radial.normalize()


        val s = random.getRandomSpeed().coerceAtLeast(0.0)
        data.velocity = dir.scale(s)


        controler.addPreTickAction {
            queue.applyVelocity(data, this)


            val lt = if (lifetime > 0) (currentAge.toFloat() / lifetime.toFloat()).coerceIn(0f, 1f) else 1f
            val fade = 1f - lt


            val baseA = data.alpha
            this.particleAlpha = if (data.sign == 0) baseA * fade else baseA * fade * 0.6f
            this.size = if (data.sign == 0) data.size * (1.05f - 0.25f * lt) else data.size * (0.9f - 0.2f * lt)


            this.loc =
                Vec3(this.loc.x, pos.y + if (data.sign == 0) groundOffsetY else (groundOffsetY + 0.05), this.loc.z)
        }
    }
}
