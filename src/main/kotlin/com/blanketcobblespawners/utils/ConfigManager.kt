package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners.spawnerValidPositions
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

data class GlobalConfig(
    var version: String = "1.0.0",                 // Default version
    var debugEnabled: Boolean = false,             // Enable or disable debug logging
    var cullSpawnerPokemonOnServerStop: Boolean = true,
    var showUnimplementedPokemonInGui: Boolean = false
)

// Subcategory for capture-related settings
data class CaptureSettings(
    var isCatchable: Boolean,                      // Is this Pokémon catchable?
    var restrictCaptureToLimitedBalls: Boolean,    // Restrict capture to specific Poké Balls
    var requiredPokeBalls: List<String> = listOf("safari_ball")  // Default to safari_ball
)

// Subcategory for IV settings
data class IVSettings(
    var allowCustomIvs: Boolean,                    // Allow custom IVs
    var minIVHp: Int,
    var maxIVHp: Int,
    var minIVAttack: Int,
    var maxIVAttack: Int,
    var minIVDefense: Int,
    var maxIVDefense: Int,
    var minIVSpecialAttack: Int,
    var maxIVSpecialAttack: Int,
    var minIVSpecialDefense: Int,
    var maxIVSpecialDefense: Int,
    var minIVSpeed: Int,
    var maxIVSpeed: Int
)

// Subcategory for EV settings
data class EVSettings(
    var allowCustomEvsOnDefeat: Boolean,            // Allow custom EVs on defeat
    var evHp: Int,
    var evAttack: Int,
    var evDefense: Int,
    var evSpecialAttack: Int,
    var evSpecialDefense: Int,
    var evSpeed: Int
)

// Subcategory for spawn time and weather
data class SpawnSettings(
    var spawnTime: String = "ALL",                  // Time of day for spawning
    var spawnWeather: String = "ALL"                // Weather conditions for spawning
)

data class PokemonSpawnEntry(
    var pokemonName: String,                        // Pokémon species
    var spawnChance: Double,                        // Chance of spawning
    var shinyChance: Double,                        // Chance of shiny
    var minLevel: Int,                              // Minimum level
    var maxLevel: Int,                              // Maximum level
    var captureSettings: CaptureSettings,           // Capture restrictions and settings
    var ivSettings: IVSettings,                     // IV-related configurations
    var evSettings: EVSettings,                     // EV-related configurations
    var spawnSettings: SpawnSettings                // Spawn conditions (time and weather)
)

data class SpawnRadius(
    var width: Int = 4,
    var height: Int = 4
)

data class SpawnerData(
    val spawnerPos: BlockPos,
    var spawnerName: String,
    var selectedPokemon: MutableList<PokemonSpawnEntry> = mutableListOf(),
    val dimension: String,
    var spawnTimerTicks: Long = 200,
    var spawnRadius: SpawnRadius = SpawnRadius(),   // Use SpawnRadius object here
    var spawnLimit: Int = 4,
    var visible: Boolean = true,                   // Visibility flag
    var showParticles: Boolean = true              // Particle effects flag
)

data class ConfigData(
    var globalConfig: GlobalConfig = GlobalConfig(),
    var spawners: MutableList<SpawnerData> = mutableListOf()
)

object ConfigManager {

    private val logger = LoggerFactory.getLogger("ConfigManager")
    private val configFile: Path = Paths.get("config", "BlanketCobbleSpawners", "config.json")
    private val backupFile: Path = Paths.get("config", "BlanketCobbleSpawners", "config_copy.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    var configData: ConfigData = ConfigData()
    val spawners: ConcurrentHashMap<BlockPos, SpawnerData> = ConcurrentHashMap()
    private val lastSpawnTicks: ConcurrentHashMap<BlockPos, Long> = ConcurrentHashMap()

    // Custom logger function based on config
    fun logDebug(message: String) {
        if (configData.globalConfig.debugEnabled) {
            println(message)
        }
    }

    fun loadConfig() {
        loadGlobalAndSpawnerData()
    }

    private fun loadGlobalAndSpawnerData() {
        if (!Files.exists(configFile)) {
            println("Config file does not exist. Creating default config.")
            createDefaultConfigData()
            return
        }

        val jsonContent = Files.readString(configFile)
        if (jsonContent.isBlank()) {
            println("Config file is empty. Creating default config.")
            createDefaultConfigData()
            return
        }

        try {
            val jsonElement = JsonParser.parseString(jsonContent)
            if (!jsonElement.isJsonObject) {
                println("Config file is invalid. Creating default config.")
                createDefaultConfigData()
                return
            }

            val jsonObject = jsonElement.asJsonObject
            // Check if version field exists and matches the current version
            val currentVersion = "1.0.0"
            if (!jsonObject.has("globalConfig") || !jsonObject.getAsJsonObject("globalConfig").has("version") ||
                jsonObject.getAsJsonObject("globalConfig").get("version").asString != currentVersion) {
                println("Config version is missing or outdated. Backing up and creating a new default config.")
                backupConfigFile()
                createDefaultConfigData()
                return
            }

            configData = gson.fromJson(jsonContent, ConfigData::class.java)
            logDebug("Loaded config data: $configData")

            // Load spawners into the concurrent hash map
            configData.spawners.forEach { spawners[it.spawnerPos] = it }
        } catch (e: Exception) {
            println("Error loading config data: ${e.message}")
            logger.error("Error loading config data: ${e.message}")
            createDefaultConfigData()
        }
    }

    private fun saveConfigData() {
        try {
            configData.spawners = spawners.values.toMutableList() // Update spawners in configData

            Files.writeString(
                configFile,
                gson.toJson(configData),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            logDebug("Successfully saved config data to $configFile")
        } catch (e: Exception) {
            logger.error("Error saving config data: ${e.message}")
        }
    }

    private fun createDefaultConfigData() {
        try {
            Files.createDirectories(configFile.parent)
            configData = ConfigData() // Use default values for global config and spawners
            Files.writeString(
                configFile,
                gson.toJson(configData),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            logDebug("Default config data created.")
        } catch (e: Exception) {
            logger.error("Error creating default config data: ${e.message}")
        }
    }

    private fun backupConfigFile() {
        try {
            if (Files.exists(configFile)) {
                Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING)
                logDebug("Backup created at $backupFile")
            }
        } catch (e: Exception) {
            logger.error("Failed to create backup: ${e.message}")
        }
    }


    fun loadSpawnerData() {
        logDebug("Loading spawner data...")
        loadGlobalAndSpawnerData() // Combined loading for spawners and global config
    }

    fun saveSpawnerData() {
        logDebug("Saving spawner data...")
        saveConfigData() // Combined saving for spawners and global config
    }

    fun reloadSpawnerData() {
        logDebug("Reloading spawner data from file.")
        spawners.clear()
        spawnerValidPositions.clear() // Clear cached spawn locations for spawner logic
        ParticleUtils.cachedValidPositions.clear() // Clear cached valid positions for particle visualization
        loadSpawnerData()
        logDebug("Reloading complete.")
    }

    fun updateLastSpawnTick(spawnerPos: BlockPos, tick: Long) {
        logDebug("Updated last spawn tick for spawner at position: $spawnerPos")
        lastSpawnTicks[spawnerPos] = tick
    }

    fun getLastSpawnTick(spawnerPos: BlockPos): Long {
        return lastSpawnTicks[spawnerPos] ?: 0L
    }

    // Utility method to update a PokemonSpawnEntry
    fun updatePokemonSpawnEntry(
        spawnerPos: BlockPos,
        pokemonName: String,
        update: (PokemonSpawnEntry) -> Unit
    ): PokemonSpawnEntry? {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return null
        }

        val selectedEntry = spawnerData.selectedPokemon.find { it.pokemonName.equals(pokemonName, ignoreCase = true) }
            ?: run {
                logDebug("Pokemon '$pokemonName' not found in spawner at $spawnerPos.")
                return null
            }

        update(selectedEntry)
        saveSpawnerData()
        return selectedEntry
    }

    // Utility method to get a PokemonSpawnEntry
    fun getPokemonSpawnEntry(spawnerPos: BlockPos, pokemonName: String): PokemonSpawnEntry? {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return null
        }
        return spawnerData.selectedPokemon.find { it.pokemonName.equals(pokemonName, ignoreCase = true) }
            ?: run {
                logDebug("Pokemon '$pokemonName' not found in spawner at $spawnerPos.")
                null
            }
    }

    // Utility method to add a PokemonSpawnEntry
    fun addPokemonSpawnEntry(spawnerPos: BlockPos, entry: PokemonSpawnEntry): Boolean {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return false
        }

        if (spawnerData.selectedPokemon.any { it.pokemonName.equals(entry.pokemonName, ignoreCase = true) }) {
            logDebug("Pokemon '${entry.pokemonName}' is already selected for spawner at $spawnerPos.")
            return false
        }

        spawnerData.selectedPokemon.add(entry)
        saveSpawnerData()
        logDebug("Added Pokémon '${entry.pokemonName}' to spawner at $spawnerPos.")
        return true
    }

    // Utility method to remove a PokemonSpawnEntry
    fun removePokemonSpawnEntry(spawnerPos: BlockPos, pokemonName: String): Boolean {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return false
        }

        val removed = spawnerData.selectedPokemon.removeIf { it.pokemonName.equals(pokemonName, ignoreCase = true) }

        return if (removed) {
            saveSpawnerData()
            logDebug("Removed Pokémon '$pokemonName' from spawner at $spawnerPos.")
            true
        } else {
            logDebug("Pokemon '$pokemonName' not found in spawner at $spawnerPos.")
            false
        }
    }
}
