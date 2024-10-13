package com.blanketcobblespawners.utils

import net.minecraft.util.math.BlockPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SpawnerUUIDManager {
    // Map to track Pokémon entity UUID to the spawner and species it belongs to
    data class PokemonInfo(val spawnerPos: BlockPos, val speciesName: String)

    // Maps each Pokémon UUID to its associated spawner and species info
    private val pokemonUUIDMap = ConcurrentHashMap<UUID, PokemonInfo>()

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
}
