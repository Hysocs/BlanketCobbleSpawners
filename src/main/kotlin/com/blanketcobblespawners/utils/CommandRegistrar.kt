// File: CommandRegistrar.kt
package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.gui.SpawnerListGui
import com.blanketcobblespawners.utils.ParticleUtils.toggleVisualization
import com.blanketcobblespawners.utils.gui.SpawnerPokemonSelectionGui
import com.cobblemon.mod.common.api.drop.DropTable
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.block.Blocks
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.lang.instrument.Instrumentation
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import kotlin.reflect.full.memberProperties

object CommandRegistrar {

    private val logger = LoggerFactory.getLogger("CommandRegistrar")

    fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            logger.info("Registering commands for BlanketCobbleSpawners.")
            registerBlanketCobbleSpawnersCommand(dispatcher)
        }
    }

    // Register the `/blanketcobblespawners` command with aliases
// Register the `/blanketcobblespawners` command with aliases
    private fun registerBlanketCobbleSpawnersCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val mainCommand = literal("blanketcobblespawners") // Main command
            .requires { source ->
                val player = source.player
                player != null && hasPermission(player, "spawner.manage", 2)
            }
            .executes { context ->
                context.source.sendFeedback({ Text.literal("BlanketCobbleSpawners v1.0.0") }, false)
                1
            }
            // Edit command
            .then(
                literal("edit")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                val player = context.source.player as? ServerPlayerEntity

                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                if (player != null) {
                                    // Open the GUI for the spawner
                                    SpawnerPokemonSelectionGui.openSpawnerGui(player, spawnerEntry.spawnerPos)
                                    context.source.sendFeedback({ Text.literal("GUI for spawner '$spawnerName' has been opened.") }, true)
                                    return@executes 1
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // Rename command
            .then(
                literal("rename")
                    .then(
                        argument("currentName", word())
                            .suggests(spawnerNameSuggestions)
                            .then(
                                argument("newName", word())
                                    .executes { context ->
                                        val currentName = getString(context, "currentName")
                                        val newName = getString(context, "newName")
                                        val player = context.source.player as? ServerPlayerEntity

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == currentName }

                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$currentName' not found."))
                                            return@executes 0
                                        }

                                        // Check if the new name already exists
                                        if (ConfigManager.spawners.values.any { it.spawnerName == newName }) {
                                            context.source.sendError(Text.literal("Spawner name '$newName' is already in use."))
                                            return@executes 0
                                        }

                                        // Rename the spawner
                                        spawnerEntry.spawnerName = newName
                                        ConfigManager.saveSpawnerData()

                                        context.source.sendFeedback(
                                            { Text.literal("Spawner renamed from '$currentName' to '$newName'.") },
                                            true
                                        )
                                        return@executes 1
                                    }
                            )
                    )
            )
            // Addmon command
            .then(
                literal("addmon")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .then(
                                argument("pokemonName", word())
                                    .suggests(pokemonNameSuggestions)
                                    .then(
                                        argument("formName", word())
                                            .suggests(formNameSuggestions)
                                            .executes { context ->
                                                val spawnerName = getString(context, "spawnerName")
                                                val pokemonName = getString(context, "pokemonName").lowercase()
                                                val formName = getString(context, "formName").lowercase()

                                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                                if (spawnerEntry == null) {
                                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                                    return@executes 0
                                                }

                                                val species = PokemonSpecies.getByName(pokemonName)
                                                if (species == null) {
                                                    context.source.sendError(Text.literal("Pokémon '$pokemonName' not found. Please check the spelling."))
                                                    return@executes 0
                                                }

                                                // Determine the appropriate form name
                                                val selectedForm = when {
                                                    species.forms.isEmpty() -> "Normal" // No forms available, default to "Normal"
                                                    species.forms.any { it.name.equals(formName, ignoreCase = true) } -> formName
                                                    formName.isBlank() || formName.equals("normal", ignoreCase = true) -> "Normal"
                                                    else -> {
                                                        context.source.sendError(Text.literal("Form '$formName' does not exist for Pokémon '$pokemonName'. Defaulting to 'Normal'."))
                                                        "Normal"
                                                    }
                                                }

                                                val newEntry = PokemonSpawnEntry(
                                                    pokemonName = pokemonName,
                                                    formName = selectedForm,
                                                    spawnChance = 50.0,
                                                    shinyChance = 0.0,
                                                    minLevel = 1,
                                                    maxLevel = 100,
                                                    captureSettings = CaptureSettings(
                                                        isCatchable = true,
                                                        restrictCaptureToLimitedBalls = true,
                                                        requiredPokeBalls = listOf("safari_ball")
                                                    ),
                                                    ivSettings = IVSettings(
                                                        allowCustomIvs = false,
                                                        minIVHp = 0,
                                                        maxIVHp = 31,
                                                        minIVAttack = 0,
                                                        maxIVAttack = 31,
                                                        minIVDefense = 0,
                                                        maxIVDefense = 31,
                                                        minIVSpecialAttack = 0,
                                                        maxIVSpecialAttack = 31,
                                                        minIVSpecialDefense = 0,
                                                        maxIVSpecialDefense = 31,
                                                        minIVSpeed = 0,
                                                        maxIVSpeed = 31
                                                    ),
                                                    evSettings = EVSettings(
                                                        allowCustomEvsOnDefeat = false,
                                                        evHp = 0,
                                                        evAttack = 0,
                                                        evDefense = 0,
                                                        evSpecialAttack = 0,
                                                        evSpecialDefense = 0,
                                                        evSpeed = 0
                                                    ),
                                                    spawnSettings = SpawnSettings(
                                                        spawnTime = "ALL",
                                                        spawnWeather = "ALL"
                                                    )
                                                )

                                                if (ConfigManager.addPokemonSpawnEntry(spawnerEntry.spawnerPos, newEntry)) {
                                                    context.source.sendFeedback(
                                                        { Text.literal("Added Pokémon '$pokemonName' with form '$selectedForm' to spawner '$spawnerName'.") },
                                                        true
                                                    )
                                                    return@executes 1
                                                } else {
                                                    context.source.sendError(Text.literal("Failed to add Pokémon '$pokemonName' to spawner '$spawnerName'."))
                                                    return@executes 0
                                                }
                                            }
                                    )
                                    .executes { context ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val pokemonName = getString(context, "pokemonName").lowercase()

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                            return@executes 0
                                        }

                                        val species = PokemonSpecies.getByName(pokemonName)
                                        if (species == null) {
                                            context.source.sendError(Text.literal("Pokémon '$pokemonName' not found. Please check the spelling."))
                                            return@executes 0
                                        }

                                        // Set form to "Normal" if no form specified
                                        val selectedForm = if (species.forms.isEmpty()) "Normal" else "default"

                                        val newEntry = PokemonSpawnEntry(
                                            pokemonName = pokemonName,
                                            formName = selectedForm,
                                            spawnChance = 50.0,
                                            shinyChance = 0.0,
                                            minLevel = 1,
                                            maxLevel = 100,
                                            captureSettings = CaptureSettings(
                                                isCatchable = true,
                                                restrictCaptureToLimitedBalls = true,
                                                requiredPokeBalls = listOf("safari_ball")
                                            ),
                                            ivSettings = IVSettings(
                                                allowCustomIvs = false,
                                                minIVHp = 0,
                                                maxIVHp = 31,
                                                minIVAttack = 0,
                                                maxIVAttack = 31,
                                                minIVDefense = 0,
                                                maxIVDefense = 31,
                                                minIVSpecialAttack = 0,
                                                maxIVSpecialAttack = 31,
                                                minIVSpecialDefense = 0,
                                                maxIVSpecialDefense = 0,
                                                minIVSpeed = 0,
                                                maxIVSpeed = 31
                                            ),
                                            evSettings = EVSettings(
                                                allowCustomEvsOnDefeat = false,
                                                evHp = 0,
                                                evAttack = 0,
                                                evDefense = 0,
                                                evSpecialAttack = 0,
                                                evSpecialDefense = 0,
                                                evSpeed = 0
                                            ),
                                            spawnSettings = SpawnSettings(
                                                spawnTime = "ALL",
                                                spawnWeather = "ALL"
                                            )
                                        )

                                        if (ConfigManager.addPokemonSpawnEntry(spawnerEntry.spawnerPos, newEntry)) {
                                            context.source.sendFeedback(
                                                { Text.literal("Added Pokémon '$pokemonName' to spawner '$spawnerName' with form '$selectedForm'.") },
                                                true
                                            )
                                            return@executes 1
                                        } else {
                                            context.source.sendError(Text.literal("Failed to add Pokémon '$pokemonName' to spawner '$spawnerName'."))
                                            return@executes 0
                                        }
                                    }
                            )
                    )
            )
            // Removemon command
            .then(
                literal("removemon")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .then(
                                argument("pokemonName", word())
                                    .suggests { context, builder ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        spawnerEntry?.selectedPokemon?.forEach { entry ->
                                            if (entry.pokemonName.startsWith(builder.remainingLowerCase)) {
                                                builder.suggest(entry.pokemonName)
                                            }
                                        }
                                        builder.buildFuture()
                                    }
                                    .then(
                                        argument("formName", word())
                                            .suggests(formNameSuggestions)
                                            .executes { context ->
                                                val spawnerName = getString(context, "spawnerName")
                                                val pokemonName = getString(context, "pokemonName")
                                                val formName = getString(context, "formName")

                                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                                if (spawnerEntry == null) {
                                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                                    return@executes 0
                                                }

                                                val existingEntry = spawnerEntry.selectedPokemon.find {
                                                    it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                                                            it.formName.equals(formName, ignoreCase = true)
                                                }

                                                if (existingEntry != null) {
                                                    if (ConfigManager.removePokemonSpawnEntry(spawnerEntry.spawnerPos, pokemonName, formName)) {
                                                        context.source.sendFeedback(
                                                            { Text.literal("Removed Pokémon '$pokemonName' with form '$formName' from spawner '$spawnerName'.") },
                                                            true
                                                        )
                                                        return@executes 1
                                                    } else {
                                                        context.source.sendError(Text.literal("Failed to remove Pokémon '$pokemonName' from spawner '$spawnerName'."))
                                                        return@executes 0
                                                    }
                                                } else {
                                                    context.source.sendError(Text.literal("Pokémon '$pokemonName' with form '$formName' is not selected for spawner '$spawnerName'."))
                                                    return@executes 0
                                                }
                                            }
                                    )
                                    .executes { context ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val pokemonName = getString(context, "pokemonName")

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                            return@executes 0
                                        }

                                        val matchingEntries = spawnerEntry.selectedPokemon.filter { it.pokemonName.equals(pokemonName, ignoreCase = true) }

                                        when {
                                            matchingEntries.isEmpty() -> {
                                                context.source.sendError(Text.literal("Pokémon '$pokemonName' is not selected for spawner '$spawnerName'."))
                                                return@executes 0
                                            }
                                            matchingEntries.size == 1 -> {
                                                val entry = matchingEntries.first()
                                                if (ConfigManager.removePokemonSpawnEntry(spawnerEntry.spawnerPos, pokemonName, entry.formName)) {
                                                    context.source.sendFeedback(
                                                        { Text.literal("Removed Pokémon '$pokemonName' with form '${entry.formName}' from spawner '$spawnerName'.") },
                                                        true
                                                    )
                                                    return@executes 1
                                                } else {
                                                    context.source.sendError(Text.literal("Failed to remove Pokémon '$pokemonName' from spawner '$spawnerName'."))
                                                    return@executes 0
                                                }
                                            }
                                            else -> {
                                                context.source.sendError(Text.literal("Multiple forms found for Pokémon '$pokemonName'. Please specify a form name."))
                                                return@executes 0
                                            }
                                        }
                                    }
                            )
                    )
            )
            // Killspawned command
            .then(
                literal("killspawned")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }
                                val spawnerPos = spawnerEntry.spawnerPos
                                val server = context.source.server
                                val registryKey = parseDimension(spawnerEntry.dimension)
                                val serverWorld = server.getWorld(registryKey)
                                if (serverWorld == null) {
                                    context.source.sendError(Text.literal("World '${registryKey.value}' not found for spawner '$spawnerName'."))
                                    return@executes 0
                                }
                                val uuids = SpawnerUUIDManager.getUUIDsForSpawner(spawnerPos)
                                uuids.forEach { uuid ->
                                    val entity = serverWorld.getEntity(uuid)
                                    if (entity is PokemonEntity) {
                                        entity.discard()
                                        SpawnerUUIDManager.removePokemon(uuid)
                                        logDebug("Despawned Pokémon with UUID $uuid from spawner at $spawnerPos")
                                    }
                                }
                                context.source.sendFeedback(
                                    { Text.literal("All Pokémon spawned by spawner '$spawnerName' have been removed.") },
                                    true
                                )
                                1
                            }
                    )
            )
            // Togglevisibility command
            .then(
                literal("togglevisibility")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerData = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }

                                if (spawnerData != null) {
                                    val success = toggleSpawnerVisibility(context.source.server, spawnerData.spawnerPos)
                                    if (success) {
                                        context.source.sendFeedback(
                                            { Text.literal("Spawner '$spawnerName' visibility has been toggled.") },
                                            true
                                        )
                                        return@executes 1
                                    } else {
                                        context.source.sendError(Text.literal("Failed to toggle visibility for spawner '$spawnerName'."))
                                        return@executes 0
                                    }
                                } else {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // Toggleradius command
            .then(
                literal("toggleradius")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                val player = context.source.player as? ServerPlayerEntity

                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                if (player != null) {
                                    // Toggle the visualization for the player
                                    toggleVisualization(player, spawnerEntry)
                                    context.source.sendFeedback(
                                        { Text.literal("Spawn radius visualization toggled for spawner '$spawnerName'.") },
                                        true
                                    )
                                    return@executes 1
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // Teleport command
            .then(
                literal("teleport")
                    .then(
                        argument("spawnerName", word())
                            .suggests(spawnerNameSuggestions)
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                val player = context.source.player as? ServerPlayerEntity

                                if (spawnerEntry == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                if (player != null) {
                                    // Teleport the player to the spawner's location
                                    val spawnerPos = spawnerEntry.spawnerPos
                                    val dimension = parseDimension(spawnerEntry.dimension)
                                    val world = context.source.server.getWorld(dimension)
                                    if (world != null) {
                                        player.teleport(world, spawnerPos.x.toDouble(), spawnerPos.y.toDouble(), spawnerPos.z.toDouble(), player.yaw, player.pitch)
                                        context.source.sendFeedback({ Text.literal("Teleported to spawner '$spawnerName'.") }, true)
                                        return@executes 1
                                    } else {
                                        context.source.sendError(Text.literal("World '${spawnerEntry.dimension}' not found."))
                                        return@executes 0
                                    }
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // List command
            .then(
                literal("list")
                    .executes { context ->
                        val player = context.source.player as? ServerPlayerEntity ?: return@executes 0
                        val spawnerList = ConfigManager.spawners.map { (pos, data) ->
                            "${data.spawnerName}: ${pos.x}, ${pos.y}, ${pos.z} (${data.dimension})"
                        }

                        if (spawnerList.isEmpty()) {
                            player.sendMessage(Text.literal("No spawners found."), false)
                        } else {
                            player.sendMessage(Text.literal("Spawners:\n${spawnerList.joinToString("\n")}"), false)
                        }
                        1
                    }
            )
            // GUI command
            .then(
                literal("gui")
                    .executes { context ->
                        val player = context.source.player as? ServerPlayerEntity ?: return@executes 0
                        SpawnerListGui.openSpawnerListGui(player)
                        context.source.sendFeedback({ Text.literal("Spawner GUI has been opened.") }, true)
                        1
                    }
            )
            // Reload command
            .then(
                literal("reload")
                    .executes { context ->
                        ConfigManager.reloadSpawnerData() // Reload config
                        context.source.sendFeedback(
                            { Text.literal("Configuration for BlanketCobbleSpawners has been successfully reloaded.") },
                            true
                        )
                        logDebug("Configuration reloaded for BlanketCobbleSpawners.")
                        1
                    }
            )
            // Give command
            .then(
                literal("givespawnerblock")
                    .executes { context ->
                        (context.source.player as? ServerPlayerEntity)?.let { player ->
                            val customSpawnerItem = ItemStack(Items.SPAWNER).apply {
                                count = 1
                                nbt = createSpawnerNbt()
                            }
                            player.inventory.insertStack(customSpawnerItem)
                            player.sendMessage(Text.literal("A custom spawner has been added to your inventory."), false)
                            logDebug("Custom spawner given to player ${player.name.string}.")
                        }
                        1
                    }
            )
            // Help command
            .then(
                literal("help")
                    .executes { context ->
                        val helpText = Text.literal("**BlanketCobbleSpawners Commands:**\n").styled { it.withColor(0xFFFFFF) } // White title
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) } // Gray bullet
                                    .append(Text.literal("/blanketcobblespawners reload").styled { it.withColor(0x55FF55) }) // Green command
                                    .append(Text.literal(": Reloads the spawner configuration.\n").styled { it.withColor(0xAAAAAA) }) // Gray description
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners givespawnerblock").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Gives a custom spawner to the player.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners list").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Lists all spawners with their coordinates.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners gui").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Opens the GUI listing all spawners.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners edit <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Opens the GUI to edit the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners rename <currentName> <newName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Renames the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners addmon <spawnerName> <pokemonName> [formName]").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Adds a Pokémon to the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners removemon <spawnerName> <pokemonName> [formName]").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Removes a Pokémon from the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners killspawned <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Removes all Pokémon spawned by the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners togglevisibility <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Toggles the visibility of the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners toggleradius <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Toggles spawn radius visualization for the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )
                            .append(
                                Text.literal("- ").styled { it.withColor(0xAAAAAA) }
                                    .append(Text.literal("/blanketcobblespawners teleport <spawnerName>").styled { it.withColor(0x55FF55) })
                                    .append(Text.literal(": Teleports you to the specified spawner.\n").styled { it.withColor(0xAAAAAA) })
                            )

                        val player = context.source.player as? ServerPlayerEntity
                        if (player != null) {
                            player.sendMessage(helpText, false)
                            return@executes 1
                        } else {
                            context.source.sendError(Text.literal("Only players can run this command."))
                            return@executes 0
                        }
                    }
            )

            // Debug commands group
            .then(
                literal("debug")
                    .then(
                        literal("spawn-custom-pokemon")
                            .then(
                                argument("pokemonName", string())
                                    .suggests(pokemonNameSuggestions)
                                    .then(
                                        argument("formName", string())
                                            .suggests(formNameSuggestions)
                                            .executes { context ->
                                                val pokemonName = getString(context, "pokemonName").lowercase()
                                                val formName = getString(context, "formName").lowercase()
                                                executeSpawnCustom(context, pokemonName, formName)
                                            }
                                    )
                                    .executes { context ->
                                        val pokemonName = getString(context, "pokemonName").lowercase()
                                        executeSpawnCustom(context, pokemonName, null)
                                    }
                            )
                    )
                    .then(
                        literal("log-pokemon-species-and-dex")
                            .executes { context ->
                                // Fetch all species
                                val speciesList = PokemonSpecies.species
                                if (speciesList.isNotEmpty()) {
                                    speciesList.forEach { species ->
                                        // Print species name and dex number to the console
                                        logger.info("Species: ${species.name}, Dex Number: ${species.nationalPokedexNumber}")
                                    }
                                    context.source.sendFeedback(
                                        { Text.literal("Logged all Pokémon species and dex numbers to the console.") },
                                        true
                                    )
                                } else {
                                    context.source.sendError(Text.literal("No Pokémon species found."))
                                }
                                1
                            }
                    )
                    .then(
                        // Add "givepokemoninspectwand" command
                        literal("givepokemoninspectwand")
                            .executes { context ->
                                val player = context.source.player as? ServerPlayerEntity ?: return@executes 0
                                givePokemonInspectStick(player)  // Call the function to give the custom stick
                                context.source.sendFeedback({ Text.literal("Given a Pokemon Inspect Wand!") }, true)
                                1
                            }
                    )
                    .then(
                        literal("calculateMapEntryCount")
                            .executes { context ->
                                val memoryUsageMessage = calculateMapEntryCount()
                                context.source.sendFeedback(Supplier { Text.literal(memoryUsageMessage) }, false)
                                1
                            }
                    )
                    .then(
                        literal("listforms")
                            .then(
                                argument("pokemonName", word())
                                    .suggests(pokemonNameSuggestions)
                                    .executes { context ->
                                        val pokemonName = getString(context, "pokemonName").lowercase()

                                        val species = PokemonSpecies.getByName(pokemonName)

                                        if (species == null) {
                                            context.source.sendError(Text.literal("Pokémon '$pokemonName' not found."))
                                            return@executes 0
                                        }

                                        // Retrieve available forms for the specified Pokémon
                                        val formNames = species.forms.map { it.name ?: "Default" }

                                        if (formNames.isEmpty()) {
                                            context.source.sendFeedback({ Text.literal("No available forms for Pokémon '$pokemonName'.") }, false)
                                        } else {
                                            val formsList = formNames.joinToString(", ")
                                            context.source.sendFeedback({ Text.literal("Available forms for Pokémon '$pokemonName': $formsList") }, false)
                                        }

                                        1
                                    }
                            )
                    )
            )
        // Build the command into a CommandNode
        val builtMainCommand = dispatcher.register(mainCommand)

        // Define aliases for `/cobblespawners` and `/bcs`, and redirect them to the built command. i may add more
        val aliasSpawnerCommand1 = literal("cobblespawners").redirect(builtMainCommand)
        val aliasSpawnerCommand2 = literal("bcs").redirect(builtMainCommand)

        // Register the aliases
        dispatcher.register(aliasSpawnerCommand1)
        dispatcher.register(aliasSpawnerCommand2)

        // Log the registration
        logDebug("Registering commands: /blanketcobblespawners, /cobblespawners, /bcs")
    }



    private fun calculateMapEntryCount(): String {
        val uuidMapCount = SpawnerUUIDManager.pokemonUUIDMap.size
        val visualizationMapCount = ParticleUtils.activeVisualizations.size
        val spawnerConfigCount = ConfigManager.spawners.size
        val cachedValidPositionsCount = BlanketCobbleSpawners.spawnerValidPositions.size
        val ongoingBattlesCount = BattleTracker().ongoingBattles.size
        val playerPagesCount = SpawnerPokemonSelectionGui.playerPages.size
        val spawnerGuisOpenCount = SpawnerPokemonSelectionGui.spawnerGuisOpen.size
        val selectedPokemonListCount = ConfigManager.spawners.values.sumOf { it.selectedPokemon.size }
        val speciesFormsListCount = SpawnerPokemonSelectionGui.getSortedSpeciesList(emptyList()).size
        val lastSpawnTicksCount = ConfigManager.lastSpawnTicks.size
        val spawnerValidPositionsCount = BlanketCobbleSpawners.spawnerValidPositions.size

        // Summing up all the entry counts
        val totalEntries = uuidMapCount + visualizationMapCount + spawnerConfigCount +
                cachedValidPositionsCount + ongoingBattlesCount + playerPagesCount +
                spawnerGuisOpenCount + selectedPokemonListCount + speciesFormsListCount +
                lastSpawnTicksCount + spawnerValidPositionsCount

        return """
        |[BlanketCobbleSpawners Map Entry Counts]
        |UUID Manager Entry Count: $uuidMapCount
        |Active Visualizations Entry Count: $visualizationMapCount
        |Spawner Config Entry Count: $spawnerConfigCount
        |Cached Valid Positions Entry Count: $cachedValidPositionsCount
        |Ongoing Battles Entry Count: $ongoingBattlesCount
        |Player Pages Map Entry Count: $playerPagesCount
        |Spawner GUIs Open Entry Count: $spawnerGuisOpenCount
        |Selected Pokémon List Entry Count: $selectedPokemonListCount
        |Species Forms List Entry Count: $speciesFormsListCount
        |Last Spawn Ticks Map Entry Count: $lastSpawnTicksCount
        |Spawner Valid Positions Entry Count: $spawnerValidPositionsCount
        |Total Map Entries: $totalEntries
    """.trimMargin()
    }





    // Suggestion Providers
    private val spawnerNameSuggestions: SuggestionProvider<ServerCommandSource> = SuggestionProvider { context, builder ->
        val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
        spawnerNames.filter { it.startsWith(builder.remainingLowerCase) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    // Suggestion provider for Pokémon names, filtering based on current input
    private val pokemonNameSuggestions: SuggestionProvider<ServerCommandSource> = SuggestionProvider { context, builder ->
        // Fetch the current input
        val input = builder.remaining.lowercase()
        // Filter species names that start with the current input
        PokemonSpecies.species
            .map { it.name }
            .filter { it.lowercase().startsWith(input) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }


    private val formNameSuggestions: SuggestionProvider<ServerCommandSource> = SuggestionProvider { context, builder ->
        // Retrieve the 'pokemonName' argument from the context
        val pokemonName = try {
            getString(context, "pokemonName").lowercase()
        } catch (e: IllegalArgumentException) {
            "" // Default to empty if 'pokemonName' isn't provided yet
        }

        // Get the species by name
        val species = PokemonSpecies.getByName(pokemonName)
        if (species != null) {
            // Always suggest "Normal" as a default form, avoiding duplicates
            builder.suggest("Normal")

            // Add each available form from the species, skipping if it is already "Normal"
            species.forms.forEach { form ->
                val formName = form.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    ?: "Normal"
                if (formName != "Normal") {
                    builder.suggest(formName)
                }
            }
        } else {
            // If no species is found, suggest only "Normal"
            builder.suggest("Normal")
        }

        builder.buildFuture()
    }




    // Function to give the player a PokemonInspect stick
    private fun givePokemonInspectStick(player: ServerPlayerEntity) {
        // Create a stick item with custom NBT, name, and lore
        val inspectStick = ItemStack(Items.STICK).apply {
            count = 1

            // NBT to track custom behavior
            nbt = NbtCompound().apply {
                // Custom identifier for the stick to track in the game logic
                putString("PokemonInspect", "true")

                // Add display properties for name and lore
                put("display", NbtCompound().apply {
                    // Custom name/title
                    putString("Name", "{\"text\":\"Pokemon Inspect Stick\",\"color\":\"green\",\"italic\":false}")

                    // Custom lore
                    val loreList = NbtList().apply {
                        add(NbtString.of("{\"text\":\"Use this to inspect Pokémon.\",\"color\":\"dark_green\",\"italic\":true}"))
                        add(NbtString.of("{\"text\":\"Track your Pokémon interactions.\",\"color\":\"gray\",\"italic\":false}"))
                    }
                    put("Lore", loreList)
                })
            }
        }

        // Add the item to the player's inventory
        player.inventory.insertStack(inspectStick)
    }
    fun registerEntityClickEvent() {
        val playerLastInspectTime = ConcurrentHashMap<UUID, Long>() // Track player's last inspect time

        UseEntityCallback.EVENT.register { player, world, hand, entity, _ ->
            // Check if the entity clicked is a Pokémon and the player has the inspect wand
            if (player is ServerPlayerEntity && entity is PokemonEntity && hand == Hand.MAIN_HAND) {
                val itemInHand = player.getStackInHand(hand)
                if (itemInHand.item == Items.STICK && itemInHand.nbt?.getString("PokemonInspect") == "true") {
                    // Get the current timestamp and player's UUID
                    val currentTime = System.currentTimeMillis()
                    val playerId = player.uuid

                    // Check if the player has inspected recently (cooldown set to 1 second)
                    if (currentTime - (playerLastInspectTime[playerId] ?: 0) > 1000) {
                        // Update the last inspect time
                        playerLastInspectTime[playerId] = currentTime

                        // Call the inspect function to display Pokémon details
                        inspectPokemon(player, entity)

                        return@register ActionResult.SUCCESS
                    }
                }
            }
            ActionResult.PASS
        }
    }

    private fun inspectPokemon(player: ServerPlayerEntity, pokemonEntity: PokemonEntity) {
        val pokemon = pokemonEntity.pokemon

        // UUID information and UUID Manager count
        val uuid = pokemonEntity.uuid
        val trackedUUIDInfo = SpawnerUUIDManager.getPokemonInfo(uuid)
        val totalUUIDsInManager = SpawnerUUIDManager.pokemonUUIDMap.size
        val spawnerInfo = SpawnerUUIDManager.getPokemonInfo(uuid)
        val isFromSpawner = spawnerInfo != null

        val speciesName = pokemon.species.name
        val form = pokemon.form.name ?: "Default"
        val isShiny = pokemon.shiny
        val catchRate = pokemon.species.catchRate
        val friendship = pokemon.friendship
        val state = pokemon.state.name
        val owner = pokemon.getOwnerPlayer()?.name?.string ?: "None"
        val experience = pokemon.experience
        val evolutions = pokemon.species.evolutions.joinToString(", ") { evolution ->
            evolution.result.species.toString()
        }
        val tradeable = pokemon.tradeable
        val gender = pokemon.gender.name
        val caughtBall = pokemon.caughtBall.name
        val currentHealth = pokemon.currentHealth
        val maxHealth = pokemon.hp

        // Additional attributes
        val allAccessibleMoves = pokemon.allAccessibleMoves.joinToString(", ") { it.displayName.toString() }
        val lastFlowerFed = pokemon.lastFlowerFed.toString() ?: "None"
        val originalTrainer = pokemon.originalTrainer?.toString() ?: "None"
        val scaleModifier = pokemon.scaleModifier

        // Entity-specific attributes
        val despawnCounter = pokemonEntity.despawnCounter
        val blocksTraveled = pokemonEntity.blocksTraveled
        val ticksLived = pokemonEntity.ticksLived
        val width = pokemonEntity.width
        val height = pokemonEntity.height
        val isBattling = pokemonEntity.isBattling
        val isBusy = pokemonEntity.isBusy
        val movementSpeed = pokemonEntity.movementSpeed

        // IVs, EVs, and Abilities
        val ivs = pokemon.ivs
        val evs = pokemon.evs
        val abilities = listOf(pokemon.ability) ?: emptyList()

        // Prepare the message to display
        val message = Text.literal("You clicked on Pokémon: ")
            .append(Text.literal(speciesName).styled { it.withColor(0x00FF00) })
            .append(Text.literal("\nUUID: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(uuid.toString()).styled { it.withColor(0xFFAA00) })

        // Add UUID Manager Information
        message.append(Text.literal("\nTracked UUIDs in Manager: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(totalUUIDsInManager.toString()).styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nForm: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(form).styled { it.withColor(0xFFFF55) })

        message.append(Text.literal("\nShiny: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isShiny) "Yes" else "No").styled { it.withColor(if (isShiny) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nFriendship: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$friendship").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nState: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(state).styled { it.withColor(0xFFAA00) })


        message.append(Text.literal("\nOwner: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(owner).styled { it.withColor(0xFFAA00) })

        message.append(Text.literal("\nExperience: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$experience").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nEvolutions: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(evolutions.ifEmpty { "None" }).styled { it.withColor(0xFFFF55) })

        message.append(Text.literal("\nTradeable: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (tradeable) "Yes" else "No").styled { it.withColor(if (tradeable) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nGender: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(gender).styled { it.withColor(0xFFFF55) })

        message.append(Text.literal("\nCaught Ball: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(caughtBall.toString()).styled { it.withColor(0xFFAA00) })

        message.append(Text.literal("\nHealth: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$currentHealth/$maxHealth").styled { it.withColor(0xFF5555) })

        message.append(Text.literal("\nCatch Rate: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$catchRate").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nAll Accessible Moves: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(allAccessibleMoves).styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nLast Flower Fed: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(lastFlowerFed).styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nOriginal Trainer: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(originalTrainer).styled { it.withColor(0xFFAA00) })

        message.append(Text.literal("\nScale Modifier: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$scaleModifier").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nIVs: ").styled { it.withColor(0xAAAAAA) })
        Stats.PERMANENT.forEach { stat ->
            message.append(
                Text.literal("\n  ${stat.showdownId}: ").styled { it.withColor(0xAAAAAA) }
            ).append(
                Text.literal("${ivs[stat]}").styled { it.withColor(0x55FF55) }
            )
        }

        message.append(Text.literal("\nEVs: ").styled { it.withColor(0xAAAAAA) })
        Stats.PERMANENT.forEach { stat ->
            message.append(
                Text.literal("\n  ${stat.showdownId}: ").styled { it.withColor(0xAAAAAA) }
            ).append(
                Text.literal("${evs.get(stat)}").styled { it.withColor(0x55FF55) }
            )
        }

        message.append(Text.literal("\nAbilities: ").styled { it.withColor(0xAAAAAA) })
        abilities.forEach { ability ->
            message.append(
                Text.literal("\n  ${ability.displayName}").styled { it.withColor(0xFFAA00) }
            )
        }

        message.append(Text.literal("\nDespawn Counter: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$despawnCounter").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nBlocks Traveled: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$blocksTraveled").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nTicks Lived: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$ticksLived").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nWidth: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$width").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nHeight: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$height").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nIs Battling: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isBattling) "Yes" else "No").styled { it.withColor(if (isBattling) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nIs Busy: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isBusy) "Yes" else "No").styled { it.withColor(if (isBusy) 0x55FF55 else 0xFF5555) })

        message.append(Text.literal("\nMovement Speed: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal("$movementSpeed").styled { it.withColor(0x55FF55) })

        message.append(Text.literal("\nSpawner Origin: ").styled { it.withColor(0xAAAAAA) })
            .append(Text.literal(if (isFromSpawner) "Yes" else "No").styled { it.withColor(if (isFromSpawner) 0x55FF55 else 0xFF5555) })

        // Send the formatted message to the player
        player.sendMessage(message, false)
    }











    // Function to create the NBT data for the custom spawner
    private fun createSpawnerNbt(): NbtCompound {
        val nbt = NbtCompound()

        // Indicate that this is a custom spawner
        nbt.putString("CustomSpawner", "true")

        // Add custom display data (title and lore)
        val displayTag = NbtCompound()

        // Set custom name (title) with color and style (this is a JSON text component)
        displayTag.putString("Name", "{\"text\":\"Custom Cobble Spawner\",\"color\":\"gold\",\"italic\":false}")

        // Create a custom lore list and make sure no default lore is added
        val loreList = NbtList().apply {
            add(NbtString.of("{\"text\":\"A special spawner.\",\"color\":\"gray\",\"italic\":true}"))
            add(NbtString.of("{\"text\":\"Used to spawn cobble-based entities.\",\"color\":\"dark_gray\",\"italic\":false}"))
        }

        // Attach the custom lore to the display tag
        displayTag.put("Lore", loreList)

        // Attach the display tag (with custom name and lore) to the spawner NBT
        nbt.put("display", displayTag)

        // Remove default entity tag if it's present
        if (nbt.contains("BlockEntityTag")) {
            nbt.remove("BlockEntityTag") // This will strip any default behavior linked to the spawner
        }

        return nbt
    }

    /**
     * Public function to toggle the visibility of a spawner.
     *
     * @param server The Minecraft server instance.
     * @param spawnerPos The BlockPos of the spawner to toggle.
     * @return True if the operation was successful, false otherwise.
     */
    fun toggleSpawnerVisibility(server: MinecraftServer, spawnerPos: BlockPos): Boolean {
        val spawnerData = ConfigManager.getSpawner(spawnerPos)
        if (spawnerData == null) {
            logger.error("Spawner at position $spawnerPos not found.")
            return false
        }

        // Toggle the visibility flag
        spawnerData.visible = !spawnerData.visible

        // Update the block state based on the new visibility
        val registryKey = parseDimension(spawnerData.dimension)
        val world = server.getWorld(registryKey)

        if (world == null) {
            logger.error("World '${spawnerData.dimension}' not found.")
            return false
        }

        return try {
            if (spawnerData.visible) {
                // Make the spawner visible (place the block back with its original state)
                // You may want to restore the original NBT data here if necessary
                world.setBlockState(spawnerPos, Blocks.SPAWNER.defaultState)
                logDebug("Spawner at $spawnerPos is now visible.")
            } else {
                // Make the spawner invisible (remove the block)
                world.setBlockState(spawnerPos, Blocks.AIR.defaultState)
                logDebug("Spawner at $spawnerPos is now invisible.")
            }

            // Save the updated configuration
            ConfigManager.saveSpawnerData()
            true
        } catch (e: Exception) {
            logger.error("Error toggling visibility for spawner at $spawnerPos: ${e.message}")
            false
        }
    }

    private fun executeSpawnCustom(context: CommandContext<ServerCommandSource>, pokemonName: String, formName: String?): Int {
        val source = context.source
        val world = source.world
        val pos = Vec3d(source.position.x, source.position.y, source.position.z)

        val species = PokemonSpecies.getByName(pokemonName)
        if (species == null) {
            source.sendError(Text.literal("Species '$pokemonName' not found."))
            return 0
        }

        val propertiesStringBuilder = StringBuilder(pokemonName)
        if (formName != null) {
            val form = species.forms.find { it.name?.lowercase() == formName }
            if (form == null) {
                source.sendError(Text.literal("Form '$formName' not found for species '$pokemonName'."))
                return 0
            }
            // Add required aspects to properties
            if (form.aspects.isNotEmpty()) {
                for (aspect in form.aspects) {
                    propertiesStringBuilder.append(" ").append("$aspect=true")
                }
            } else {
                // If the form has no required aspects, set the form directly
                propertiesStringBuilder.append(" form=${form.formOnlyShowdownId()}")
            }
        }

        val properties = PokemonProperties.parse(propertiesStringBuilder.toString())

        val pokemonEntity = properties.createEntity(world)
        pokemonEntity.refreshPositionAndAngles(pos.x, pos.y, pos.z, pokemonEntity.yaw, pokemonEntity.pitch)
        pokemonEntity.dataTracker.set(PokemonEntity.SPAWN_DIRECTION, pokemonEntity.random.nextFloat() * 360F)

        return if (world.spawnEntity(pokemonEntity)) {
            val displayName = pokemonEntity.displayName.string
            source.sendFeedback(Supplier { Text.literal("Spawned $displayName!") }, true)
            1 // Command.SINGLE_SUCCESS
        } else {
            source.sendError(Text.literal("Failed to spawn $pokemonName"))
            0
        }
    }

    // Helper function to remove spawner block
    private fun removeSpawnerBlock(server: MinecraftServer, spawnerPos: BlockPos, dimension: String) {
        val registryKey = parseDimension(dimension)
        val world = server.getWorld(registryKey) ?: return

        world.setBlockState(spawnerPos, Blocks.AIR.defaultState)
    }

    // Check if the player has permission
    fun hasPermission(player: ServerPlayerEntity, permission: String, level: Int): Boolean {
        return if (hasLuckPermsPermission(player, permission, level)) {
            true
        } else {
            player.hasPermissionLevel(level)
        }
    }

    // LuckPerms permission check
    private fun hasLuckPermsPermission(player: ServerPlayerEntity, permission: String, level: Int): Boolean {
        return try {
            Permissions.check(player, permission, level)
        } catch (e: NoClassDefFoundError) {
            false
        }
    }

    // Helper function to parse dimension string to RegistryKey<World>
    private fun parseDimension(dimensionString: String): RegistryKey<World> {
        val parts = dimensionString.split(":")
        if (parts.size != 2) {
            logger.warn("Invalid dimension format: $dimensionString. Expected 'namespace:path'")
            return RegistryKey.of(RegistryKeys.WORLD, Identifier("minecraft", "overworld")) // default to overworld
        }
        val namespace = parts[0]
        val path = parts[1]
        return RegistryKey.of(RegistryKeys.WORLD, Identifier(namespace, path))
    }
}
