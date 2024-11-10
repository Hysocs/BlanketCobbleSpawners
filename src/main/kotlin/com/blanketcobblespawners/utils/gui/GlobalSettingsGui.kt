// File: GlobalSettingsGui.kt
package com.blanketcobblespawners.utils.gui

import com.blanketcobblespawners.utils.ConfigManager
import com.blanketcobblespawners.utils.ConfigManager.configData
import com.blanketcobblespawners.utils.GlobalConfig
import com.blanketcobblespawners.utils.CustomGui
import com.blanketcobblespawners.utils.InteractionContext
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.slf4j.LoggerFactory

object GlobalSettingsGui {

    private val logger = LoggerFactory.getLogger("GlobalSettingsGui")

    fun openGlobalSettingsGui(player: ServerPlayerEntity) {
        // Load globalConfig from the configData
        val globalConfig = configData.globalConfig
        val layout = generateGlobalSettingsLayout(globalConfig)

        val onInteract: (InteractionContext) -> Unit = { context ->
            try {
                when (context.slotIndex) {
                    11 -> {
                        configData.globalConfig.debugEnabled = !configData.globalConfig.debugEnabled
                        logger.info("Toggled Debug Mode to ${configData.globalConfig.debugEnabled}")
                        updateGuiItem(context, player, "Debug Mode", configData.globalConfig.debugEnabled, Formatting.GOLD)
                        ConfigManager.saveConfigData()
                        player.sendMessage(Text.literal("Debug Mode is now ${if (configData.globalConfig.debugEnabled) "ON" else "OFF"}"), false)
                    }
                    13 -> {
                        configData.globalConfig.cullSpawnerPokemonOnServerStop = !configData.globalConfig.cullSpawnerPokemonOnServerStop
                        logger.info("Toggled Cull Spawner Pokémon on Stop to ${configData.globalConfig.cullSpawnerPokemonOnServerStop}")
                        updateGuiItem(context, player, "Cull Spawner Pokémon on Stop", configData.globalConfig.cullSpawnerPokemonOnServerStop, Formatting.RED)
                        ConfigManager.saveConfigData()
                        player.sendMessage(Text.literal("Cull Spawner Pokémon on Stop is now ${if (configData.globalConfig.cullSpawnerPokemonOnServerStop) "ON" else "OFF"}"), false)
                    }
                    15 -> {
                        configData.globalConfig.showUnimplementedPokemonInGui = !configData.globalConfig.showUnimplementedPokemonInGui
                        logger.info("Toggled Show Unimplemented Pokémon in GUI to ${configData.globalConfig.showUnimplementedPokemonInGui}")
                        updateGuiItem(context, player, "Show Unimplemented Pokémon in GUI", configData.globalConfig.showUnimplementedPokemonInGui, Formatting.BLUE)
                        ConfigManager.saveConfigData()
                        player.sendMessage(Text.literal("Show Unimplemented Pokémon in GUI is now ${if (configData.globalConfig.showUnimplementedPokemonInGui) "ON" else "OFF"}"), false)
                    }
                    31 -> {
                        configData.globalConfig.showFormsInGui = !configData.globalConfig.showFormsInGui
                        logger.info("Toggled Show Form in GUI to ${configData.globalConfig.showFormsInGui}")
                        updateGuiItem(context, player, "Show Form in GUI", configData.globalConfig.showFormsInGui, Formatting.GREEN)
                        ConfigManager.saveConfigData()
                        player.sendMessage(Text.literal("Show Form in GUI is now ${if (configData.globalConfig.showFormsInGui) "ON" else "OFF"}"), false)
                    }
                    49 -> {
                        logger.info("Back button clicked. Saving config and returning to Spawner List GUI.")
                        ConfigManager.saveConfigData()
                        SpawnerListGui.openSpawnerListGui(player)
                    }
                    else -> {
                        logger.warn("Unknown setting clicked at slot ${context.slotIndex}")
                        player.sendMessage(Text.literal("Unknown setting clicked at slot ${context.slotIndex}"), false)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error handling GUI interaction at slot ${context.slotIndex}: ${e.message}", e)
                player.sendMessage(Text.literal("An error occurred while updating settings."), false)
            }
        }

        val onClose: (Inventory) -> Unit = {
            logger.info("Global settings GUI closed by player ${player.name.string}.")
            player.sendMessage(Text.literal("Global settings closed."), false)
        }

        CustomGui.openGui(
            player,
            "Edit Global Settings",
            layout,
            onInteract,
            onClose
        )
    }

    private fun generateGlobalSettingsLayout(globalConfig: GlobalConfig): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        layout[11] = CustomGui.createPlayerHeadButton(
            "DebugModeTexture",
            Text.literal("Debug Mode").styled { it.withColor(Formatting.GOLD).withBold(false).withItalic(false) },
            listOf(
                Text.literal("Toggle Debug Mode").styled { it.withColor(Formatting.GRAY).withItalic(false) },
                Text.literal("Status: ${if (globalConfig.debugEnabled) "ON" else "OFF"}").styled {
                    it.withColor(if (globalConfig.debugEnabled) Formatting.GREEN else Formatting.GRAY).withItalic(false)
                }
            ),
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmI2YzkyZWUwNmY2MmY0Y2YyOWIyYmY0M2I4MTI1YzI0YmI2YTJjNjU4Y2ZiNzE4ZGY5ZTk1ZjJlYzM0NTA1ZSJ9fX0="
        )

        layout[13] = CustomGui.createPlayerHeadButton(
            "CullSpawnerPokemonTexture",
            Text.literal("Cull Spawner Pokémon on Stop").styled { it.withColor(Formatting.RED).withBold(false).withItalic(false) },
            listOf(
                Text.literal("Toggle culling of spawner Pokémon on stop").styled { it.withColor(Formatting.GRAY).withItalic(false) },
                Text.literal("Status: ${if (globalConfig.cullSpawnerPokemonOnServerStop) "ON" else "OFF"}").styled {
                    it.withColor(if (globalConfig.cullSpawnerPokemonOnServerStop) Formatting.GREEN else Formatting.GRAY).withItalic(false)
                }
            ),
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzMzZGJmYjdkZmYyNTY0ZjZiMTc2OGQ3MmEyY2I0M2E4ZDY2YWMzZWFmYzI4MmRkOWJhZDIyN2EzYTAzMDg0In19fQ=="
        )

        layout[15] = CustomGui.createPlayerHeadButton(
            "UnimplementedPokemonTexture",
            Text.literal("Show Unimplemented Pokémon in GUI").styled { it.withColor(Formatting.BLUE).withBold(false).withItalic(false) },
            listOf(
                Text.literal("Toggle display of unimplemented Pokémon").styled { it.withColor(Formatting.GRAY).withItalic(false) },
                Text.literal("Status: ${if (globalConfig.showUnimplementedPokemonInGui) "ON" else "OFF"}").styled {
                    it.withColor(if (globalConfig.showUnimplementedPokemonInGui) Formatting.GREEN else Formatting.GRAY).withItalic(false)
                }
            ),
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWRlOWZjYzA1YTZmYTM3YTI4MGMzMjMwZTc2OWQyM2EwZDMwMDJjMDQ1MjM0MzU2YmQ1MWY0NzRhMjcwZTQzOSJ9fX0="
        )

        layout[31] = CustomGui.createPlayerHeadButton(
            "ShowFormTexture",
            Text.literal("Show Form in GUI").styled { it.withColor(Formatting.GREEN).withBold(false).withItalic(false) },
            listOf(
                Text.literal("Toggle display of forms in GUI").styled { it.withColor(Formatting.GRAY).withItalic(false) },
                Text.literal("Status: ${if (globalConfig.showFormsInGui) "ON" else "OFF"}").styled {
                    it.withColor(if (globalConfig.showFormsInGui) Formatting.GREEN else Formatting.GRAY).withItalic(false)
                }
            ),
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmFmZDU2ZWE5OThjMDhjNDcyY2IyM2VjY2RlMjE3OTk3OGE0ZTRhNGM5ZTRkM2RmOGVlYWMxYmYyZGU2MzhhYSJ9fX0="
        )

        // Return to Spawner List button
        layout[49] = CustomGui.createPlayerHeadButton(
            "ReturnToSpawnerListTexture",
            Text.literal("Back").styled { it.withColor(Formatting.WHITE).withBold(false).withItalic(false) },
            listOf(Text.literal("Return to Spawner List").styled { it.withColor(Formatting.GRAY).withItalic(false) }),
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzI0MzE5MTFmNDE3OGI0ZDJiNDEzYWE3ZjVjNzhhZTQ0NDdmZTkyNDY5NDNjMzFkZjMxMTYzYzBlMDQzZTBkNiJ9fX0="
        )
        // Fill remaining empty slots with gray stained glass panes
        for (i in layout.indices) {
            if (layout[i] == ItemStack.EMPTY) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    setCustomName(Text.literal(" "))
                }
            }
        }

        return layout
    }

    private fun updateGuiItem(context: InteractionContext, player: ServerPlayerEntity, settingName: String, newValue: Boolean, color: Formatting) {
        // Get the existing item in the slot (which is the player head with texture)
        val itemStack = context.clickedStack

        // Update the name while keeping the same color, not bold, and explicitly setting italic to false
        itemStack.setCustomName(
            Text.literal(settingName).styled {
                it.withColor(color).withBold(false).withItalic(false)
            }
        )

        // Update the lore to only modify the status line
        val itemNbt = itemStack.orCreateNbt
        val displayNbt = itemNbt.getCompound("display")
        val loreList = displayNbt.getList("Lore", 8) as NbtList

        // Modify only the "Status" line in the lore with proper color
        var updatedStatus = false
        for (i in 0 until loreList.size) {
            val loreLine = loreList.getString(i)
            if (loreLine.contains("Status:")) {
                loreList.set(
                    i,
                    NbtString.of(
                        "{\"text\":\"Status: ${if (newValue) "ON" else "OFF"}\"," +
                                "\"color\":\"${if (newValue) "green" else "gray"}\",\"italic\":false}"
                    )
                )
                updatedStatus = true
                break
            }
        }

        // If no "Status:" line was found, add it at the end
        if (!updatedStatus) {
            loreList.add(
                NbtString.of(
                    "{\"text\":\"Status: ${if (newValue) "ON" else "OFF"}\"," +
                            "\"color\":\"${if (newValue) "green" else "gray"}\",\"italic\":false}"
                )
            )
        }

        displayNbt.put("Lore", loreList)
        itemNbt.put("display", displayNbt)
        itemStack.nbt = itemNbt

        // Update the item in the player's screen
        player.currentScreenHandler.slots[context.slotIndex].stack = itemStack
        player.currentScreenHandler.sendContentUpdates()
    }
}
