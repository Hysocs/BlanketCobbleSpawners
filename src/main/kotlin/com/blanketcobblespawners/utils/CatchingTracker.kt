package com.blanketcobblespawners.utils

import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokeball.PokeBallCaptureCalculatedEvent
import com.cobblemon.mod.common.api.pokeball.catching.CaptureContext
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting

class CatchingTracker {

    // Variable to track whether we should check for the ball's removal
    private var checkForBallRemoval = false
    private var trackedPokeBallUuid: java.util.UUID? = null
    private var trackedPlayer: ServerPlayerEntity? = null
    private var trackedPokeBallEntity: EmptyPokeBallEntity? = null  // Store the entity itself

    fun registerEvents() {
        // Event listener for capture restrictions
        CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.subscribe { event ->
            handlePokeBallCaptureCalculated(event)
        }

        // Register the tick event to check for the Poké Ball entity removal
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (checkForBallRemoval) {
                trackedPlayer?.let { player ->
                    val world = player.world as ServerWorld
                    trackedPokeBallUuid?.let { pokeBallUuid ->
                        // Check if the Poké Ball entity is no longer present
                        if (world.getEntity(pokeBallUuid) == null) {
                            // Entity is removed, return the ball to the player
                            trackedPokeBallEntity?.let { returnPokeballToPlayer(player, it) }  // Pass the entity here
                            // Stop checking
                            checkForBallRemoval = false
                        }
                    }
                }
            }
        }
    }

    private fun handlePokeBallCaptureCalculated(event: PokeBallCaptureCalculatedEvent) {
        val pokeBallEntity: EmptyPokeBallEntity = event.pokeBallEntity
        val pokemonEntity: PokemonEntity = event.pokemonEntity
        val thrower: ServerPlayerEntity? = pokeBallEntity.owner as? ServerPlayerEntity

        logDebug("PokeBallCaptureCalculatedEvent triggered for Pokémon: ${pokemonEntity.pokemon.species.name}, UUID: ${pokemonEntity.uuid}")

        // Check if the Pokémon is from a spawner
        val spawnerInfo = SpawnerUUIDManager.getPokemonInfo(pokemonEntity.uuid)
        if (spawnerInfo != null) {
            logDebug("Pokémon ${pokemonEntity.pokemon.species.name} is from spawner at ${spawnerInfo.spawnerPos}")

            // Retrieve the Pokémon's config from the spawner data
            val spawnerData = ConfigManager.spawners[spawnerInfo.spawnerPos]
            val pokemonSpawnEntry = spawnerData?.selectedPokemon?.find {
                it.pokemonName.equals(pokemonEntity.pokemon.species.name, ignoreCase = true)
            }

            if (pokemonSpawnEntry != null) {
                val usedPokeBall = pokeBallEntity.pokeBall
                val usedPokeBallName = usedPokeBall.name.toString()
                val allowedPokeBalls = pokemonSpawnEntry.captureSettings.requiredPokeBalls

                logDebug("Used Pokéball: $usedPokeBallName, Allowed Pokéballs: $allowedPokeBalls")

                // Check if the Pokémon has restricted capture enabled
                if (pokemonSpawnEntry.captureSettings.restrictCaptureToLimitedBalls) {
                    // If restricted, check if "ALL" is not in the list and if the used Poké Ball is not allowed
                    if (!allowedPokeBalls.contains("ALL") && !allowedPokeBalls.contains(usedPokeBallName)) {
                        // Make the capture fail by setting the captureResult to a failed CaptureContext
                        event.captureResult = CaptureContext(
                            numberOfShakes = 0,
                            isSuccessfulCapture = false,
                            isCriticalCapture = false
                        )
                        logDebug("Capture attempt failed: ${pokemonEntity.pokemon.species.name} can only be captured with one of the following balls: $allowedPokeBalls.")

                        // Notify the player
                        thrower?.sendMessage(
                            Text.literal("Only the following Pokéballs can capture this Pokémon: $allowedPokeBalls!")
                                .formatted(Formatting.RED),
                            false
                        )
                        logDebug("Sent message to player: Only the following Pokéballs can capture this Pokémon: $allowedPokeBalls!")

                        // Set up tracking for the ball's removal
                        trackedPokeBallUuid = pokeBallEntity.uuid
                        trackedPlayer = thrower
                        trackedPokeBallEntity = pokeBallEntity  // Store the entity for later use
                        checkForBallRemoval = true // Start checking for entity removal
                    } else {
                        logDebug("Valid Pokéball used successfully to capture ${pokemonEntity.pokemon.species.name}.")
                    }
                } else {
                    logDebug("No capture restriction for Pokémon: ${pokemonEntity.pokemon.species.name}. Any Pokéball is allowed.")
                }
            } else {
                logDebug("Pokémon ${pokemonEntity.pokemon.species.name} not found in the spawner's configuration.")
            }
        } else {
            logDebug("Pokémon ${pokemonEntity.pokemon.species.name} is NOT from a spawner.")
        }
    }


    /**
     * Returns the Poké Ball to the player's inventory or drops it if their inventory is full.
     */
    /**
     * Spawns the Poké Ball in the world at the last known position of the Poké Ball entity.
     */
    private fun returnPokeballToPlayer(player: ServerPlayerEntity?, pokeBallEntity: EmptyPokeBallEntity) {
        if (player == null) return

        // Discard the ball entity to simulate its disappearance
        pokeBallEntity.discard()

        // Find the corresponding Poké Ball item from the Poké Ball entity
        val usedPokeBallItem = pokeBallEntity.pokeBall.item()
        val pokeBallStack = usedPokeBallItem.defaultStack

        // Get the last known position of the ball entity
        val ballPos = pokeBallEntity.blockPos

        // Spawn the Poké Ball item in the world at the ball's last position
        val world = player.world
        world.spawnEntity(net.minecraft.entity.ItemEntity(world, ballPos.x + 0.5, ballPos.y + 0.5, ballPos.z + 0.5, pokeBallStack))

        // Log the return action for debugging
        logDebug("Spawned Pokéball ${usedPokeBallItem.name} at ${ballPos.x}, ${ballPos.y}, ${ballPos.z} after failed capture.")
    }

}
