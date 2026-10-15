package cn.coostack.cooparticlesapi.extend

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * named by Fabric Yarn mapping
 * @see BlockPos.containing
 */
fun ofFloored(vec: Vec3): BlockPos {



    return BlockPos.containing(vec)
}

@JvmOverloads
fun BlockPos.playSoundAt(world: Level, sound: SoundEvent, source: SoundSource, volume: Float = 1f, pitch: Float = 1f) =
    apply {
        world.playSound(null, this, sound, source, volume, pitch)
    }