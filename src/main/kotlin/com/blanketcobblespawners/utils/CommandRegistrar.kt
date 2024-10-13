package com.blanketcobblespawners.utils

import com.blanketcobblespawners.BlanketCobbleSpawners
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.Gui.GuiManager
import com.blanketcobblespawners.utils.ParticleUtils.toggleVisualization
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.suggestion.SuggestionProvider
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
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
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory

object CommandRegistrar {

    private val logger = LoggerFactory.getLogger("CommandRegistrar")

    fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            logger.info("Registering commands for BlanketCobbleSpawners.")
            registerBlanketCobbleSpawnersCommand(dispatcher)
        }
    }

    // Register the `/blanketcobblespawners` command
    private fun registerBlanketCobbleSpawnersCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val spawnerCommand = literal("blanketcobblespawners")
            .requires { source ->
                val player = source.player
                player != null && hasPermission(player, "spawner.manage", 2)
            }
            .executes { context ->
                context.source.sendFeedback({ Text.literal("BlanketCobbleSpawners v1.0.0") }, false)
                1
            }
            // Reload subcommand
            .then(
                literal("reload").executes { context ->
                    ConfigManager.reloadSpawnerData() // Call reload config
                    context.source.sendFeedback({ Text.literal("Configuration for BlanketCobbleSpawners has been successfully reloaded.") }, true)
                    logDebug("Configuration reloaded for BlanketCobbleSpawners.")
                    1
                }
            )
            // Give a custom spawner to the player
            .then(
                literal("givespawner").executes { context ->
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
            // Remove all spawned Pokémon from a specific spawner
            .then(
                literal("clearspawned")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { spawnerName ->
                                    if (spawnerName.startsWith(builder.remainingLowerCase)) {
                                        builder.suggest(spawnerName)
                                    }
                                }
                                builder.buildFuture()
                            })
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
                                context.source.sendFeedback({ Text.literal("All Pokémon spawned by spawner '$spawnerName' have been removed.") }, true)
                                1
                            }
                    )
            )

            // Remove a specific spawner (block and config)
            .then(
                literal("removespawner")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerData = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }

                                if (spawnerData == null) {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }

                                // Remove spawner block if visible
                                if (spawnerData.visible) {
                                    removeSpawnerBlock(context.source.server, spawnerData.spawnerPos, spawnerData.dimension)
                                }

                                // Remove spawner from config
                                ConfigManager.spawners.remove(spawnerData.spawnerPos)
                                ConfigManager.saveSpawnerData()
                                context.source.sendFeedback({ Text.literal("Spawner '$spawnerName' has been removed.") }, true)
                                1
                            }
                    )
            )
            // Open the GUI for managing a spawner
            .then(
                literal("opengui")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
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
                                    GuiManager.openSpawnerGui(player, spawnerEntry.spawnerPos)
                                    context.source.sendFeedback({ Text.literal("GUI for spawner '$spawnerName' has been opened.") }, true)
                                    return@executes 1
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // Add a Pokémon to a specific spawner
            .then(
                literal("addmon")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
                            .then(
                                argument("pokemonName", word())
                                    .suggests(SuggestionProvider { context, builder ->
                                        PokemonSpecies.species.map { it.name }.forEach { speciesName ->
                                            if (speciesName.startsWith(builder.remainingLowerCase)) {
                                                builder.suggest(speciesName)
                                            }
                                        }
                                        builder.buildFuture()
                                    })
                                    .executes { context ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val pokemonName = getString(context, "pokemonName")

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                            return@executes 0
                                        }

                                        val newEntry = PokemonSpawnEntry(
                                            pokemonName = pokemonName,
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
                                            context.source.sendFeedback({ Text.literal("Added Pokémon '$pokemonName' to spawner '$spawnerName'.") }, true)
                                            return@executes 1
                                        } else {
                                            context.source.sendError(Text.literal("Failed to add Pokémon '$pokemonName' to spawner '$spawnerName'."))
                                            return@executes 0
                                        }
                                    }
                            )
                    )
            )
            .then(
                literal("removemon")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
                            .then(
                                argument("pokemonName", word())
                                    .suggests(SuggestionProvider { context, builder ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        spawnerEntry?.selectedPokemon?.forEach { entry ->
                                            if (entry.pokemonName.startsWith(builder.remainingLowerCase)) {
                                                builder.suggest(entry.pokemonName)
                                            }
                                        }
                                        builder.buildFuture()
                                    })
                                    .executes { context ->
                                        val spawnerName = getString(context, "spawnerName")
                                        val pokemonName = getString(context, "pokemonName")

                                        val spawnerEntry = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }
                                        if (spawnerEntry == null) {
                                            context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                            return@executes 0
                                        }

                                        val existingEntry = spawnerEntry.selectedPokemon.find {
                                            it.pokemonName.equals(pokemonName, ignoreCase = true)
                                        }

                                        if (existingEntry != null) {
                                            if (ConfigManager.removePokemonSpawnEntry(spawnerEntry.spawnerPos, pokemonName)) {
                                                context.source.sendFeedback({ Text.literal("Deselected Pokémon '$pokemonName' from spawner '$spawnerName'.") }, true)
                                                return@executes 1
                                            } else {
                                                context.source.sendError(Text.literal("Failed to deselect Pokémon '$pokemonName' from spawner '$spawnerName'."))
                                                return@executes 0
                                            }
                                        } else {
                                            context.source.sendError(Text.literal("Pokémon '$pokemonName' is not selected for spawner '$spawnerName'."))
                                            return@executes 0
                                        }
                                    }
                            )
                    )
            )
            // Toggle the visibility of a spawner
            .then(
                literal("togglevisibility")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
                            .executes { context ->
                                val spawnerName = getString(context, "spawnerName")
                                val spawnerData = ConfigManager.spawners.values.find { it.spawnerName == spawnerName }

                                if (spawnerData != null) {
                                    spawnerData.visible = !spawnerData.visible
                                    toggleSpawnerVisibility(context.source.server, spawnerData) // Apply change
                                    ConfigManager.saveSpawnerData()
                                    context.source.sendFeedback({ Text.literal("Spawner '$spawnerName' visibility has been toggled.") }, true)
                                    return@executes 1
                                } else {
                                    context.source.sendError(Text.literal("Spawner '$spawnerName' not found."))
                                    return@executes 0
                                }
                            }
                    )
            )
            // Inside CommandRegistrar
            .then(
                literal("visualisespawnradius")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
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
                                    return@executes 1
                                } else {
                                    context.source.sendError(Text.literal("Only players can run this command."))
                                    return@executes 0
                                }
                            }
                    )
            )

            // New command to list spawners with coordinates
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
                        return@executes 1
                    }
            )
            .then(
                literal("tptospawner")
                    .then(
                        argument("spawnerName", word())
                            .suggests(SuggestionProvider { context, builder ->
                                val spawnerNames = ConfigManager.spawners.values.map { it.spawnerName }
                                spawnerNames.forEach { builder.suggest(it) }
                                builder.buildFuture()
                            })
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
        // Log the registration
        logDebug("Registering command: /blanketcobblespawners")
        dispatcher.register(spawnerCommand)
    }


    // Function to create the NBT data for the custom spawner, fully removing default lore and adding custom title and lore
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

        // **Remove default entity tag if it's present**
        if (nbt.contains("BlockEntityTag")) {
            nbt.remove("BlockEntityTag") // This will strip any default behavior linked to the spawner
        }

        return nbt
    }

    // Helper function to toggle spawner visibility
    private fun toggleSpawnerVisibility(server: MinecraftServer, spawnerData: SpawnerData) {
        val registryKey = parseDimension(spawnerData.dimension)
        val world = server.getWorld(registryKey) ?: return

        if (spawnerData.visible) {
            // Make the spawner visible (place the block back)
            world.setBlockState(spawnerData.spawnerPos, Blocks.SPAWNER.defaultState)
        } else {
            // Make the spawner invisible (remove the block but keep functionality)
            world.setBlockState(spawnerData.spawnerPos, Blocks.AIR.defaultState)
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
    private fun parseDimension(dimensionString: String): RegistryKey<net.minecraft.world.World> {
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
