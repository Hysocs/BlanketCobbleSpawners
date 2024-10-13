package com.blanketcobblespawners.utils.Gui

import com.blanketcobblespawners.utils.ConfigManager
import com.blanketcobblespawners.utils.Gui.GuiManager.spawnerGuisOpen
import com.blanketcobblespawners.utils.CustomGui
import com.blanketcobblespawners.utils.InteractionContext
import com.blanketcobblespawners.utils.PokemonSpawnEntry
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory

object OtherEditableGui {
    private val logger = LoggerFactory.getLogger(OtherEditableGui::class.java)

    /**
     * Opens the Other Editable GUI for a specific Pokémon.
     */
    fun openOtherEditableGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (selectedEntry == null) {
            player.sendMessage(Text.literal("Pokémon '$pokemonName' not found in spawner."), false)
            logger.warn("Pokémon '$pokemonName' not found in spawner at $spawnerPos.")
            return
        }

        val layout = generateOtherEditableLayout(selectedEntry)

        GuiManager.spawnerGuisOpen[spawnerPos] = player

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedItem = context.clickedStack
            val clickedItemName = clickedItem.name?.string ?: ""

            when (clickedItem.item) {
                Items.LEVER -> {
                    toggleIsCatchable(spawnerPos, pokemonName, player, context.slotIndex)
                }
                Items.CLOCK -> {
                    toggleSpawnTime(spawnerPos, pokemonName, player, context.slotIndex)
                }
                Items.SUNFLOWER -> { // New item for weather toggle
                    toggleSpawnWeather(spawnerPos, pokemonName, player, context.slotIndex)
                }
                Items.ARROW -> {
                    CustomGui.closeGui(player)
                    player.sendMessage(Text.literal("Returning to Edit Pokémon menu"), false)
                    GuiManager.openPokemonEditSubGui(player, spawnerPos, pokemonName)
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(Text.literal("Other Editable GUI closed for $pokemonName"), false)
        }

        CustomGui.openGui(
            player,
            "Edit Other Properties for $pokemonName",
            layout,
            onInteract,
            onClose
        )
    }

    /**
     * Generates the layout for the Other Editable GUI.
     */
    private fun generateOtherEditableLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        // Toggle Button for isCatchable
        layout[22] = createIsCatchableToggleButton(selectedEntry.captureSettings.isCatchable)

        // Button for spawn time toggle
        layout[24] = createSpawnTimeToggleButton(selectedEntry.spawnSettings.spawnTime)

        // New button for spawn weather toggle
        layout[20] = createSpawnWeatherToggleButton(selectedEntry.spawnSettings.spawnWeather)

        // Fill the rest with gray stained glass panes except for the toggle buttons and back button
        for (i in 0 until 54) {
            if (i !in listOf(20, 22, 24, 49)) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    setCustomName(Text.literal(" "))
                }
            }
        }

        // Back Button
        layout[49] = ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back"))
            GuiManager.setItemLore(this, listOf("§eClick to return"))
        }

        return layout
    }

    /**
     * Creates a toggle button for isCatchable.
     */
    private fun createIsCatchableToggleButton(isCatchable: Boolean): ItemStack {
        val toggleName = if (isCatchable) "Set Catchable: ON" else "Set Catchable: OFF"

        return ItemStack(Items.LEVER).apply {
            setCustomName(Text.literal(toggleName))
            GuiManager.setItemLore(this, listOf("§eClick to toggle"))
        }
    }

    /**
     * Creates a toggle button for spawnTime.
     */
    private fun createSpawnTimeToggleButton(spawnTime: String): ItemStack {
        val displayName = when (spawnTime) {
            "DAY" -> "Spawn Time: DAY"
            "NIGHT" -> "Spawn Time: NIGHT"
            else -> "Spawn Time: ALL"
        }

        return ItemStack(Items.CLOCK).apply {
            setCustomName(Text.literal(displayName))
            GuiManager.setItemLore(this, listOf("§eClick to toggle spawn time"))
        }
    }

    /**
     * Creates a toggle button for spawnWeather.
     */
    private fun createSpawnWeatherToggleButton(spawnWeather: String): ItemStack {
        val displayName = when (spawnWeather) {
            "CLEAR" -> "Spawn Weather: CLEAR"
            "RAIN" -> "Spawn Weather: RAIN"
            "THUNDER" -> "Spawn Weather: THUNDER"
            else -> "Spawn Weather: ALL"
        }

        return ItemStack(Items.SUNFLOWER).apply { // Using SUNFLOWER as weather icon
            setCustomName(Text.literal(displayName))
            GuiManager.setItemLore(this, listOf("§eClick to toggle spawn weather"))
        }
    }

    /**
     * Toggles the isCatchable property of the selectedEntry.
     */
    private fun toggleIsCatchable(spawnerPos: BlockPos, pokemonName: String, player: ServerPlayerEntity, leverSlot: Int) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            selectedEntry.captureSettings.isCatchable = !selectedEntry.captureSettings.isCatchable
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle isCatchable."), false)
            return
        }

        // Update the lever item to reflect the new value (ON/OFF)
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (updatedEntry != null) {
            val leverItem = createIsCatchableToggleButton(updatedEntry.captureSettings.isCatchable)

            // Update the GUI with the new lever without closing
            val screenHandler = player.currentScreenHandler
            if (leverSlot < screenHandler.slots.size) {
                screenHandler.slots[leverSlot].stack = leverItem
            }

            screenHandler.sendContentUpdates()

            logger.info("Toggled isCatchable for $pokemonName at spawner $spawnerPos.")

            // Notify the player
            player.sendMessage(Text.literal("Set Catchable to ${if (updatedEntry.captureSettings.isCatchable) "ON" else "OFF"} for $pokemonName."), false)
        }
    }

    /**
     * Toggles the spawnTime property of the selectedEntry.
     */
    private fun toggleSpawnTime(spawnerPos: BlockPos, pokemonName: String, player: ServerPlayerEntity, clockSlot: Int) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            selectedEntry.spawnSettings.spawnTime = when (selectedEntry.spawnSettings.spawnTime) {
                "DAY" -> "NIGHT"
                "NIGHT" -> "BOTH"
                else -> "DAY"
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle spawn time."), false)
            return
        }

        // Update the clock item to reflect the new spawn time
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (updatedEntry != null) {
            val clockItem = createSpawnTimeToggleButton(updatedEntry.spawnSettings.spawnTime)

            // Update the GUI with the new clock item without closing
            val screenHandler = player.currentScreenHandler
            if (clockSlot < screenHandler.slots.size) {
                screenHandler.slots[clockSlot].stack = clockItem
            }

            screenHandler.sendContentUpdates()

            logger.info("Toggled spawn time for $pokemonName at spawner $spawnerPos to ${updatedEntry.spawnSettings.spawnTime}.")

            // Notify the player
            player.sendMessage(Text.literal("Set spawn time to ${updatedEntry.spawnSettings.spawnTime} for $pokemonName."), false)
        }
    }

    /**
     * Toggles the spawnWeather property of the selectedEntry.
     */
    private fun toggleSpawnWeather(spawnerPos: BlockPos, pokemonName: String, player: ServerPlayerEntity, weatherSlot: Int) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            selectedEntry.spawnSettings.spawnWeather = when (selectedEntry.spawnSettings.spawnWeather) {
                "CLEAR" -> "RAIN"
                "RAIN" -> "THUNDER"
                "THUNDER" -> "ALL"
                else -> "CLEAR"
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle spawn weather."), false)
            return
        }

        // Update the weather item to reflect the new spawn weather
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (updatedEntry != null) {
            val weatherItem = createSpawnWeatherToggleButton(updatedEntry.spawnSettings.spawnWeather)

            // Update the GUI with the new weather item without closing
            val screenHandler = player.currentScreenHandler
            if (weatherSlot < screenHandler.slots.size) {
                screenHandler.slots[weatherSlot].stack = weatherItem
            }

            screenHandler.sendContentUpdates()

            logger.info("Toggled spawn weather for $pokemonName at spawner $spawnerPos to ${updatedEntry.spawnSettings.spawnWeather}.")

            // Notify the player
            player.sendMessage(Text.literal("Set spawn weather to ${updatedEntry.spawnSettings.spawnWeather} for $pokemonName."), false)
        }
    }
}
