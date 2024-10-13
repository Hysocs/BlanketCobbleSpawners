package com.blanketcobblespawners.utils

import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.*

class BattleTracker {

    // Map to keep track of ongoing battles by battle ID
    private val ongoingBattles = mutableMapOf<UUID, BattleInfo>()

    // Set to keep track of battles pending cleanup
    private val pendingCleanupBattles = mutableSetOf<UUID>()

    // Data class to store per-battle tracking data
    data class BattleInfo(
        val battleId: UUID,
        var actors: List<BattleActor>,
        val lastActivePlayerMon: MutableMap<UUID, Pokemon> = mutableMapOf(),
        val lastActiveOpponentMon: MutableMap<UUID, Pokemon> = mutableMapOf(),
        var isOpponentFromSpawner: Boolean = false,
        val originalEVMap: MutableMap<UUID, Map<Stat, Int>> = mutableMapOf(),
        var valuesApplied: Boolean = false, // Flag to indicate if values have been applied
        var currentActivePlayerPokemon: Pokemon? = null
    )

    fun registerEvents() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe { event ->
            handleBattleStartPre(event.battle.battleId)
        }

        CobblemonEvents.BATTLE_STARTED_POST.subscribe { event ->
            handleBattleStartPost(event.battle.battleId, event.battle.actors.toList())
        }

        CobblemonEvents.POKEMON_SENT_POST.subscribe { event ->
            handlePokemonSent(event.pokemon)
        }

        CobblemonEvents.BATTLE_VICTORY.subscribe { event ->
            handleBattleVictory(event.battle.battleId)
        }

        CobblemonEvents.BATTLE_FLED.subscribe { event ->
            handleBattleFlee(event.battle.battleId)
        }

        CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
            handlePokemonCaptured(event.pokemon)
        }

        // Register per-tick handler
        ServerTickEvents.START_SERVER_TICK.register { server ->
            onServerTick(server)
        }
    }

    private fun handleBattleStartPre(battleId: UUID) {
        logDebug("Battle pre-start for Battle ID: $battleId")
        // Initialize a new BattleInfo object for this battle
        ongoingBattles[battleId] = BattleInfo(
            battleId = battleId,
            actors = emptyList() // Will be updated in handleBattleStartPost
        )
    }

    private fun handleBattleStartPost(battleId: UUID, actors: List<BattleActor>) {
        logDebug("Battle fully started for Battle ID: $battleId")

        val battleInfo = ongoingBattles[battleId] ?: return
        battleInfo.actors = actors

        actors.forEach { actor ->
            when (actor) {
                is PlayerBattleActor -> handlePlayerActivePokemon(battleId, actor.pokemonList.firstOrNull()?.effectedPokemon)
                is PokemonBattleActor -> handleOpponentActivePokemon(battleId, actor.pokemonList.firstOrNull()?.effectedPokemon)
            }
        }
    }

    private fun handlePokemonSent(pokemon: Pokemon) {
        // Need to find which battle this Pokemon is in
        val battleId = findBattleIdByPokemon(pokemon)
        if (battleId != null) {
            if (pokemon.entity?.owner is ServerPlayerEntity) {
                logDebug("Player swapped in Pokémon: ${pokemon.species.name}")
                handlePlayerActivePokemon(battleId, pokemon)
            } else {
                logDebug("Opponent swapped in Pokémon: ${pokemon.species.name}")
                handleOpponentActivePokemon(battleId, pokemon)
            }
        } else {
            logDebug("Could not find battle for Pokémon: ${pokemon.species.name}")
        }
    }

    private fun handlePlayerActivePokemon(battleId: UUID, pokemon: Pokemon?) {
        if (pokemon == null) {
            logDebug("Player active Pokémon is null, skipping EV save.")
            return
        }

        val battleInfo = ongoingBattles[battleId] ?: return

        // Update the current active Pokémon
        battleInfo.currentActivePlayerPokemon = pokemon

        // Save original EVs if not already saved
        if (!battleInfo.originalEVMap.containsKey(pokemon.uuid)) {
            saveOriginalEVs(battleId, pokemon)
        }

        // Keep track of all Pokémon used during the battle
        battleInfo.lastActivePlayerMon[pokemon.uuid] = pokemon

        logDebug("Tracking Player's Pokémon: ${pokemon.species.name}, UUID: ${pokemon.uuid}")
    }

    private fun handleOpponentActivePokemon(battleId: UUID, pokemon: Pokemon?) {
        if (pokemon == null) {
            logDebug("Opponent active Pokémon is null, skipping battle logic.")
            return
        }

        val battleInfo = ongoingBattles[battleId] ?: return

        if (!battleInfo.isOpponentFromSpawner) {
            val entity = pokemon.entity
            if (entity != null) {
                val spawnerInfo = SpawnerUUIDManager.getPokemonInfo(entity.uuid)
                if (spawnerInfo != null) {
                    logDebug("Opponent's Pokémon: ${pokemon.species.name} (UUID: ${entity.uuid}) is from spawner at ${spawnerInfo.spawnerPos}")
                    battleInfo.lastActiveOpponentMon[pokemon.uuid] = pokemon
                    battleInfo.isOpponentFromSpawner = true

                    // Save original EVs for the player's Pokémon
                    saveOriginalEVs(battleId, battleInfo.currentActivePlayerPokemon)
                } else {
                    logDebug("Opponent's Pokémon: ${pokemon.species.name} (UUID: ${entity.uuid}) is NOT from a spawner. Skipping all battle logic.")
                    battleInfo.isOpponentFromSpawner = false
                }
            }
        } else {
            logDebug("Already checked opponent's Pokémon. Skipping further checks.")
        }
    }

    private fun handleBattleVictory(battleId: UUID) {
        logDebug("Battle victory for Battle ID: $battleId")

        val battleInfo = ongoingBattles[battleId] ?: return

        // Mark battle as pending cleanup
        pendingCleanupBattles.add(battleId)

        // Attempt to apply values immediately
        applyValuesAfterBattle(battleId)
    }

    private fun handleBattleFlee(battleId: UUID) {
        logDebug("Battle fled for Battle ID: $battleId")

        // Since the player fled, we do not apply any EV changes
        cleanupBattle(battleId)
    }

    private fun handlePokemonCaptured(pokemon: Pokemon) {
        logDebug("Pokémon captured during battle: ${pokemon.species.name}")
        // Find the battle that this Pokémon was part of
        val battleId = findBattleIdByPokemon(pokemon)
        if (battleId != null) {
            // Since the Pokémon was captured, we do not apply any EV changes
            cleanupBattle(battleId)
        }
    }

    private fun applyValuesAfterBattle(battleId: UUID) {
        val battleInfo = ongoingBattles[battleId] ?: return

        if (battleInfo.valuesApplied) {
            logDebug("Values already applied for Battle ID: $battleId")
            return
        }

        val playerPokemon = battleInfo.currentActivePlayerPokemon
        val opponentPokemon = battleInfo.lastActiveOpponentMon.values.firstOrNull()

        if (playerPokemon != null && opponentPokemon != null) {
            if (battleInfo.isOpponentFromSpawner) {
                logDebug("Reverting and applying EVs for Player's Pokémon: ${playerPokemon.species.name}")
                logDebug("Based on Opponent's Pokémon: ${opponentPokemon.species.name}")

                revertEVsAfterChange(battleId, playerPokemon)
                applyCustomEVs(playerPokemon, playerPokemon.entity, opponentPokemon.species.name)
            } else {
                logDebug("Opponent's Pokémon was not from a spawner. Skipping EV application.")
            }
        } else {
            logDebug("Could not find active player or opponent Pokémon for EV calculation.")
        }

        // Mark that values have been applied
        battleInfo.valuesApplied = true
    }

    private fun saveOriginalEVs(battleId: UUID, pokemon: Pokemon?) {
        if (pokemon == null) return

        val battleInfo = ongoingBattles[battleId] ?: return

        val currentEVs = Stats.PERMANENT.associateWith { pokemon.evs.get(it) ?: 0 }
        battleInfo.originalEVMap[pokemon.uuid] = currentEVs
        logDebug("Saved EVs for ${pokemon.species.name}: ${currentEVs.entries.joinToString { "${it.key}: ${it.value}" }}")
    }

    private fun revertEVsAfterChange(battleId: UUID, pokemon: Pokemon) {
        val battleInfo = ongoingBattles[battleId] ?: return

        val originalEVs = battleInfo.originalEVMap[pokemon.uuid] ?: run {
            logDebug("No original EVs found for Pokémon ${pokemon.species.name} (UUID: ${pokemon.uuid}). Skipping EV revert.")
            return
        }

        originalEVs.forEach { (stat, ev) ->
            pokemon.evs.set(stat, ev)
        }
        logDebug("Reverted EVs for ${pokemon.species.name}: ${originalEVs.entries.joinToString { "${it.key}: ${it.value}" }}")
    }

    private fun applyCustomEVs(pokemon: Pokemon, entity: PokemonEntity?, opponentSpeciesName: String) {
        val spawnerPokemonEntry = ConfigManager.spawners.values
            .flatMap { it.selectedPokemon }
            .firstOrNull { it.pokemonName.equals(opponentSpeciesName, ignoreCase = true) }

        if (spawnerPokemonEntry != null) {
            if (!spawnerPokemonEntry.evSettings.allowCustomEvsOnDefeat) {
                logDebug("Custom EVs not allowed for opponent's species: $opponentSpeciesName.")
                return
            }

            val customEvs = mapOf(
                Stats.HP to spawnerPokemonEntry.evSettings.evHp,
                Stats.ATTACK to spawnerPokemonEntry.evSettings.evAttack,
                Stats.DEFENCE to spawnerPokemonEntry.evSettings.evDefense,
                Stats.SPECIAL_ATTACK to spawnerPokemonEntry.evSettings.evSpecialAttack,
                Stats.SPECIAL_DEFENCE to spawnerPokemonEntry.evSettings.evSpecialDefense,
                Stats.SPEED to spawnerPokemonEntry.evSettings.evSpeed
            )

            customEvs.forEach { (stat, ev) ->
                pokemon.evs.add(stat, ev)
            }

            // Try to notify the player
            val player = pokemon.getOwnerPlayer() as? ServerPlayerEntity
            player?.sendMessage(
                Text.literal("Custom EVs applied to ${pokemon.species.name} based on defeating $opponentSpeciesName: ${
                    customEvs.entries.joinToString { "${it.key}: ${it.value}" }
                }"),
                false
            )

            logDebug("Custom EVs applied for ${pokemon.species.name} based on opponent's $opponentSpeciesName.")
        } else {
            logDebug("No custom EVs found for opponent's species: $opponentSpeciesName.")
        }
    }

    private fun cleanupBattle(battleId: UUID) {
        ongoingBattles.remove(battleId)
        pendingCleanupBattles.remove(battleId)
        logDebug("Cleaned up battle tracking for Battle ID: $battleId")
    }

    private fun findBattleIdByPokemon(pokemon: Pokemon): UUID? {
        return ongoingBattles.values.find { battleInfo ->
            battleInfo.actors.any { actor ->
                actor.pokemonList.any { battlePokemon ->
                    battlePokemon.effectedPokemon.uuid == pokemon.uuid
                }
            }
        }?.battleId
    }

    private fun onServerTick(server: MinecraftServer) {
        val battlesToCleanup = mutableListOf<UUID>()

        // Check pending cleanup battles
        for (battleId in pendingCleanupBattles) {
            val battleInfo = ongoingBattles[battleId] ?: continue

            // If values have been applied, we can clean up
            if (battleInfo.valuesApplied) {
                battlesToCleanup.add(battleId)
            } else {
                // Attempt to apply values if not yet applied
                applyValuesAfterBattle(battleId)
            }
        }

        // Clean up battles where values have been applied
        battlesToCleanup.forEach { battleId ->
            cleanupBattle(battleId)
        }
    }
}
