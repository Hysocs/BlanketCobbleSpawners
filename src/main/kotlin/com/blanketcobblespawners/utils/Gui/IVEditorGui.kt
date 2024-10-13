package com.blanketcobblespawners.utils.Gui

import com.blanketcobblespawners.utils.ConfigManager
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.CustomGui
import com.blanketcobblespawners.utils.InteractionContext
import com.blanketcobblespawners.utils.PokemonSpawnEntry
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory

object IVEditorGui {
    private val logger = LoggerFactory.getLogger(IVEditorGui::class.java)

    /**
     * Opens the IV editor GUI for a specific Pokémon.
     */
    fun openIVEditorGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (selectedEntry == null) {
            player.sendMessage(Text.literal("Pokemon '$pokemonName' not found in spawner."), false)
            return
        }

        val layout = generateIVEditorLayout(selectedEntry)

        GuiManager.spawnerGuisOpen[spawnerPos] = player

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedItem = context.clickedStack
            val clickedItemName = clickedItem.name?.string ?: ""

            when (clickedItem.item) {
                Items.PAPER -> {
                    when (context.clickType) {
                        ClickType.LEFT -> {
                            updateIVValue(spawnerPos, selectedEntry.pokemonName, clickedItemName, -1, player)
                        }
                        ClickType.RIGHT -> {
                            updateIVValue(spawnerPos, selectedEntry.pokemonName, clickedItemName, 1, player)
                        }
                    }
                }
                Items.LEVER -> {
                    toggleAllowCustomIvsWithoutClosing(spawnerPos, selectedEntry.pokemonName, player, context.slotIndex)
                }
                Items.ARROW -> {
                    CustomGui.closeGui(player)
                    player.sendMessage(Text.literal("Returning to Edit Pokémon menu"), false)
                    GuiManager.openPokemonEditSubGui(player, spawnerPos, selectedEntry.pokemonName)
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            GuiManager.spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(Text.literal("IV Editor closed for ${selectedEntry.pokemonName}"), false)
        }

        CustomGui.openGui(
            player,
            "Edit IVs for ${selectedEntry.pokemonName}",
            layout,
            onInteract,
            onClose
        )
    }

    /**
     * Generates the layout for the IV editor GUI.
     */
    private fun generateIVEditorLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        val ivSettings = selectedEntry.ivSettings

        // Add paper items for each IV stat (HP, Attack, Defense, etc.)
        layout[0] = createIVItem("HP Min", ivSettings.minIVHp)
        layout[1] = createIVItem("HP Max", ivSettings.maxIVHp)

        layout[2] = createIVItem("Attack Min", ivSettings.minIVAttack)
        layout[3] = createIVItem("Attack Max", ivSettings.maxIVAttack)

        layout[4] = createIVItem("Defense Min", ivSettings.minIVDefense)
        layout[5] = createIVItem("Defense Max", ivSettings.maxIVDefense)

        layout[6] = createIVItem("Special Attack Min", ivSettings.minIVSpecialAttack)
        layout[7] = createIVItem("Special Attack Max", ivSettings.maxIVSpecialAttack)

        layout[8] = createIVItem("Special Defense Min", ivSettings.minIVSpecialDefense)
        layout[9] = createIVItem("Special Defense Max", ivSettings.maxIVSpecialDefense)

        layout[10] = createIVItem("Speed Min", ivSettings.minIVSpeed)
        layout[11] = createIVItem("Speed Max", ivSettings.maxIVSpeed)

        // Fill the rest with gray stained glass panes except for the toggle button and back button
        for (i in 12 until 54) {
            if (i != 31 && i != 49) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    GuiManager.setItemLore(this, listOf(" "))
                    setCustomName(Text.literal(" "))
                }
            }
        }

        // Add toggle button for custom IVs
        layout[31] = ItemStack(Items.LEVER).apply {
            setCustomName(Text.literal("Allow Custom IVs: ${if (ivSettings.allowCustomIvs) "ON" else "OFF"}"))
            GuiManager.setItemLore(this, listOf("§eClick to toggle"))
        }

        // Add the back button to return to the Pokémon editor
        layout[49] = ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back"))
        }

        return layout
    }

    /**
     * Creates an IV editing ItemStack for a specific stat.
     */
    private fun createIVItem(statName: String, value: Int): ItemStack {
        return ItemStack(Items.PAPER).apply {
            setCustomName(Text.literal(statName))
            GuiManager.setItemLore(this, listOf(
                "§aCurrent Value:",
                "§7Value: §f$value",
                "§eLeft-click to decrease",
                "§eRight-click to increase"
            ))
        }
    }

    /**
     * Updates the IV value for the given Pokémon entry.
     */
    private fun updateIVValue(spawnerPos: BlockPos, pokemonName: String, statName: String, delta: Int, player: ServerPlayerEntity) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            val ivSettings = selectedEntry.ivSettings
            when (statName.lowercase()) {
                "hp min" -> ivSettings.minIVHp = (ivSettings.minIVHp + delta).coerceIn(0, 31)
                "hp max" -> ivSettings.maxIVHp = (ivSettings.maxIVHp + delta).coerceIn(0, 31)
                "attack min" -> ivSettings.minIVAttack = (ivSettings.minIVAttack + delta).coerceIn(0, 31)
                "attack max" -> ivSettings.maxIVAttack = (ivSettings.maxIVAttack + delta).coerceIn(0, 31)
                "defense min" -> ivSettings.minIVDefense = (ivSettings.minIVDefense + delta).coerceIn(0, 31)
                "defense max" -> ivSettings.maxIVDefense = (ivSettings.maxIVDefense + delta).coerceIn(0, 31)
                "special attack min" -> ivSettings.minIVSpecialAttack = (ivSettings.minIVSpecialAttack + delta).coerceIn(0, 31)
                "special attack max" -> ivSettings.maxIVSpecialAttack = (ivSettings.maxIVSpecialAttack + delta).coerceIn(0, 31)
                "special defense min" -> ivSettings.minIVSpecialDefense = (ivSettings.minIVSpecialDefense + delta).coerceIn(0, 31)
                "special defense max" -> ivSettings.maxIVSpecialDefense = (ivSettings.maxIVSpecialDefense + delta).coerceIn(0, 31)
                "speed min" -> ivSettings.minIVSpeed = (ivSettings.minIVSpeed + delta).coerceIn(0, 31)
                "speed max" -> ivSettings.maxIVSpeed = (ivSettings.maxIVSpeed + delta).coerceIn(0, 31)
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to update IV value."), false)
        }

        // After updating, refresh the GUI
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (updatedEntry != null) {
            refreshGui(player, updatedEntry)
            logDebug("Updated IVs for ${updatedEntry.pokemonName} at spawner $spawnerPos and saved to JSON.")
        }
    }

    /**
     * Refreshes the IV editor GUI items based on the current state.
     */
    private fun refreshGui(player: ServerPlayerEntity, selectedEntry: PokemonSpawnEntry) {
        val layout = generateIVEditorLayout(selectedEntry)

        val screenHandler = player.currentScreenHandler
        layout.forEachIndexed { index, itemStack ->
            if (index < screenHandler.slots.size) {
                screenHandler.slots[index].stack = itemStack
            }
        }

        screenHandler.sendContentUpdates()
    }

    /**
     * Toggles the allowCustomIvs flag without closing the GUI and updates the lever lore.
     */
    private fun toggleAllowCustomIvsWithoutClosing(spawnerPos: BlockPos, pokemonName: String, player: ServerPlayerEntity, leverSlot: Int) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName) { selectedEntry ->
            val ivSettings = selectedEntry.ivSettings
            ivSettings.allowCustomIvs = !ivSettings.allowCustomIvs
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle allowCustomIvs."), false)
            return
        }

        // Update the lever item to reflect the new value (ON/OFF)
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (selectedEntry != null) {
            val leverItem = ItemStack(Items.LEVER).apply {
                setCustomName(Text.literal("Allow Custom IVs: ${if (selectedEntry.ivSettings.allowCustomIvs) "ON" else "OFF"}"))
                GuiManager.setItemLore(this, listOf("§eClick to toggle"))
            }

            // Update the GUI with the new lever lore without closing
            val screenHandler = player.currentScreenHandler
            if (leverSlot < screenHandler.slots.size) {
                screenHandler.slots[leverSlot].stack = leverItem
            }

            screenHandler.sendContentUpdates()

            logDebug("Toggled allowCustomIvs for ${selectedEntry.pokemonName} at spawner $spawnerPos.")
        }
    }
}
