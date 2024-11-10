// File: SpawnerUUIDManager.kt
package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SpawnerUUIDManager {
    // Map to track Pokémon entity UUID to the spawner and species it belongs to
    data class PokemonInfo(
        val spawnerPos: BlockPos,
        val speciesName: String,
        var lastKnownTime: Long = System.currentTimeMillis()
    )

    // Maps each Pokémon UUID to its associated spawner and species info
    val pokemonUUIDMap = ConcurrentHashMap<UUID, PokemonInfo>()

    // Add a Pokémon UUID and associate it with a spawner and species
    fun addPokemon(uuid: UUID, spawnerPos: BlockPos, speciesName: String) {
        pokemonUUIDMap[uuid] = PokemonInfo(spawnerPos, speciesName)
    }

    // Remove a Pokémon by its UUID
    fun removePokemon(uuid: UUID) {
        pokemonUUIDMap.remove(uuid)
    }

    // Get spawner position and species for a given Pokémon UUID
    fun getPokemonInfo(uuid: UUID): PokemonInfo? {
        return pokemonUUIDMap[uuid]
    }

    // Clear all Pokémon related to a specific spawner
    fun clearPokemonForSpawner(spawnerPos: BlockPos) {
        pokemonUUIDMap.entries.removeIf { it.value.spawnerPos == spawnerPos }
    }

    // Automatically count the number of Pokémon for a specific spawner
    fun getPokemonCountForSpawner(spawnerPos: BlockPos): Int {
        return pokemonUUIDMap.values.count { it.spawnerPos == spawnerPos }
    }

    // Helper function to auto-update the spawn counter for a spawner based on existing Pokémon UUIDs
    fun updateSpawnerCount(spawnerPos: BlockPos, maxLimit: Int): Boolean {
        val currentCount = getPokemonCountForSpawner(spawnerPos)
        return currentCount < maxLimit
    }

    // Get all UUIDs for a spawner, in case a list is needed
    fun getUUIDsForSpawner(spawnerPos: BlockPos): List<UUID> {
        return pokemonUUIDMap.filterValues { it.spawnerPos == spawnerPos }.keys.toList()
    }

    // Cleanup stale entries in pokemonUUIDMap
    fun cleanupStaleEntries(server: MinecraftServer) {
        val uuidsToRemove = mutableListOf<UUID>()
        for ((uuid, info) in pokemonUUIDMap) {
            val spawnerData = ConfigManager.spawners[info.spawnerPos] ?: continue
            val registryKey = BlanketCobbleSpawners.parseDimension(spawnerData.dimension)
            val serverWorld = server.getWorld(registryKey) ?: continue
            val entity = serverWorld.getEntity(uuid)
            if (entity !is com.cobblemon.mod.common.entity.pokemon.PokemonEntity || !entity.isAlive || !entity.pokemon.isWild()) {
                uuidsToRemove.add(uuid)
            } else {
                // Update last known time if entity is valid
                info.lastKnownTime = System.currentTimeMillis()
            }
        }
        uuidsToRemove.forEach { uuid ->
            pokemonUUIDMap.remove(uuid)
            logDebug("Removed stale Pokémon UUID $uuid from SpawnerUUIDManager during cleanup.")
        }
    }

    fun cleanupStaleEntriesForSpawner(serverWorld: ServerWorld, spawnerPos: BlockPos) {
        val uuidsToRemove = mutableListOf<UUID>()

        // Filter for entries associated with this spawner
        for ((uuid, info) in pokemonUUIDMap.filterValues { it.spawnerPos == spawnerPos }) {
            val entity = serverWorld.getEntity(uuid)
            if (entity !is PokemonEntity || !entity.isAlive || !entity.pokemon.isWild()) {
                uuidsToRemove.add(uuid)
            } else {
                // Update last known time if the entity is valid
                info.lastKnownTime = System.currentTimeMillis()
            }
        }

        // Remove all stale UUIDs for this spawner
        uuidsToRemove.forEach { uuid ->
            pokemonUUIDMap.remove(uuid)
            logDebug("Removed stale Pokémon UUID $uuid from SpawnerUUIDManager for spawner at $spawnerPos.")
        }
    }

    private fun logDebug(message: String) {
        if (ConfigManager.configData.globalConfig.debugEnabled) {
            println("[DEBUG] $message")
        }
    }
}
