package com.blanketcobblespawners.utils.Gui

import com.blanketcobblespawners.utils.*
import com.cobblemon.mod.common.CobblemonItems
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.enchantment.Enchantments
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.math.BlockPos
import org.joml.Vector4f
import org.slf4j.LoggerFactory

enum class SortMethod {
    ALPHABETICAL,
    TYPE,
    RANDOM,
    SELECTED
}

object GuiManager {
    private val logger = LoggerFactory.getLogger(GuiManager::class.java)
    private var sortMethod = SortMethod.ALPHABETICAL

    // Tracks the current page per player
    private val playerPages: MutableMap<ServerPlayerEntity, Int> = mutableMapOf()

    val spawnerGuisOpen: MutableMap<BlockPos, ServerPlayerEntity> = mutableMapOf()

    fun isSpawnerGuiOpen(spawnerPos: BlockPos): Boolean {
        return spawnerGuisOpen.containsKey(spawnerPos)
    }

    fun openSpawnerGui(player: ServerPlayerEntity, spawnerPos: BlockPos, page: Int = 0) {
        val currentSpawnerData = ConfigManager.spawners[spawnerPos] ?: run {
            player.sendMessage(Text.literal("Spawner data not found"), false)
            return
        }

        spawnerGuisOpen[spawnerPos] = player
        playerPages[player] = page // Track the page for the player

        val layout = generatePokemonItemsForGui(currentSpawnerData.selectedPokemon, page)

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedItem = context.clickedStack
            val clickedItemName = clickedItem.name?.string ?: ""
            val currentPage = playerPages[player] ?: 0

            when (clickedItem.item) {
                Items.COMPASS -> {
                    sortMethod = getNextSortMethod(sortMethod)
                    ConfigManager.saveSpawnerData()
                    refreshGuiItems(player, currentSpawnerData.selectedPokemon, currentPage)
                    player.sendMessage(Text.literal("Sort method changed to ${sortMethod.name}"), false)
                }
                Items.ARROW -> {
                    when (clickedItemName) {
                        "Next" -> {
                            val newPage = currentPage + 1
                            if (newPage * 45 < getSortedSpeciesList(currentSpawnerData.selectedPokemon).size) {
                                playerPages[player] = newPage // Update player's current page
                                refreshGuiItems(player, currentSpawnerData.selectedPokemon, newPage)
                            } else {
                                player.sendMessage(Text.literal("No more pages."), false)
                            }
                        }
                        "Previous" -> {
                            if (currentPage > 0) {
                                val newPage = currentPage - 1
                                playerPages[player] = newPage // Update player's current page
                                refreshGuiItems(player, currentSpawnerData.selectedPokemon, newPage)
                            } else {
                                player.sendMessage(Text.literal("Already on the first page."), false)
                            }
                        }
                    }
                }
                is PokemonItem -> {
                    val plainPokemonName = stripFormatting(clickedItemName)
                    val existingEntry = currentSpawnerData.selectedPokemon.find {
                        it.pokemonName.equals(plainPokemonName, ignoreCase = true)
                    }

                    when (context.clickType) {
                        ClickType.LEFT -> {
                            if (existingEntry == null) {
                                val newEntry = PokemonSpawnEntry(
                                    pokemonName = plainPokemonName,
                                    spawnChance = 50.0,
                                    shinyChance = 0.0,
                                    minLevel = 1,
                                    maxLevel = 100,
                                    captureSettings = CaptureSettings(
                                        isCatchable = true,
                                        restrictCaptureToLimitedBalls = false,
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
                                if (ConfigManager.addPokemonSpawnEntry(spawnerPos, newEntry)) {
                                    player.sendMessage(Text.literal("Added $plainPokemonName to the spawner."), false)
                                }
                            } else {
                                if (ConfigManager.removePokemonSpawnEntry(spawnerPos, plainPokemonName)) {
                                    player.sendMessage(Text.literal("Removed $plainPokemonName from the spawner."), false)
                                }
                            }
                            refreshGuiItems(player, currentSpawnerData.selectedPokemon, currentPage)
                        }

                        ClickType.RIGHT -> {
                            if (existingEntry != null) {
                                closeMainGuiAndOpenSubGui(player, spawnerPos, plainPokemonName)
                            }
                        }
                    }
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            playerPages.remove(player)
            ConfigManager.saveSpawnerData()
            player.sendMessage(Text.literal("Spawner data saved and GUI closed"), false)
        }

        val guiTitle = "Select Pokémon for ${currentSpawnerData.spawnerName}"

        CustomGui.openGui(
            player,
            guiTitle,
            layout,
            onInteract,
            onClose
        )
    }

    private fun closeMainGuiAndOpenSubGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String) {
        CustomGui.closeGui(player)
        openPokemonEditSubGui(player, spawnerPos, pokemonName)
    }

    fun openPokemonEditSubGui(player: ServerPlayerEntity, spawnerPos: BlockPos, pokemonName: String) {
        val selectedEntry = ConfigManager.getPokemonSpawnEntry(spawnerPos, pokemonName)
        if (selectedEntry == null) {
            player.sendMessage(Text.literal("Pokemon '$pokemonName' not found in spawner."), false)
            return
        }

        spawnerGuisOpen[spawnerPos] = player

        val layout = generateSubGuiLayout(selectedEntry)

        val onInteract: (InteractionContext) -> Unit = { context ->
            val clickedItem = context.clickedStack
            val clickedItemName = clickedItem.name?.string ?: ""

            when (clickedItem.item) {
                CobblemonItems.DESTINY_KNOT -> {
                    CustomGui.closeGui(player)
                    IVEditorGui.openIVEditorGui(player, spawnerPos, pokemonName)
                }
                CobblemonItems.HEAVY_DUTY_BOOTS -> {
                    CustomGui.closeGui(player)
                    EVEditorGui.openEVEditorGui(player, spawnerPos, pokemonName)
                }
                CobblemonItems.FAIRY_FEATHER -> {
                    CustomGui.closeGui(player)
                    SpawnShinyEditorGui.openSpawnShinyEditorGui(player, spawnerPos, pokemonName)
                }
                CobblemonItems.RARE_CANDY -> {
                    CustomGui.closeGui(player)
                    OtherEditableGui.openOtherEditableGui(player, spawnerPos, pokemonName)
                }
                Items.ARROW -> {
                    player.sendMessage(Text.literal("Returning to Pokémon List"), false)
                    CustomGui.closeGui(player)
                    openSpawnerGui(player, spawnerPos, playerPages[player] ?: 0)
                }
            }
        }

        val onClose: (Inventory) -> Unit = {
            spawnerGuisOpen.remove(spawnerPos)
            player.sendMessage(Text.literal("Sub GUI closed for ${selectedEntry.pokemonName}"), false)
        }

        val subGuiTitle = "Edit Pokémon: ${selectedEntry.pokemonName}"

        CustomGui.openGui(
            player,
            subGuiTitle,
            layout,
            onInteract,
            onClose
        )
    }

    private fun generateSubGuiLayout(selectedEntry: PokemonSpawnEntry): List<ItemStack> {
        val layout = MutableList(54) { ItemStack.EMPTY }

        for (i in layout.indices) {
            if (i !in listOf(11, 13, 15, 31, 49)) {
                layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                    setCustomName(Text.literal(" "))
                }
            }
        }

        layout[11] = ItemStack(CobblemonItems.DESTINY_KNOT).apply {
            setCustomName(Text.literal("Edit IVs"))
            setItemLore(this, listOf("§7Adjust individual values", "§7for each stat (HP, Attack, etc.)"))
        }

        layout[13] = ItemStack(CobblemonItems.HEAVY_DUTY_BOOTS).apply {
            setCustomName(Text.literal("Edit EVs"))
            setItemLore(this, listOf("§7Adjust effort values", "§7earned through battles"))
        }

        layout[15] = ItemStack(CobblemonItems.FAIRY_FEATHER).apply {
            setCustomName(Text.literal("Edit Spawn/Shiny Chances"))
            setItemLore(this, listOf("§7Modify the spawn and", "§7shiny encounter chances"))
        }

        layout[31] = ItemStack(CobblemonItems.RARE_CANDY).apply {
            setCustomName(Text.literal("Edit Other Stats"))
            setItemLore(this, listOf("§7Change level, catchability,", "§7and other miscellaneous stats"))
        }

        layout[49] = ItemStack(Items.ARROW).apply {
            setCustomName(Text.literal("Back"))
        }

        return layout
    }

    private fun refreshGuiItems(player: ServerPlayerEntity, selectedPokemon: List<PokemonSpawnEntry>, page: Int) {
        val layout = generatePokemonItemsForGui(selectedPokemon, page)

        val screenHandler = player.currentScreenHandler
        layout.forEachIndexed { index, itemStack ->
            if (index < screenHandler.slots.size) {
                screenHandler.slots[index].stack = itemStack
            }
        }

        screenHandler.sendContentUpdates()
    }

    private fun generatePokemonItemsForGui(
        selectedPokemon: List<PokemonSpawnEntry>,
        page: Int
    ): List<ItemStack> {
        val totalSlots = 54
        val layout = MutableList(totalSlots) { ItemStack.EMPTY }
        val pageSize = 45

        val speciesList = getSortedSpeciesList(selectedPokemon)

        val start = page * pageSize
        val end = minOf(start + pageSize, speciesList.size)

        for (i in start until end) {
            val species = speciesList[i]
            val isSelected = selectedPokemon.any { it.pokemonName.equals(species.name, ignoreCase = true) }

            val itemStack = if (isSelected) {
                createSelectedPokemonItem(species, selectedPokemon)
            } else {
                createUnselectedPokemonItem(species)
            }

            val slotIndex = i - start
            if (slotIndex in 0 until pageSize) {
                layout[slotIndex] = itemStack
            }
        }

        val fillerSlots = listOf(45, 46, 47, 48, 50, 51, 52)
        for (i in fillerSlots) {
            layout[i] = ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                setCustomName(Text.literal(" "))
            }
        }

        layout[49] = ItemStack(Items.COMPASS).apply {
            setCustomName(Text.literal("Sort Method"))
            setItemLore(this, listOf("§9§lCurrent Sort: §r§e${sortMethod.name.lowercase().replaceFirstChar { it.uppercase() }}", "§7Click to change sorting method"))
        }

        if (page > 0) {
            layout[45] = ItemStack(Items.ARROW).apply {
                setCustomName(Text.literal("Previous"))
            }
        }

        if (end < speciesList.size) {
            layout[53] = ItemStack(Items.ARROW).apply {
                setCustomName(Text.literal("Next"))
            }
        }

        return layout
    }

    private fun createSelectedPokemonItem(
        species: Species,
        selectedPokemon: List<PokemonSpawnEntry>
    ): ItemStack {
        val selectedItem = PokemonItem.from(species, tint = Vector4f(1.0f, 1.0f, 1.0f, 1.0f)) // Softer green tint for selection
        addEnchantmentGlint(selectedItem)

        val entry = selectedPokemon.find { it.pokemonName.equals(species.name, ignoreCase = true) }
        val chance = entry?.spawnChance ?: 50.0
        val shinyChance = entry?.shinyChance ?: 0.0
        val minLevel = entry?.minLevel ?: 1
        val maxLevel = entry?.maxLevel ?: 100
        val spawnTime = entry?.spawnSettings?.spawnTime ?: "ALL"
        val spawnWeather = entry?.spawnSettings?.spawnWeather ?: "ALL"

        val pokemonLore = listOf(
            "§2Type: §a${species.primaryType.name}",
            species.secondaryType?.let { "§2Secondary Type: §a${it.name}" } ?: "",
            "----------------",
            "§6Spawn Chance: §e$chance%",
            "§bShiny Chance: §3%.2f%%".format(shinyChance),
            "§dMin Level: §f$minLevel",
            "§dMax Level: §f$maxLevel",
            "§9Spawn Time: §b$spawnTime",
            "§3Spawn Weather: §b$spawnWeather",
            "----------------",
            "§e§lLeft-click§r to §cDeselect",
            "§e§lRight-click§r to §aEdit stats and properties"
        ).filter { it.isNotEmpty() }

        setItemLore(selectedItem, pokemonLore)
        selectedItem.setCustomName(Text.literal("§f§n${species.name}"))
        return selectedItem
    }

    private fun createUnselectedPokemonItem(species: Species): ItemStack {
        val unselectedItem = PokemonItem.from(species, tint = Vector4f(0.3f, 0.3f, 0.3f, 1f)) // Default white for unselected
        val pokemonLore = listOf(
            "§aType: §f${species.primaryType.name}",
            species.secondaryType?.let { "§aSecondary Type: §f${it.name}" } ?: "",
            "----------------",
            "§e§lLeft-click§r to §aSelect" // Match the style of the deselect action
        ).filter { it.isNotEmpty() }

        setItemLore(unselectedItem, pokemonLore)
        unselectedItem.setCustomName(Text.literal("§f${species.name}"))
        return unselectedItem
    }

    private fun getNextSortMethod(currentSort: SortMethod): SortMethod {
        return when (currentSort) {
            SortMethod.ALPHABETICAL -> SortMethod.TYPE
            SortMethod.TYPE -> SortMethod.RANDOM
            SortMethod.RANDOM -> SortMethod.SELECTED
            SortMethod.SELECTED -> SortMethod.ALPHABETICAL
        }
    }

    private fun addEnchantmentGlint(itemStack: ItemStack) {
        EnchantmentHelper.set(mapOf(Enchantments.UNBREAKING to 1), itemStack)
        itemStack.orCreateNbt.putInt("HideFlags", 1)
    }

    fun setItemLore(itemStack: ItemStack, loreLines: List<String>) {
        val displayNbt = itemStack.orCreateNbt.getCompound("display") ?: NbtCompound()
        val loreList = NbtList()

        loreLines.forEach { line ->
            loreList.add(NbtString.of(Text.Serializer.toJson(Text.literal(line))))
        }

        displayNbt.put("Lore", loreList)
        itemStack.orCreateNbt.put("display", displayNbt)
    }

    fun stripFormatting(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "")
    }

    private fun getSortedSpeciesList(selectedPokemon: List<PokemonSpawnEntry>): List<Species> {
        val showUnimplemented = ConfigManager.configData.globalConfig.showUnimplementedPokemonInGui

        val speciesList = PokemonSpecies.species.filter { species ->
            showUnimplemented || species.implemented
        }

        return when (sortMethod) {
            SortMethod.ALPHABETICAL -> speciesList.sortedBy { it.name }
            SortMethod.TYPE -> speciesList.sortedBy { it.primaryType.name }
            SortMethod.RANDOM -> speciesList.shuffled()
            SortMethod.SELECTED -> {
                // Use the selected Pokémon for the current spawner only
                val selectedSet = selectedPokemon.map { it.pokemonName.lowercase() }.toSet()
                val selectedList = speciesList.filter { selectedSet.contains(it.name.lowercase()) }
                val unselectedList = speciesList.filter { !selectedSet.contains(it.name.lowercase()) }.shuffled()
                selectedList + unselectedList
            }
        }
    }
}
