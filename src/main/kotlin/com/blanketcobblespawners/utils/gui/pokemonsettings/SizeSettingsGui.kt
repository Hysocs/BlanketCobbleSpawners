// File: SizeSettingsGui.kt
package com.blanketcobblespawners.utils.gui.pokemonsettings

import com.blanketcobblespawners.utils.ConfigManager
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
import kotlin.math.roundToInt

object SizeSettingsGui {
    private val logger = LoggerFactory.getLogger(SizeSettingsGui::class.java)

    /**
     * Opens the Size Editing GUI for a specific Pokémon and form.
     *
     * @param player The player opening the GUI.
     * @param spawnerPos The position of the spawner.
     * @param pokemonName The name of the Pokémon.
     * @param formName The form of the Pokémon, if any.
     */
    fun openSizeEditorGui(
        player: ServerPlayerEntity,
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?
    ) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (selectedEntry == null) {
            player.sendMessage(
                Text.literal("Pokémon '$pokemonName' with form '${formName ?: "Standard"}' not found in spawner."),
                false
            )
            logger.warn("Pokémon '$pokemonName' with form '${formName ?: "Standard"}' not found in spawner at $spawnerPos.")
            return
        }

        val layout = generateSizeEditorLayout(selectedEntry)

        spawnerGuisOpen[spawnerPos] = player

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedSlot = context.slotIndex
            val clickType = context.clickType

            when (clickedSlot) {
                19 -> { // Toggle Allow Custom Size
                    handleToggleAllowCustomSize(
                        spawnerPos,
                        pokemonName,
                        formName,
                        player,
                        selectedEntry
                    )
                }
                23 -> { // Minimum Size Adjuster
                    if (selectedEntry.sizeSettings.allowCustomSize) {
                        handleSizeAdjustment(
                            spawnerPos,
                            pokemonName,
                            formName,
                            player,
                            selectedEntry,
                            isMinSize = true,
                            increase = clickType == ClickType.RIGHT
                        )
                    } else {
                        player.sendMessage(Text.literal("Custom sizes are disabled for this Pokémon."), false)
                    }
                }
                25 -> { // Maximum Size Adjuster
                    if (selectedEntry.sizeSettings.allowCustomSize) {
                        handleSizeAdjustment(
                            spawnerPos,
                            pokemonName,
                            formName,
                            player,
                            selectedEntry,
                            isMinSize = false,
                            increase = clickType == ClickType.RIGHT
                        )
                    } else {
                        player.sendMessage(Text.literal("Custom sizes are disabled for this Pokémon."), false)
                    }
                }
                49 -> { // Back Button
                    CustomGui.closeGui(player)
                    player.sendMessage(Text.literal("Returning to Edit Pokémon menu."), false)
                    GuiManager.openPokemonEditSubGui(player, spawnerPos, pokemonName, formName)
                }
                else -> {
                    // Ignore other slots
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(
                Text.literal("Size Settings GUI closed for $pokemonName (${selectedEntry.formName ?: "Standard"})"),
                false
            )
        }

        CustomGui.openGui(
            player,
            "Edit Size Settings for $pokemonName (${selectedEntry.formName ?: "Standard"})",
            layout,
            onInteract,
            onClose
        )
    }

    /**
     * Generates the layout for the Size Editing GUI.
     *
     * @param selectedEntry The selected Pokémon spawn entry.
     * @return A list of ItemStacks representing the GUI layout.
     */
    private fun generateSizeEditorLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        // Toggle Allow Custom Size at slot 19
        layout[19] = createToggleAllowCustomSizeButton(
            allowCustomSize = selectedEntry.sizeSettings.allowCustomSize
        )

        // Minimum Size Adjuster at slot 23
        layout[23] = createSizeAdjusterButton(
            label = "Min Size",
            currentSize = selectedEntry.sizeSettings.minSize,
            isMinSize = true,
            allowCustomSize = selectedEntry.sizeSettings.allowCustomSize
        )

        // Maximum Size Adjuster at slot 25
        layout[25] = createSizeAdjusterButton(
            label = "Max Size",
            currentSize = selectedEntry.sizeSettings.maxSize,
            isMinSize = false,
            allowCustomSize = selectedEntry.sizeSettings.allowCustomSize
        )

        // Back Button at slot 49
        layout[49] = createBackButton()

        // Fill the rest with gray stained glass panes
        for (i in 0 until 54) {
            if (i !in listOf(19, 23, 25, 49)) {
                layout[i] = createFillerPane()
            }
        }

        return layout
    }

    /**
     * Creates a toggle button for allowing/disallowing custom size settings.
     *
     * @param allowCustomSize Current state of allowCustomSize.
     * @return The configured ItemStack.
     */
    private fun createToggleAllowCustomSizeButton(allowCustomSize: Boolean): ItemStack {
        val displayName = if (allowCustomSize) "Disable Custom Sizes" else "Enable Custom Sizes"

        // Define the status line based on the current state
        val statusLine = if (allowCustomSize) {
            "§aStatus: ENABLED"  // Green color for enabled
        } else {
            "§7Status: DISABLED" // Gray color for disabled
        }

        return ItemStack(if (allowCustomSize) Items.RED_WOOL else Items.GREEN_WOOL).apply {
            setCustomName(Text.literal(displayName).styled {
                it.withColor(
                    if (allowCustomSize) net.minecraft.util.Formatting.RED else net.minecraft.util.Formatting.GREEN
                ).withBold(true)
            })
            // Set the lore with both the click prompt and the status line
            CustomGui.setItemLore(
                this,
                listOf(
                    "§7Click to ${if (allowCustomSize) "disable" else "enable"} custom size settings.",
                    statusLine
                )
            )
        }
    }

    /**
     * Creates a size adjuster button (either min or max).
     *
     * @param label The label for the size type.
     * @param currentSize The current size value.
     * @param isMinSize Flag indicating if it's for min size.
     * @param allowCustomSize Flag indicating if custom sizes are allowed.
     * @return The configured ItemStack.
     */
    private fun createSizeAdjusterButton(label: String, currentSize: Float, isMinSize: Boolean, allowCustomSize: Boolean): ItemStack {
        val displayName = "$label: %.1f".format(currentSize)

        return ItemStack(Items.PAPER).apply {
            setCustomName(Text.literal(displayName).styled {
                it.withColor(
                    if (isMinSize) net.minecraft.util.Formatting.GREEN else net.minecraft.util.Formatting.BLUE
                ).withBold(true)
            })
            val lore = mutableListOf<String>(
                "§7Left-click to decrease by 0.1",
                "§7Right-click to increase by 0.1"
            )
            if (!allowCustomSize) {
                lore.add("§cCustom sizes are disabled.")
            }
            CustomGui.setItemLore(this, lore)
        }
    }

    /**
     * Creates the Back button.
     *
     * @return The configured ItemStack.
     */
    private fun createBackButton(): ItemStack {
        return ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back").styled { it.withColor(net.minecraft.util.Formatting.BLUE).withBold(true) })
            // Set the lore to indicate returning to the previous menu
            CustomGui.setItemLore(this, listOf("§7Click to return to the previous menu."))
        }
    }

    /**
     * Creates a filler pane to fill unused slots.
     *
     * @return The configured ItemStack.
     */
    private fun createFillerPane(): ItemStack {
        return ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
            setCustomName(Text.literal(" "))
        }
    }

    /**
     * Rounds a Float to one decimal place.
     *
     * @param value The Float value to round.
     * @return The rounded Float value.
     */
    private fun roundToOneDecimal(value: Float): Float {
        return (value * 10).roundToInt() / 10f
    }

    /**
     * Handles toggling the allowCustomSize flag.
     *
     * @param spawnerPos The position of the spawner.
     * @param pokemonName The name of the Pokémon.
     * @param formName The form of the Pokémon, if any.
     * @param player The player interacting with the GUI.
     * @param selectedEntry The selected Pokémon spawn entry.
     */
    private fun handleToggleAllowCustomSize(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        player: ServerPlayerEntity,
        selectedEntry: PokemonSpawnEntry
    ) {
        // Toggle the allowCustomSize flag
        selectedEntry.sizeSettings.allowCustomSize = !selectedEntry.sizeSettings.allowCustomSize

        // Save the updated configuration
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { entry ->
            entry.sizeSettings = selectedEntry.sizeSettings
        }

        // Update the GUI to reflect the change
        val screenHandler = player.currentScreenHandler
        if (screenHandler.slots.size > 19) {
            screenHandler.slots[19].stack = createToggleAllowCustomSizeButton(selectedEntry.sizeSettings.allowCustomSize)
        }

        screenHandler.sendContentUpdates()

        // Notify the player
        val status = if (selectedEntry.sizeSettings.allowCustomSize) "enabled" else "disabled"
        player.sendMessage(
            Text.literal("Custom size settings ${status} for $pokemonName."),
            false
        )
    }

    /**
     * Handles size adjustment logic.
     *
     * @param spawnerPos The position of the spawner.
     * @param pokemonName The name of the Pokémon.
     * @param formName The form of the Pokémon, if any.
     * @param player The player interacting with the GUI.
     * @param selectedEntry The selected Pokémon spawn entry.
     * @param isMinSize Flag indicating if adjusting min size.
     * @param increase Flag indicating if increasing or decreasing.
     */
    private fun handleSizeAdjustment(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        player: ServerPlayerEntity,
        selectedEntry: PokemonSpawnEntry,
        isMinSize: Boolean,
        increase: Boolean
    ) {
        val adjustmentValue = 0.1f
        val newSize = if (increase) {
            if (isMinSize) selectedEntry.sizeSettings.minSize + adjustmentValue else selectedEntry.sizeSettings.maxSize + adjustmentValue
        } else {
            if (isMinSize) selectedEntry.sizeSettings.minSize - adjustmentValue else selectedEntry.sizeSettings.maxSize - adjustmentValue
        }

        // Determine bounds
        val minBound = 0.5f
        val maxBound = if (isMinSize) selectedEntry.sizeSettings.maxSize else 3.0f

        val adjustedSize = newSize.coerceIn(minBound, maxBound)
        val roundedSize = roundToOneDecimal(adjustedSize)

        // Check if adjustment is necessary (i.e., no change after rounding)
        if (roundedSize == (if (isMinSize) selectedEntry.sizeSettings.minSize else selectedEntry.sizeSettings.maxSize)) {
            player.sendMessage(
                Text.literal("Cannot adjust ${if (isMinSize) "minimum" else "maximum"} size beyond allowed limits."),
                false
            )
            return
        }

        // Update the size in the config
        ConfigManager.updatePokemonSpawnEntry(spawnerPos, pokemonName, formName) { entry ->
            if (isMinSize) {
                entry.sizeSettings.minSize = roundedSize
            } else {
                entry.sizeSettings.maxSize = roundedSize
            }
        } ?: run {
            player.sendMessage(Text.literal("Failed to adjust size."), false)
            return
        }

        // Retrieve updated entry
        val updatedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName, formName)
        if (updatedEntry != null) {
            // Update the size display in the GUI
            val screenHandler = player.currentScreenHandler
            val targetSlot = if (isMinSize) 23 else 25
            if (targetSlot < screenHandler.slots.size) {
                screenHandler.slots[targetSlot].stack = createSizeAdjusterButton(
                    label = if (isMinSize) "Min Size" else "Max Size",
                    currentSize = if (isMinSize) updatedEntry.sizeSettings.minSize else updatedEntry.sizeSettings.maxSize,
                    isMinSize = isMinSize,
                    allowCustomSize = updatedEntry.sizeSettings.allowCustomSize
                )
            }

            screenHandler.sendContentUpdates()

            logger.info(
                "Adjusted ${if (isMinSize) "min" else "max"} size for $pokemonName (${updatedEntry.formName ?: "Standard"}) at spawner $spawnerPos to ${
                    if (isMinSize) updatedEntry.sizeSettings.minSize else updatedEntry.sizeSettings.maxSize
                }."
            )

            // Notify the player
            player.sendMessage(
                Text.literal(
                    "Set ${if (isMinSize) "minimum" else "maximum"} size to ${
                        if (isMinSize) updatedEntry.sizeSettings.minSize else updatedEntry.sizeSettings.maxSize
                    } for $pokemonName."
                ),
                false
            )
        }
    }
}
