package com.blanketcobblespawners.utils.gui.pokemonsettings

import com.blanketcobblespawners.utils.ConfigManager
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.CustomGui
import com.blanketcobblespawners.utils.InteractionContext
import com.blanketcobblespawners.utils.PokemonSpawnEntry
import com.blanketcobblespawners.utils.gui.GuiManager
import com.blanketcobblespawners.utils.gui.GuiManager.spawnerGuisOpen
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory

object EVSettingsGui {
    private val logger = LoggerFactory.getLogger(EVSettingsGui::class.java)

    /**
     * Opens the EV editor GUI for a specific Pokémon and form.
     */
    fun openEVEditorGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String, formName: String?) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (selectedEntry == null) {
            player.sendMessage(
                Text.literal("Pokémon '$pokemonName' with form '${formName ?: "Standard"}' not found in spawner."),
                false
            )
            return
        }

        val layout = generateEVEditorLayout(selectedEntry)

        GuiManager.spawnerGuisOpen[spawnerPos] = player

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedItem = context.clickedStack
            val clickedItemName = clickedItem.name?.string ?: ""

            when (clickedItem.item) {
                Items.PAPER -> {
                    when (context.clickType) {
                        ClickType.LEFT -> {
                            updateEVValue(
                                spawnerPos,
                                selectedEntry.pokemonName,
                                selectedEntry.formName,
                                clickedItemName,
                                -1,
                                player,
                                context.slotIndex
                            )
                        }
                        ClickType.RIGHT -> {
                            updateEVValue(
                                spawnerPos,
                                selectedEntry.pokemonName,
                                selectedEntry.formName,
                                clickedItemName,
                                1,
                                player,
                                context.slotIndex
                            )
                        }
                    }
                }
                Items.LEVER -> {
                    toggleAllowCustomEvsWithoutClosing(
                        spawnerPos,
                        selectedEntry.pokemonName,
                        selectedEntry.formName,
                        player,
                        context.slotIndex
                    )
                }
                Items.ARROW -> {
                    CustomGui.closeGui(player)
                    player.sendMessage(Text.literal("Returning to Edit Pokémon menu"), false)
                    GuiManager.openPokemonEditSubGui(
                        player,
                        spawnerPos,
                        selectedEntry.pokemonName,
                        selectedEntry.formName
                    )
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(
                Text.literal("EV Editor closed for ${selectedEntry.pokemonName} (${selectedEntry.formName ?: "Standard"})"),
                false
            )
        }

        CustomGui.openGui(
            player,
            "Edit EVs for ${selectedEntry.pokemonName} (${selectedEntry.formName ?: "Standard"})",
            layout,
            onInteract,
            onClose
        )
    }

    /**
     * Generates the layout for the EV editor GUI.
     */
    private fun generateEVEditorLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        val evSettings = selectedEntry.evSettings

        // Add paper items for each EV stat (HP, Attack, Defense, etc.)
        layout[0] = createEVItem("HP EV", evSettings.evHp)
        layout[1] = createEVItem("Attack EV", evSettings.evAttack)
        layout[2] = createEVItem("Defense EV", evSettings.evDefense)
        layout[3] = createEVItem("Special Attack EV", evSettings.evSpecialAttack)
        layout[4] = createEVItem("Special Defense EV", evSettings.evSpecialDefense)
        layout[5] = createEVItem("Speed EV", evSettings.evSpeed)

        // Fill the rest with gray stained glass panes except for the toggle button and back button
        for (i in 6 until 54) {
            if (i != 31 && i != 49) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    CustomGui.setItemLore(this, listOf(" "))
                    setCustomName(Text.literal(" "))
                }
            }
        }

        // Add toggle button for custom EVs
        layout[31] = ItemStack(Items.LEVER).apply {
            setCustomName(Text.literal("Allow Custom EVs: ${if (evSettings.allowCustomEvsOnDefeat) "ON" else "OFF"}"))
            CustomGui.setItemLore(this, listOf("§eClick to toggle"))
        }

        // Add the back button to return to the Pokémon editor
        layout[49] = ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back"))
        }

        return layout
    }

    /**
     * Creates an EV editing ItemStack for a specific stat.
     */
    private fun createEVItem(statName: String, value: Int): ItemStack {
        return ItemStack(Items.PAPER).apply {
            setCustomName(Text.literal(statName))
            CustomGui.setItemLore(
                this, listOf(
                    "§aCurrent Value:",
                    "§7Value: §f$value",
                    "§eLeft-click to decrease",
                    "§eRight-click to increase"
                )
            )
        }
    }

    /**
     * Updates the EV value for the given Pokémon entry.
     */
    private fun updateEVValue(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        statName: String,
        delta: Int,
        player: ServerPlayerEntity,
        slotIndex: Int
    ) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { selectedEntry ->
            val evSettings = selectedEntry.evSettings
            when (statName.lowercase()) {
                "hp ev" -> evSettings.evHp = (evSettings.evHp + delta).coerceIn(0, 252)
                "attack ev" -> evSettings.evAttack = (evSettings.evAttack + delta).coerceIn(0, 252)
                "defense ev" -> evSettings.evDefense = (evSettings.evDefense + delta).coerceIn(0, 252)
                "special attack ev" -> evSettings.evSpecialAttack = (evSettings.evSpecialAttack + delta).coerceIn(0, 252)
                "special defense ev" -> evSettings.evSpecialDefense = (evSettings.evSpecialDefense + delta).coerceIn(0, 252)
                "speed ev" -> evSettings.evSpeed = (evSettings.evSpeed + delta).coerceIn(0, 252)
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to update EV value."), false)
            return
        }

        // Update the item lore in the GUI for the updated EV stat
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (updatedEntry != null) {
            refreshSingleGuiItem(player, statName, updatedEntry, slotIndex)
            logDebug("Updated EVs for ${updatedEntry.pokemonName} (${updatedEntry.formName ?: "Standard"}) at spawner $spawnerPos and saved to JSON.")
        }
    }

    /**
     * Refreshes a single GUI item to reflect the updated EV value.
     */
    private fun refreshSingleGuiItem(
        player: ServerPlayerEntity,
        statName: String,
        selectedEntry: PokemonSpawnEntry,
        slotIndex: Int
    ) {
        val updatedItem = when (statName.lowercase()) {
            "hp ev" -> createEVItem("HP EV", selectedEntry.evSettings.evHp)
            "attack ev" -> createEVItem("Attack EV", selectedEntry.evSettings.evAttack)
            "defense ev" -> createEVItem("Defense EV", selectedEntry.evSettings.evDefense)
            "special attack ev" -> createEVItem("Special Attack EV", selectedEntry.evSettings.evSpecialAttack)
            "special defense ev" -> createEVItem("Special Defense EV", selectedEntry.evSettings.evSpecialDefense)
            "speed ev" -> createEVItem("Speed EV", selectedEntry.evSettings.evSpeed)
            else -> return
        }

        // Update the specific slot in the GUI
        val screenHandler = player.currentScreenHandler
        if (slotIndex < screenHandler.slots.size) {
            screenHandler.slots[slotIndex].stack = updatedItem
        }

        screenHandler.sendContentUpdates()
    }

    /**
     * Toggles the allowCustomEvsOnDefeat flag without closing the GUI and updates the lever lore.
     */
    private fun toggleAllowCustomEvsWithoutClosing(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        player: ServerPlayerEntity,
        leverSlot: Int
    ) {
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { selectedEntry ->
            selectedEntry.evSettings.allowCustomEvsOnDefeat = !selectedEntry.evSettings.allowCustomEvsOnDefeat
        } ?: run {
            player.sendMessage(Text.literal("Failed to toggle allowCustomEvsOnDefeat."), false)
            return
        }

        // Update the lever item to reflect the new value (ON/OFF)
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (selectedEntry != null) {
            val leverItem = ItemStack(Items.LEVER).apply {
                setCustomName(Text.literal("Allow Custom EVs: ${if (selectedEntry.evSettings.allowCustomEvsOnDefeat) "ON" else "OFF"}"))
                CustomGui.setItemLore(this, listOf("§eClick to toggle"))
            }

            // Update the GUI with the new lever lore without closing
            val screenHandler = player.currentScreenHandler
            if (leverSlot < screenHandler.slots.size) {
                screenHandler.slots[leverSlot].stack = leverItem
            }

            screenHandler.sendContentUpdates()

            logDebug("Toggled allowCustomEvsOnDefeat for ${selectedEntry.pokemonName} (${selectedEntry.formName ?: "Standard"}) at spawner $spawnerPos.")
        }
    }
}
