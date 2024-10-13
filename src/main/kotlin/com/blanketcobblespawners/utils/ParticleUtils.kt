package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners.computeValidSpawnPositions
import com.blanketcobblespawners.BlanketCobbleSpawners.random
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

object ParticleUtils {

    val activeVisualizations = mutableMapOf<ServerPlayerEntity, Pair<BlockPos, Long>>()
    val visualizationInterval = 10L // Number of ticks between particle updates
    val cachedValidPositions = mutableMapOf<BlockPos, List<BlockPos>>() // Cache for valid spawn positions

    fun visualizeSpawnerPositions(player: ServerPlayerEntity, spawnerData: SpawnerData) {
        val serverWorld = player.world as? ServerWorld ?: return

        // Check if cached positions exist; if not, compute and cache them
        val validPositions = cachedValidPositions[spawnerData.spawnerPos] ?: run {
            val positions = computeValidSpawnPositions(serverWorld, spawnerData)
            cachedValidPositions[spawnerData.spawnerPos] = positions
            positions
        }

        val flameParticle = ParticleTypes.FLAME
        val blueFlameParticle = ParticleTypes.SOUL_FIRE_FLAME

        val spawnRadiusWidth = spawnerData.spawnRadius.width
        val spawnRadiusHeight = spawnerData.spawnRadius.height
        val centerPos = spawnerData.spawnerPos

        // Get player's chunk position
        val playerChunkX = player.blockPos.x shr 4
        val playerChunkZ = player.blockPos.z shr 4

        // Render valid spawn points inside the radius
        for (spawnPos in validPositions) {
            val spawnChunkX = spawnPos.x shr 4
            val spawnChunkZ = spawnPos.z shr 4

            // Only render particles if within 2 chunks from the player
            if (Math.abs(playerChunkX - spawnChunkX) <= 2 && Math.abs(playerChunkZ - spawnChunkZ) <= 2) {
                player.networkHandler.sendPacket(
                    net.minecraft.network.packet.s2c.play.ParticleS2CPacket(
                        flameParticle,
                        true,
                        spawnPos.x + 0.5,
                        spawnPos.y + 1.0,
                        spawnPos.z + 0.5,
                        0.0f, 0.0f, 0.0f,
                        0.01f,
                        1
                    )
                )
            }
        }

        // Render cube outline using blue flame particles (outline for the radius)
        // Properly account for width and height in all directions and only render the edges
        renderCubeOutline(player, blueFlameParticle, centerPos, spawnRadiusWidth, spawnRadiusHeight)
    }

    private fun renderCubeOutline(
        player: ServerPlayerEntity,
        particleType: ParticleEffect,
        centerPos: BlockPos,
        radiusWidth: Int,
        radiusHeight: Int
    ) {
        // Render the 12 edges of the cube
        for (x in listOf(-radiusWidth, radiusWidth)) {
            for (y in listOf(-radiusHeight, radiusHeight)) {
                for (z in -radiusWidth..radiusWidth) {
                    sendParticleIfInRange(player, particleType, centerPos.x + x, centerPos.y + y, centerPos.z + z)
                }
            }
        }

        for (y in listOf(-radiusHeight, radiusHeight)) {
            for (z in listOf(-radiusWidth, radiusWidth)) {
                for (x in -radiusWidth..radiusWidth) {
                    sendParticleIfInRange(player, particleType, centerPos.x + x, centerPos.y + y, centerPos.z + z)
                }
            }
        }

        for (x in listOf(-radiusWidth, radiusWidth)) {
            for (z in listOf(-radiusWidth, radiusWidth)) {
                for (y in -radiusHeight..radiusHeight) {
                    sendParticleIfInRange(player, particleType, centerPos.x + x, centerPos.y + y, centerPos.z + z)
                }
            }
        }
    }

    fun toggleVisualization(player: ServerPlayerEntity, spawnerData: SpawnerData) {
        val spawnerPos = spawnerData.spawnerPos

        // Check if the player already has an active visualization for this spawner
        if (activeVisualizations.containsKey(player) && activeVisualizations[player]?.first == spawnerPos) {
            // Visualization is active, so we stop it
            activeVisualizations.remove(player)
            player.sendMessage(Text.literal("Stopped visualizing spawn points for spawner '${spawnerData.spawnerName}'"), false)
            return
        }

        // Start the visualization if not already active, set the current tick as the last tick
        activeVisualizations[player] = spawnerPos to player.world.time
        player.sendMessage(Text.literal("Started visualizing spawn points and cube outline for spawner '${spawnerData.spawnerName}'"), false)
    }

    private fun sendParticleIfInRange(player: ServerPlayerEntity, particleType: ParticleEffect, x: Int, y: Int, z: Int) {
        val playerChunkX = player.blockPos.x shr 4
        val playerChunkZ = player.blockPos.z shr 4
        val particleChunkX = x shr 4
        val particleChunkZ = z shr 4

        if (Math.abs(playerChunkX - particleChunkX) <= 2 && Math.abs(playerChunkZ - particleChunkZ) <= 2) {
            player.networkHandler.sendPacket(
                net.minecraft.network.packet.s2c.play.ParticleS2CPacket(
                    particleType,
                    true,
                    x + 0.5,
                    y + 1.0,
                    z + 0.5,
                    0.0f, 0.0f, 0.0f,
                    0.01f,
                    1
                )
            )
        }
    }

    // New function to spawn particles at the Pokémon's spawn position
    fun spawnMonParticles(world: ServerWorld, spawnPos: BlockPos) {
        val particleCount = 10
        for (i in 0 until particleCount) {
            val xOffset = random.nextDouble() * 0.6 - 0.3
            val yOffset = random.nextDouble() * 0.6 - 0.3
            val zOffset = random.nextDouble() * 0.6 - 0.3

            // Adjust velocity to make the particles shoot out slightly
            val velocityX = random.nextDouble() * 0.02 - 0.01
            val velocityY = random.nextDouble() * 0.02 + 0.02 // Make particles shoot upwards
            val velocityZ = random.nextDouble() * 0.02 - 0.01

            world.spawnParticles(
                ParticleTypes.CLOUD,  // Use CLOUD for particles around the Pokémon spawn
                spawnPos.x + 0.5 + xOffset,
                spawnPos.y + 1.0 + yOffset,
                spawnPos.z + 0.5 + zOffset,
                1, velocityX, velocityY, velocityZ, 0.01
            )
        }
    }

    // Reuse this function for spawner particle effects
    fun spawnSpawnerParticles(world: ServerWorld, spawnerPos: BlockPos) {
        val particleCount = 20
        for (i in 0 until particleCount) {
            val xOffset = random.nextDouble() * 0.6 - 0.3
            val yOffset = random.nextDouble() * 0.6 - 0.3
            val zOffset = random.nextDouble() * 0.6 - 0.3

            // Adjust velocity to make the particles shoot out slightly
            val velocityX = random.nextDouble() * 0.02 - 0.01
            val velocityY = random.nextDouble() * 0.02 + 0.02 // Make particles shoot upwards
            val velocityZ = random.nextDouble() * 0.02 - 0.01

            world.spawnParticles(
                ParticleTypes.SMOKE,  // Use SMOKE for grey spawner-like particles
                spawnerPos.x + 0.5 + xOffset,
                spawnerPos.y + 1.0 + yOffset,
                spawnerPos.z + 0.5 + zOffset,
                1, velocityX, velocityY, velocityZ, 0.01
            )
        }
    }
}
