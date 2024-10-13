// File: SpawnShinyEditorGui.kt
package com.blanketcobblespawners.utils.Gui

import com.blanketcobblespawners.utils.ConfigManager
import com.blanketcobblespawners.utils.ConfigManager.logDebug
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
import net.minecraft.util.ClickType
import org.slf4j.LoggerFactory

object SpawnShinyEditorGui {
    private val logger = LoggerFactory.getLogger(SpawnShinyEditorGui::class.java)

    data class ChanceButton(
        val slotIndex: Int,
        val chanceType: String, // "shiny" or "spawn"
        val action: String, // "increase" or "decrease"
        val leftDelta: Double,
        val rightDelta: Double
    )

    // Define the buttons with their respective increments/decrements
    private val chanceButtons = listOf(
        // Spawn Chance Buttons
        ChanceButton(slotIndex = 10, chanceType = "spawn", action = "decrease", leftDelta = -0.01, rightDelta = -0.05),
        ChanceButton(slotIndex = 11, chanceType = "spawn", action = "decrease", leftDelta = -0.1, rightDelta = -0.5),
        ChanceButton(slotIndex = 12, chanceType = "spawn", action = "decrease", leftDelta = -1.0, rightDelta = -5.0),
        ChanceButton(slotIndex = 14, chanceType = "spawn", action = "increase", leftDelta = 0.01, rightDelta = 0.05),
        ChanceButton(slotIndex = 15, chanceType = "spawn", action = "increase", leftDelta = 0.1, rightDelta = 0.5),
        ChanceButton(slotIndex = 16, chanceType = "spawn", action = "increase", leftDelta = 1.0, rightDelta = 5.0),
        // Shiny Chance Buttons
        ChanceButton(slotIndex = 19, chanceType = "shiny", action = "decrease", leftDelta = -0.01, rightDelta = -0.05),
        ChanceButton(slotIndex = 20, chanceType = "shiny", action = "decrease", leftDelta = -0.1, rightDelta = -0.5),
        ChanceButton(slotIndex = 21, chanceType = "shiny", action = "decrease", leftDelta = -1.0, rightDelta = -5.0),
        ChanceButton(slotIndex = 23, chanceType = "shiny", action = "increase", leftDelta = 0.01, rightDelta = 0.05),
        ChanceButton(slotIndex = 24, chanceType = "shiny", action = "increase", leftDelta = 0.1, rightDelta = 0.5),
        ChanceButton(slotIndex = 25, chanceType = "shiny", action = "increase", leftDelta = 1.0, rightDelta = 5.0)
    )

    private val chanceButtonMap = chanceButtons.associateBy { it.slotIndex }

    /**
     * Opens the Spawn and Shiny Editor GUI for a specific Pokémon.
     */
    fun openSpawnShinyEditorGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (selectedEntry == null) {
            player.sendMessage(Text.literal("Pokémon '$pokemonName' not found in spawner."), false)
            logDebug("Pokémon '$pokemonName' not found in spawner at $spawnerPos.")
            return
        }

        val layout = generateSpawnShinyEditorLayout(selectedEntry)

        GuiManager.spawnerGuisOpen[spawnerPos] = player

        val onInteract: (InteractionContext) -> Unit = onInteract@{ context ->
            val slotIndex = context.slotIndex
            val clickType = context.clickType

            if (chanceButtonMap.containsKey(slotIndex)) {
                val chanceButton = chanceButtonMap[slotIndex]!!
                val delta = if (clickType == ClickType.LEFT) chanceButton.leftDelta else chanceButton.rightDelta
                if (chanceButton.chanceType == "shiny") {
                    updateShinyChance(spawnerPos, pokemonName, delta, player)
                } else if (chanceButton.chanceType == "spawn") {
                    updateSpawnChance(spawnerPos, pokemonName, delta, player)
                }
                return@onInteract
            }

            val clickedItem = context.clickedStack
            if (clickedItem.item == Items.ARROW) {
                CustomGui.closeGui(player)
                player.sendMessage(Text.literal("Returning to Edit Pokémon menu"), false)
                GuiManager.openPokemonEditSubGui(player, spawnerPos, pokemonName)
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(Text.literal("Spawn and Shiny Editor closed for $pokemonName"), false)
        }

        val guiTitle = "Edit Spawn & Shiny Chances for $pokemonName"

        CustomGui.openGui(
            player,
            guiTitle,
            layout,
            onInteract,
            onClose
        )
    }

    /**
     * Generates the layout for the Spawn and Shiny Editor GUI.
     */
    private fun generateSpawnShinyEditorLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        // Add Chance Buttons to the layout
        chanceButtons.forEach { button ->
            val itemStack = createChanceButtonItemStack(button, selectedEntry)
            layout[button.slotIndex] = itemStack
        }

        // Current Spawn Chance Display
        layout[13] = createCurrentSpawnChanceDisplay(selectedEntry.spawnChance)
        // Current Shiny Chance Display
        layout[22] = createCurrentShinyChanceDisplay(selectedEntry.shinyChance)

        // Fill the rest with gray stained glass panes except for the button slots and back button
        val excludedSlots = chanceButtons.map { it.slotIndex } + listOf(13, 22, 49)

        for (i in 0 until 54) {
            if (i !in excludedSlots) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    setCustomName(Text.literal(" "))
                }
            }
        }

        // Back Button remains at slot 49
        layout[49] = ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back"))
            GuiManager.setItemLore(this, listOf("§eClick to return"))
        }

        return layout
    }

    private fun createChanceButtonItemStack(button: ChanceButton, selectedEntry: PokemonSpawnEntry): ItemStack {
        val (slotIndex, chanceType, action, leftDelta, rightDelta) = button
        val itemName = "${action.capitalize()} ${chanceType.capitalize()} Chance"
        val itemStack = ItemStack(Items.PAPER).apply {
            setCustomName(Text.literal(itemName))
            val currentChance = if (chanceType == "shiny") selectedEntry.shinyChance else selectedEntry.spawnChance
            val lore = listOf(
                "§aCurrent ${chanceType.capitalize()} Chance: §f${"%.2f".format(currentChance)}%",
                "§eLeft-click: ${if (leftDelta > 0) "+" else ""}${"%.2f".format(leftDelta)}%",
                "§eRight-click: ${if (rightDelta > 0) "+" else ""}${"%.2f".format(rightDelta)}%"
            )
            GuiManager.setItemLore(this, lore)
        }
        return itemStack
    }

    private fun createCurrentSpawnChanceDisplay(chance: Double): ItemStack {
        return ItemStack(Items.BOOK).apply {
            setCustomName(Text.literal("Current Spawn Chance"))
            GuiManager.setItemLore(this, listOf(
                "§aSpawn Chance: §f${"%.2f".format(chance)}%"
            ))
        }
    }

    private fun createCurrentShinyChanceDisplay(chance: Double): ItemStack {
        return ItemStack(Items.BOOK).apply {
            setCustomName(Text.literal("Current Shiny Chance"))
            GuiManager.setItemLore(this, listOf(
                "§aShiny Chance: §f${"%.2f".format(chance)}%"
            ))
        }
    }

    /**
     * Updates the spawn chance value for the selected Pokémon.
     */
    private fun updateSpawnChance(spawnerPos: BlockPos, pokemonName: String, delta: Double, player: ServerPlayerEntity) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            selectedEntry.spawnChance = (selectedEntry.spawnChance + delta).coerceIn(0.0, 100.0)
        } ?: run {
            player.sendMessage(Text.literal("Failed to update spawn chance."), false)
            return
        }

        // Refresh the GUI
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (updatedEntry != null) {
            refreshGui(player, updatedEntry)
            logDebug("Updated spawnChance to ${updatedEntry.spawnChance}% for $pokemonName at spawner $spawnerPos.")
        }
    }

    /**
     * Updates the shiny chance value for the selected Pokémon.
     */
    private fun updateShinyChance(spawnerPos: BlockPos, pokemonName: String, delta: Double, player: ServerPlayerEntity) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            selectedEntry.shinyChance = (selectedEntry.shinyChance + delta).coerceIn(0.0, 100.0)
        } ?: run {
            player.sendMessage(Text.literal("Failed to update shiny chance."), false)
            return
        }

        // Refresh the GUI
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (updatedEntry != null) {
            refreshGui(player, updatedEntry)
            logDebug("Updated shinyChance to ${updatedEntry.shinyChance}% for $pokemonName at spawner $spawnerPos.")
        }
    }

    /**
     * Refreshes the Spawn and Shiny Editor GUI items based on the current state.
     */
    private fun refreshGui(player: ServerPlayerEntity, selectedEntry: PokemonSpawnEntry) {
        val layout = generateSpawnShinyEditorLayout(selectedEntry)

        val screenHandler = player.currentScreenHandler
        layout.forEachIndexed { index, itemStack ->
            if (index < screenHandler.slots.size) {
                screenHandler.slots[index].stack = itemStack
            }
        }

        screenHandler.sendContentUpdates()
    }
}
