package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners.spawnerValidPositions
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

// Data classes for configuration

data class GlobalConfig(
    var version: String = "1.0.1",                 // Updated version
    var debugEnabled: Boolean = false,             // Enable or disable debug logging
    var cullSpawnerPokemonOnServerStop: Boolean = true,
    var showUnimplementedPokemonInGui: Boolean = false
)

// Subcategory for capture-related settings
data class CaptureSettings(
    var isCatchable: Boolean = true,                      // Is this Pokémon catchable?
    var restrictCaptureToLimitedBalls: Boolean = false,   // Restrict capture to specific Poké Balls
    var requiredPokeBalls: List<String> = listOf("safari_ball")  // Default to safari_ball
)

// Subcategory for IV settings
data class IVSettings(
    var allowCustomIvs: Boolean = false,                    // Allow custom IVs
    var minIVHp: Int = 0,
    var maxIVHp: Int = 31,
    var minIVAttack: Int = 0,
    var maxIVAttack: Int = 31,
    var minIVDefense: Int = 0,
    var maxIVDefense: Int = 31,
    var minIVSpecialAttack: Int = 0,
    var maxIVSpecialAttack: Int = 31,
    var minIVSpecialDefense: Int = 0,
    var maxIVSpecialDefense: Int = 31,
    var minIVSpeed: Int = 0,
    var maxIVSpeed: Int = 31
)

// Subcategory for EV settings
data class EVSettings(
    var allowCustomEvsOnDefeat: Boolean = false,            // Allow custom EVs on defeat
    var evHp: Int = 0,
    var evAttack: Int = 0,
    var evDefense: Int = 0,
    var evSpecialAttack: Int = 0,
    var evSpecialDefense: Int = 0,
    var evSpeed: Int = 0
)

// Subcategory for spawn time and weather
data class SpawnSettings(
    var spawnTime: String = "ALL",                  // Time of day for spawning
    var spawnWeather: String = "ALL"                // Weather conditions for spawning
)

data class PokemonSpawnEntry(
    var pokemonName: String,                        // Pokémon species
    var spawnChance: Double = 100.0,                // Chance of spawning
    var shinyChance: Double = 0.0,                  // Chance of shiny
    var minLevel: Int = 1,                          // Minimum level
    var maxLevel: Int = 100,                        // Maximum level
    var captureSettings: CaptureSettings = CaptureSettings(),           // Capture restrictions and settings
    var ivSettings: IVSettings = IVSettings(),                     // IV-related configurations
    var evSettings: EVSettings = EVSettings(),                     // EV-related configurations
    var spawnSettings: SpawnSettings = SpawnSettings()                // Spawn conditions (time and weather)
)

data class SpawnRadius(
    var width: Int = 4,
    var height: Int = 4
)

data class SpawnerData(
    val spawnerPos: BlockPos,
    var spawnerName: String = "default_spawner",
    var selectedPokemon: MutableList<PokemonSpawnEntry> = mutableListOf(),
    val dimension: String = "minecraft:overworld",
    var spawnTimerTicks: Long = 200,
    var spawnRadius: SpawnRadius = SpawnRadius(),   // Use SpawnRadius object here
    var spawnLimit: Int = 4,
    var visible: Boolean = true,                   // Visibility flag
    var showParticles: Boolean = true              // Particle effects flag
)

// New data class for regions
data class RegionData(
    val pos1: BlockPos,
    val pos2: BlockPos,
    var regionName: String = "default_region",
    var selectedPokemon: MutableList<PokemonSpawnEntry> = mutableListOf(),
    val dimension: String = "minecraft:overworld",
    var spawnTimerTicks: Long = 200,
    var spawnLimit: Int = 4,
    var visible: Boolean = true,                   // Visibility flag
    var showParticles: Boolean = true              // Particle effects flag
)

data class ConfigData(
    var globalConfig: GlobalConfig = GlobalConfig(),
    var spawners: MutableList<SpawnerData> = mutableListOf(),
    //var regions: MutableList<RegionData> = mutableListOf() // Added regions field
)

object ConfigManager {

    private val logger = LoggerFactory.getLogger("ConfigManager")
    private val configFile: Path = Paths.get("config", "BlanketCobbleSpawners", "config.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val objectMapper = ObjectMapper()

    var configData: ConfigData = ConfigData()
    val spawners: ConcurrentHashMap<BlockPos, SpawnerData> = ConcurrentHashMap()
    val regions: ConcurrentHashMap<String, RegionData> = ConcurrentHashMap() // Keyed by regionName
    private val lastSpawnTicks: ConcurrentHashMap<BlockPos, Long> = ConcurrentHashMap()
    private val lastRegionSpawnTicks: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    // Custom logger function based on config
    fun logDebug(message: String) {
        if (configData.globalConfig.debugEnabled) {
            println(message)
        }
    }

    fun loadConfig() {
        loadGlobalSpawnerAndRegionData()
    }

    private fun loadGlobalSpawnerAndRegionData() {
        val currentVersion = "1.0.1" // Update this to match GlobalConfig's default version

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
            // Read old config as JsonNode
            val oldConfigNode = objectMapper.readTree(jsonContent)

            // Check if version field exists and matches the current version
            val oldVersion = oldConfigNode.get("globalConfig")?.get("version")?.asText()
            if (oldVersion == null || oldVersion != currentVersion) {
                println("Config version is missing or outdated. Backing up current config and attempting to merge.")

                // Backup current config
                backupConfigFile()

                // Generate default config and read as JsonNode
                val defaultConfigData = ConfigData()
                val defaultConfigJson = gson.toJson(defaultConfigData)
                val defaultConfigNode = objectMapper.readTree(defaultConfigJson)

                // Merge old config into default config
                mergeJsonNodes(defaultConfigNode, oldConfigNode)

                // Update version
                if (defaultConfigNode.has("globalConfig") && defaultConfigNode.get("globalConfig") is ObjectNode) {
                    (defaultConfigNode.get("globalConfig") as ObjectNode).put("version", currentVersion)
                }

                // Convert merged JsonNode back to ConfigData
                val mergedConfigJson = objectMapper.writeValueAsString(defaultConfigNode)
                configData = gson.fromJson(mergedConfigJson, ConfigData::class.java)

                // Load spawners into the concurrent hash map
                configData.spawners.forEach { spawners[it.spawnerPos] = it }
                // Load regions into the concurrent hash map
                //configData.regions.forEach { regions[it.regionName] = it }

                // Save the merged config
                saveConfigData()
                logDebug("Config merging completed successfully.")
            } else {
                // Version matches, proceed normally
                configData = gson.fromJson(jsonContent, ConfigData::class.java)
                logDebug("Loaded config data: $configData")

                // Load spawners into the concurrent hash map
                configData.spawners.forEach { spawners[it.spawnerPos] = it }
                // Load regions into the concurrent hash map
                //configData.regions.forEach { regions[it.regionName] = it }
            }

        } catch (e: Exception) {
            println("Error loading or merging config data: ${e.message}")
            logger.error("Error loading or merging config data: ${e.message}")
            backupConfigFile()
            createDefaultConfigData()
        }
    }

    private fun mergeJsonNodes(targetNode: JsonNode, sourceNode: JsonNode) {
        if (sourceNode is ObjectNode) {
            sourceNode.fields().forEachRemaining { entry ->
                val fieldName = entry.key
                val sourceValue = entry.value

                if (targetNode is ObjectNode) {
                    val targetValue = targetNode.get(fieldName)

                    if (targetValue != null && targetValue.isObject && sourceValue.isObject) {
                        // Recursive merge for nested objects
                        mergeJsonNodes(targetValue, sourceValue)
                    } else {
                        (targetNode as ObjectNode).set<JsonNode>(fieldName, sourceValue)
                    }
                }
            }
        }
    }

    private fun saveConfigData() {
        try {
            configData.spawners = spawners.values.toMutableList() // Update spawners in configData
            //configData.regions = regions.values.toMutableList()   // Update regions in configData

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
            configData = ConfigData() // Use default values for global config, spawners, and regions
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
                val backupFileWithTimestamp = Paths.get(
                    "config",
                    "BlanketCobbleSpawners",
                    "config_backup_${System.currentTimeMillis()}.json"
                )
                Files.copy(configFile, backupFileWithTimestamp, StandardCopyOption.REPLACE_EXISTING)
                logDebug("Backup created at $backupFileWithTimestamp")
            }
        } catch (e: Exception) {
            logger.error("Failed to create backup: ${e.message}")
        }
    }

    fun loadSpawnerData() {
        logDebug("Loading spawner data...")
        loadGlobalSpawnerAndRegionData() // Combined loading for spawners, regions, and global config
    }

    fun saveSpawnerData() {
        logDebug("Saving spawner data...")
        saveConfigData() // Combined saving for spawners, regions, and global config
    }

    fun reloadSpawnerData() {
        logDebug("Reloading spawner and region data from file.")
        spawners.clear()
        regions.clear()
        spawnerValidPositions.clear() // Clear cached spawn locations for spawner logic
        // Clear cached valid positions for particle visualization if applicable
        // ParticleUtils.cachedValidPositions.clear()
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

    fun updateLastRegionSpawnTick(regionName: String, tick: Long) {
        logDebug("Updated last spawn tick for region: $regionName")
        lastRegionSpawnTicks[regionName] = tick
    }

    fun getLastRegionSpawnTick(regionName: String): Long {
        return lastRegionSpawnTicks[regionName] ?: 0L
    }

    // Spawner utility methods

    // Add a new spawner
    fun addSpawner(spawnerPos: BlockPos, dimension: String): Boolean {
        if (spawners.containsKey(spawnerPos)) {
            logDebug("Spawner at position $spawnerPos already exists.")
            return false
        }

        val spawnerData = SpawnerData(
            spawnerPos = spawnerPos,
            dimension = dimension
        )
        spawners[spawnerPos] = spawnerData
        saveSpawnerData()
        logDebug("Added spawner at position $spawnerPos.")
        return true
    }

    // Update a spawner's data
    fun updateSpawner(spawnerPos: BlockPos, update: (SpawnerData) -> Unit): SpawnerData? {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return null
        }

        update(spawnerData)
        saveSpawnerData()
        return spawnerData
    }

    // Get a spawner's data
    fun getSpawner(spawnerPos: BlockPos): SpawnerData? {
        return spawners[spawnerPos]
    }

    // Remove a spawner
    fun removeSpawner(spawnerPos: BlockPos): Boolean {
        val removed = spawners.remove(spawnerPos) != null
        if (removed) {
            saveSpawnerData()
            logDebug("Removed spawner at position $spawnerPos.")
            return true
        } else {
            logDebug("Spawner not found at position $spawnerPos")
            return false
        }
    }

    // Utility method to update a PokemonSpawnEntry in a spawner
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

    // Utility method to get a PokemonSpawnEntry from a spawner
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

    // Utility method to add a PokemonSpawnEntry to a spawner
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

    // Utility method to remove a PokemonSpawnEntry from a spawner
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

    // Utility methods for RegionData

    // Add a new region
    fun addRegion(regionName: String, pos1: BlockPos, pos2: BlockPos, dimension: String): Boolean {
        if (regions.containsKey(regionName)) {
            logDebug("Region '$regionName' already exists.")
            return false
        }

        val regionData = RegionData(
            pos1 = pos1,
            pos2 = pos2,
            regionName = regionName,
            dimension = dimension
        )
        regions[regionName] = regionData
        saveConfigData()
        logDebug("Added region '$regionName' with positions $pos1 and $pos2.")
        return true
    }

    // Update a region's data
    fun updateRegion(regionName: String, update: (RegionData) -> Unit): RegionData? {
        val regionData = regions[regionName] ?: run {
            logDebug("Region '$regionName' not found.")
            return null
        }

        update(regionData)
        saveConfigData()
        return regionData
    }

    // Get a region's data
    fun getRegion(regionName: String): RegionData? {
        return regions[regionName]
    }

    // Remove a region
    fun removeRegion(regionName: String): Boolean {
        val removed = regions.remove(regionName) != null
        if (removed) {
            saveConfigData()
            logDebug("Removed region '$regionName'.")
            return true
        } else {
            logDebug("Region '$regionName' not found.")
            return false
        }
    }

    // Utility method to update a PokemonSpawnEntry in a region
    fun updateRegionPokemonSpawnEntry(
        regionName: String,
        pokemonName: String,
        update: (PokemonSpawnEntry) -> Unit
    ): PokemonSpawnEntry? {
        val regionData = regions[regionName] ?: run {
            logDebug("Region '$regionName' not found.")
            return null
        }

        val selectedEntry = regionData.selectedPokemon.find { it.pokemonName.equals(pokemonName, ignoreCase = true) }
            ?: run {
                logDebug("Pokemon '$pokemonName' not found in region '$regionName'.")
                return null
            }

        update(selectedEntry)
        saveConfigData()
        return selectedEntry
    }

    // Utility method to add a PokemonSpawnEntry to a region
    fun addRegionPokemonSpawnEntry(regionName: String, entry: PokemonSpawnEntry): Boolean {
        val regionData = regions[regionName] ?: run {
            logDebug("Region '$regionName' not found.")
            return false
        }

        if (regionData.selectedPokemon.any { it.pokemonName.equals(entry.pokemonName, ignoreCase = true) }) {
            logDebug("Pokemon '${entry.pokemonName}' is already selected for region '$regionName'.")
            return false
        }

        regionData.selectedPokemon.add(entry)
        saveConfigData()
        logDebug("Added Pokémon '${entry.pokemonName}' to region '$regionName'.")
        return true
    }

    // Utility method to remove a PokemonSpawnEntry from a region
    fun removeRegionPokemonSpawnEntry(regionName: String, pokemonName: String): Boolean {
        val regionData = regions[regionName] ?: run {
            logDebug("Region '$regionName' not found.")
            return false
        }

        val removed = regionData.selectedPokemon.removeIf { it.pokemonName.equals(pokemonName, ignoreCase = true) }

        return if (removed) {
            saveConfigData()
            logDebug("Removed Pokémon '$pokemonName' from region '$regionName'.")
            true
        } else {
            logDebug("Pokemon '$pokemonName' not found in region '$regionName'.")
            false
        }
    }
}
