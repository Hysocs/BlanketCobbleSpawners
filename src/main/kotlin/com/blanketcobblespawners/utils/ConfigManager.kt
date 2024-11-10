package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners.spawnerValidPositions
import com.google.gson.*
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class GlobalConfig(
    var version: String = "1.0.3",
    var debugEnabled: Boolean = false,
    var cullSpawnerPokemonOnServerStop: Boolean = true,
    var showUnimplementedPokemonInGui: Boolean = false,
    var showFormsInGui: Boolean = false
)

data class CaptureSettings(
    var isCatchable: Boolean = true,
    var restrictCaptureToLimitedBalls: Boolean = false,
    var requiredPokeBalls: List<String> = listOf("safari_ball")
)

data class IVSettings(
    var allowCustomIvs: Boolean = false,
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

data class EVSettings(
    var allowCustomEvsOnDefeat: Boolean = false,
    var evHp: Int = 0,
    var evAttack: Int = 0,
    var evDefense: Int = 0,
    var evSpecialAttack: Int = 0,
    var evSpecialDefense: Int = 0,
    var evSpeed: Int = 0
)

data class SpawnSettings(
    var spawnTime: String = "ALL",
    var spawnWeather: String = "ALL"
)

data class SizeSettings(
    var allowCustomSize: Boolean = false,
    var minSize: Float = 1.0f,
    var maxSize: Float = 1.0f
)

data class HeldItemsOnSpawn(
    var allowHeldItemsOnSpawn: Boolean = false,
    var itemsWithChance: Map<String, Double> = mapOf(
        "minecraft:cobblestone" to 0.1,
        "cobblemon:pokeball" to 100.0
    )
)


data class PokemonSpawnEntry(
    val pokemonName: String,
    var formName: String? = null,
    var spawnChance: Double,
    var shinyChance: Double,
    var minLevel: Int,
    var maxLevel: Int,
    var sizeSettings: SizeSettings = SizeSettings(),
    val captureSettings: CaptureSettings,
    val ivSettings: IVSettings,
    val evSettings: EVSettings,
    val spawnSettings: SpawnSettings,
    var heldItemsOnSpawn: HeldItemsOnSpawn = HeldItemsOnSpawn()
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
    var spawnRadius: SpawnRadius = SpawnRadius(),
    var spawnLimit: Int = 4,
    var spawnAmountPerSpawn: Int = 1,
    var visible: Boolean = true,
    var showParticles: Boolean = true
)

data class ConfigData(
    var globalConfig: GlobalConfig = GlobalConfig(),
    var spawners: MutableList<SpawnerData> = mutableListOf()
)

object ConfigManager {

    private val logger = LoggerFactory.getLogger("ConfigManager")
    private val configFile: Path = Paths.get("config", "BlanketCobbleSpawners", "config.json")
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(BlockPos::class.java, object : JsonDeserializer<BlockPos>, JsonSerializer<BlockPos> {
            override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): BlockPos {
                val obj = json.asJsonObject
                val x = obj.get("x")?.asInt ?: 0
                val y = obj.get("y")?.asInt ?: 0
                val z = obj.get("z")?.asInt ?: 0
                return BlockPos(x, y, z)
            }

            override fun serialize(src: BlockPos, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
                val obj = JsonObject()
                obj.addProperty("x", src.x)
                obj.addProperty("y", src.y)
                obj.addProperty("z", src.z)
                return obj
            }
        })


        .create()

    var configData: ConfigData = ConfigData()
    val spawners: ConcurrentHashMap<BlockPos, SpawnerData> = ConcurrentHashMap()
    val lastSpawnTicks: ConcurrentHashMap<BlockPos, Long> = ConcurrentHashMap()

    fun logDebug(message: String) {
        if (configData.globalConfig.debugEnabled) {
            println("[DEBUG] $message")
        }
    }

    private fun roundToOneDecimal(value: Float): Float {
        return (value * 10).roundToInt() / 10f
    }

    fun loadConfig() {
        loadGlobalSpawnerData()
    }

    private fun loadGlobalSpawnerData() {
        val currentVersion = "1.0.3"

        if (!Files.exists(configFile)) {
            createDefaultConfigData()
            return
        }

        val jsonContent = Files.readString(configFile)
        if (jsonContent.isBlank()) {
            createDefaultConfigData()
            return
        }

        try {
            val oldConfigJson = JsonParser.parseString(jsonContent).asJsonObject

            val oldVersion = oldConfigJson["globalConfig"]?.asJsonObject?.get("version")?.asString
            if (oldVersion == null || oldVersion != currentVersion) {
                backupConfigFile()

                val migratedConfigJson = migrateConfig(oldConfigJson)

                configData = gson.fromJson(migratedConfigJson, ConfigData::class.java)

                configData.spawners.forEach { spawner ->
                    spawner.selectedPokemon.forEach { pokemon ->
                        if (pokemon.sizeSettings == null) {
                            pokemon.sizeSettings = SizeSettings()
                        } else {
                            pokemon.sizeSettings.minSize = roundToOneDecimal(pokemon.sizeSettings.minSize)
                            pokemon.sizeSettings.maxSize = roundToOneDecimal(pokemon.sizeSettings.maxSize)
                        }
                    }
                }

                configData.spawners.forEach { spawners[it.spawnerPos] = it }

                saveConfigData()
                logDebug("Config migration completed successfully.")
            } else {
                configData = gson.fromJson(jsonContent, ConfigData::class.java)

                configData.spawners.forEach { spawner ->
                    spawner.selectedPokemon.forEach { pokemon ->
                        if (pokemon.sizeSettings == null) {
                            pokemon.sizeSettings = SizeSettings()
                        } else {
                            pokemon.sizeSettings.minSize = roundToOneDecimal(pokemon.sizeSettings.minSize)
                            pokemon.sizeSettings.maxSize = roundToOneDecimal(pokemon.sizeSettings.maxSize)
                        }
                    }
                }

                configData.spawners.forEach { spawners[it.spawnerPos] = it }

                logDebug("Loaded config data with SizeSettings: $configData")
            }

        } catch (e: Exception) {
            logger.error("Error loading or migrating config data: ${e.message}")
            backupConfigFile()
            createDefaultConfigData()
        }
    }

    private fun migrateConfig(oldConfigJson: JsonObject): JsonObject {
        val currentVersion = "1.0.3"
        val migratedConfig = gson.toJsonTree(ConfigData()).asJsonObject

        val oldGlobalConfig = oldConfigJson.getAsJsonObject("globalConfig")
        val newGlobalConfig = migratedConfig.getAsJsonObject("globalConfig")

        oldGlobalConfig.entrySet().forEach { (key, value) ->
            newGlobalConfig.add(key, value)
        }

        if (!newGlobalConfig.has("showUnimplementedPokemonInGui")) {
            newGlobalConfig.addProperty("showUnimplementedPokemonInGui", false)
        }
        if (!newGlobalConfig.has("showFormsInGui")) {
            newGlobalConfig.addProperty("showFormsInGui", false)
        }

        newGlobalConfig.addProperty("version", currentVersion)

        val oldSpawners = oldConfigJson.getAsJsonArray("spawners")
        val newSpawners = migratedConfig.getAsJsonArray("spawners")

        oldSpawners.forEach { spawnerElement ->
            val oldSpawner = spawnerElement.asJsonObject
            val newSpawner = JsonObject()

            val oldSpawnerPos = oldSpawner.getAsJsonObject("spawnerPos")
            val newSpawnerPos = JsonObject()

            val x = oldSpawnerPos.get("x")?.asInt
                ?: oldSpawnerPos.get("field_11175")?.asInt
                ?: 0
            val y = oldSpawnerPos.get("y")?.asInt
                ?: oldSpawnerPos.get("field_11174")?.asInt
                ?: 0
            val z = oldSpawnerPos.get("z")?.asInt
                ?: oldSpawnerPos.get("field_11173")?.asInt
                ?: 0

            newSpawnerPos.addProperty("x", x)
            newSpawnerPos.addProperty("y", y)
            newSpawnerPos.addProperty("z", z)
            newSpawner.add("spawnerPos", newSpawnerPos)

            newSpawner.add("spawnerName", oldSpawner.get("spawnerName") ?: JsonPrimitive("default_spawner"))
            newSpawner.add("selectedPokemon", oldSpawner.get("selectedPokemon") ?: JsonArray())
            newSpawner.add("dimension", oldSpawner.get("dimension") ?: JsonPrimitive("minecraft:overworld"))
            newSpawner.add("spawnTimerTicks", oldSpawner.get("spawnTimerTicks") ?: JsonPrimitive(200))
            newSpawner.add("spawnRadius", oldSpawner.get("spawnRadius") ?: JsonObject())
            newSpawner.add("spawnLimit", oldSpawner.get("spawnLimit") ?: JsonPrimitive(4))
            newSpawner.addProperty("spawnAmountPerSpawn", 1)
            newSpawner.add("visible", oldSpawner.get("visible") ?: JsonPrimitive(true))
            newSpawner.add("showParticles", oldSpawner.get("showParticles") ?: JsonPrimitive(true))

            val selectedPokemonArray = newSpawner.getAsJsonArray("selectedPokemon")
            selectedPokemonArray.forEach { pokemonElement ->
                val pokemon = pokemonElement.asJsonObject
                applyDefaultsToPokemonEntry(pokemon)
            }

            newSpawners.add(newSpawner)
        }

        return migratedConfig
    }


    private fun applyDefaultsToPokemonEntry(pokemon: JsonObject) {
        if (!pokemon.has("sizeSettings")) {
            val sizeSettings = JsonObject()
            sizeSettings.addProperty("allowCustomSize", false)
            sizeSettings.addProperty("minSize", 1.0f)
            sizeSettings.addProperty("maxSize", 1.0f)
            pokemon.add("sizeSettings", sizeSettings)
        }

        if (!pokemon.has("captureSettings")) {
            val captureSettings = JsonObject()
            captureSettings.addProperty("isCatchable", true)
            captureSettings.addProperty("restrictCaptureToLimitedBalls", false)
            captureSettings.add("requiredPokeBalls", JsonArray().apply { add("safari_ball") })
            pokemon.add("captureSettings", captureSettings)
        }

        if (!pokemon.has("heldItemsOnSpawn")) {
            val heldItemsOnSpawn = JsonObject()
            heldItemsOnSpawn.addProperty("allowHeldItemsOnSpawn", false)

            val itemsWithChance = JsonObject()
            itemsWithChance.addProperty("minecraft:cobblestone", 0.1)
            itemsWithChance.addProperty("cobblemon:poke_ball", 100.0)

            heldItemsOnSpawn.add("itemsWithChance", itemsWithChance)
            pokemon.add("heldItemsOnSpawn", heldItemsOnSpawn)
        }
    }




    fun saveConfigData() {
        try {
            configData.spawners = spawners.values.toMutableList()
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
            configData = ConfigData()
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
        loadGlobalSpawnerData()
    }

    fun saveSpawnerData() {
        logDebug("Saving spawner data...")
        saveConfigData()
    }

    fun reloadSpawnerData() {
        logDebug("Reloading spawner data from file.")
        spawners.clear()
        spawnerValidPositions.clear()
        lastSpawnTicks.clear()
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

    fun updateSpawner(spawnerPos: BlockPos, update: (SpawnerData) -> Unit): SpawnerData? {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return null
        }

        update(spawnerData)
        saveSpawnerData()
        return spawnerData
    }

    fun getSpawner(spawnerPos: BlockPos): SpawnerData? {
        return spawners[spawnerPos]
    }

    fun removeSpawner(spawnerPos: BlockPos): Boolean {
        val removed = spawners.remove(spawnerPos) != null
        if (removed) {
            lastSpawnTicks.remove(spawnerPos)
            saveSpawnerData()
            logDebug("Removed spawner at position $spawnerPos.")
            return true
        } else {
            logDebug("Spawner not found at position $spawnerPos")
            return false
        }
    }

    fun updatePokemonSpawnEntry(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        update: (PokemonSpawnEntry) -> Unit
    ): PokemonSpawnEntry? {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return null
        }

        val selectedEntry = spawnerData.selectedPokemon.find {
            it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                    (it.formName.equals(formName, ignoreCase = true) || (it.formName == null && formName == null))
        } ?: run {
            logDebug("Pokémon '$pokemonName' with form '$formName' not found in spawner at $spawnerPos.")
            return null
        }

        update(selectedEntry)

        selectedEntry.sizeSettings.minSize = roundToOneDecimal(selectedEntry.sizeSettings.minSize)
        selectedEntry.sizeSettings.maxSize = roundToOneDecimal(selectedEntry.sizeSettings.maxSize)

        saveSpawnerData()
        return selectedEntry
    }

    fun getPokemonSpawnEntry(spawnerPos: BlockPos, pokemonName: String, formName: String?): PokemonSpawnEntry? {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return null
        }
        return spawnerData.selectedPokemon.find {
            it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                    (it.formName.equals(formName, ignoreCase = true) || (it.formName == null && formName == null))
        } ?: run {
            logDebug("Pokémon '$pokemonName' with form '$formName' not found in spawner at $spawnerPos.")
            null
        }
    }

    fun addPokemonSpawnEntry(spawnerPos: BlockPos, entry: PokemonSpawnEntry): Boolean {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return false
        }

        if (spawnerData.selectedPokemon.any {
                it.pokemonName.equals(entry.pokemonName, ignoreCase = true) &&
                        (it.formName.equals(entry.formName, ignoreCase = true) || (it.formName == null && entry.formName == null))
            }) {
            logDebug("Pokémon '${entry.pokemonName}' with form '${entry.formName}' is already selected for spawner at $spawnerPos.")
            return false
        }

        spawnerData.selectedPokemon.add(entry)
        saveSpawnerData()
        logDebug("Added Pokémon '${entry.pokemonName}' with form '${entry.formName}' to spawner at $spawnerPos.")
        return true
    }

    fun removePokemonSpawnEntry(spawnerPos: BlockPos, pokemonName: String, formName: String?): Boolean {
        val spawnerData = spawners[spawnerPos] ?: run {
            logDebug("Spawner not found at position $spawnerPos")
            return false
        }

        val removed = spawnerData.selectedPokemon.removeIf {
            it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                    (it.formName.equals(formName, ignoreCase = true) || (it.formName == null && formName == null))
        }

        return if (removed) {
            saveSpawnerData()
            logDebug("Removed Pokémon '$pokemonName' with form '$formName' from spawner at $spawnerPos.")
            true
        } else {
            logDebug("Pokémon '$pokemonName' with form '$formName' not found in spawner at $spawnerPos.")
            false
        }
    }
}
