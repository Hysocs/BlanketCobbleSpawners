// File: CustomGuiAPI.kt
package com.blanketcobblespawners.utils

import io.netty.buffer.Unpooled
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.SimpleNamedScreenHandlerFactory
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ClickType
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

data class InteractionContext(
    val slotIndex: Int,
    val clickType: ClickType,
    val button: Int,
    val clickedStack: ItemStack,
    val player: ServerPlayerEntity
)

object CustomGui {
    private val logger = LoggerFactory.getLogger(CustomGui::class.java)

    // Packet ID for slot update packet
    private val PACKET_ID = Identifier("blanketcobblespawners", "update_slot")

    /**
     * Opens a custom GUI for the specified player.
     *
     * @param player The player to open the GUI for.
     * @param title The title of the GUI.
     * @param layout A list of ItemStacks representing the GUI layout.
     * @param onInteract A callback function triggered on item interactions.
     * @param onClose A callback function triggered when the GUI is closed.
     */
    fun openGui(
        player: ServerPlayerEntity,
        title: String,
        layout: List<ItemStack>,
        onInteract: (InteractionContext) -> Unit,
        onClose: (Inventory) -> Unit
    ) {
        val factory = SimpleNamedScreenHandlerFactory({ syncId, inv, _ ->
            CustomScreenHandler(syncId, inv, layout, onInteract, onClose)
        }, Text.literal(title))
        player.openHandledScreen(factory)
    }

    /**
     * Refreshes the GUI layout for the specified player.
     *
     * @param player The player whose GUI is to be refreshed.
     * @param newLayout The new layout of the GUI.
     */
    fun refreshGui(player: ServerPlayerEntity, newLayout: List<ItemStack>) {
        val screenHandler = player.currentScreenHandler as? CustomScreenHandler
        if (screenHandler != null) {
            screenHandler.updateInventory(newLayout)
            newLayout.forEachIndexed { index, itemStack ->
                sendGuiSlotUpdatePacket(player, index, itemStack) // Send the updated slot to the client
            }
        } else {
            logger.warn("Player ${player.name.string} does not have the expected screen handler open.")
        }
    }

    /**
     * Closes the currently open GUI for the specified player.
     *
     * @param player The player whose GUI is to be closed.
     */
    fun closeGui(player: ServerPlayerEntity) {
        player.closeHandledScreen()  // This will close the currently open GUI for the player
    }

    /**
     * Sends a packet to update a specific slot in the GUI.
     *
     * @param player The player to send the packet to.
     * @param slotIndex The index of the slot to update.
     * @param itemStack The new ItemStack to set in the slot.
     */
    private fun sendGuiSlotUpdatePacket(player: ServerPlayerEntity, slotIndex: Int, itemStack: ItemStack) {
        val buf = PacketByteBuf(Unpooled.buffer())
        buf.writeInt(slotIndex)
        buf.writeItemStack(itemStack)
        val packet = CustomPayloadS2CPacket(PACKET_ID, buf)
        player.networkHandler.sendPacket(packet)
    }

    /**
     * Handles receiving the GUI slot update packet on the client side.
     *
     * @param buf The packet buffer containing slot update data.
     * @param player The player who received the packet.
     */
    fun handleGuiSlotUpdatePacket(buf: PacketByteBuf, player: ServerPlayerEntity) {
        val slotIndex = buf.readInt()
        val itemStack = buf.readItemStack()

        val screenHandler = player.currentScreenHandler as? CustomScreenHandler
        screenHandler?.updateSlot(slotIndex, itemStack)
    }

    /**
     * Creates a GUI button using a player head with a custom texture.
     *
     * @param textureName The name identifier for the texture.
     * @param title The display name of the button as a Text object.
     * @param lore The lore lines for the button as a list of Text objects.
     * @param textureValue The Base64-encoded texture value.
     * @return The customized ItemStack representing the button.
     */
    fun createPlayerHeadButton(
        textureName: String,
        title: Text,
        lore: List<Text>,
        textureValue: String
    ): ItemStack {
        return ItemStack(Items.PLAYER_HEAD).apply {
            val nbt = NbtCompound()
            val displayTag = NbtCompound()

            // Set the title
            displayTag.putString("Name", Text.Serializer.toJson(title))

            // Set the lore
            val loreList = NbtList()
            lore.forEach { loreLine ->
                loreList.add(NbtString.of(Text.Serializer.toJson(loreLine)))
            }
            displayTag.put("Lore", loreList)
            nbt.put("display", displayTag)

            // Set the texture
            val skullOwnerTag = NbtCompound()
            skullOwnerTag.putString("Name", textureName)
            val properties = NbtCompound()
            val textures = NbtList()
            val textureNBT = NbtCompound().apply {
                putString("Value", textureValue)
            }
            textures.add(textureNBT)
            properties.put("textures", textures)
            skullOwnerTag.put("Properties", properties)

            nbt.put("SkullOwner", skullOwnerTag)
            this.nbt = nbt
        }
    }

    /**
     * Creates a GUI button using a normal item.
     *
     * @param item The base ItemStack to use for the button.
     * @param displayName The display name of the button.
     * @param lore The lore lines for the button.
     * @return The customized ItemStack representing the button.
     */
    fun createNormalButton(
        item: ItemStack,
        displayName: String,
        lore: List<String>
    ): ItemStack {
        return item.copy().apply {
            setCustomName(Text.literal(displayName))
            setItemLore(this, lore)
        }
    }

    /**
     * Sets the lore on an ItemStack.
     *
     * @param itemStack The ItemStack to set the lore on.
     * @param loreLines The list of lore lines.
     */
    fun setItemLore(itemStack: ItemStack, loreLines: List<String>) {

        val displayNbt = itemStack.orCreateNbt.getCompound("display") ?: NbtCompound()
        val loreList = NbtList()

        loreLines.forEach { line ->
            loreList.add(NbtString.of(Text.Serializer.toJson(Text.literal(line))))
        }

        displayNbt.put("Lore", loreList)
        itemStack.orCreateNbt.put("display", displayNbt)
    }

    /**
     * Strips formatting codes from the provided text.
     *
     * @param text The text to strip formatting from.
     * @return The unformatted text.
     */
    fun stripFormatting(text: String): String {
        return text.replace(Regex("§[0-9a-fk-or]"), "")
    }

    /**
     * Adds an enchantment glint effect to an ItemStack.
     *
     * @param itemStack The ItemStack to add the glint to.
     */
    fun addEnchantmentGlint(itemStack: ItemStack) {
        EnchantmentHelper.set(mapOf(Enchantments.UNBREAKING to 1), itemStack)
        itemStack.orCreateNbt.putInt("HideFlags", 1)
    }
}

/**
 * Custom ScreenHandler for managing the GUI.
 */
class CustomScreenHandler(
    syncId: Int,
    playerInventory: PlayerInventory,
    layout: List<ItemStack>,
    private var onInteract: ((InteractionContext) -> Unit)?,
    private var onClose: ((Inventory) -> Unit)?
) : ScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId) {

    private val guiInventory: Inventory = object : Inventory {
        private val items = Array<ItemStack?>(54) { ItemStack.EMPTY }

        init {
            layout.forEachIndexed { index, itemStack ->
                if (index < size()) items[index] = itemStack
            }
        }

        override fun clear() {
            items.fill(ItemStack.EMPTY)
        }

        override fun size(): Int = items.size

        override fun isEmpty(): Boolean = items.all { it?.isEmpty ?: true }

        override fun getStack(slot: Int): ItemStack = items[slot] ?: ItemStack.EMPTY

        override fun removeStack(slot: Int, amount: Int): ItemStack {
            val stack = getStack(slot)
            return if (stack.count <= amount) {
                removeStack(slot)
            } else {
                val splitStack = stack.split(amount)
                items[slot] = stack
                splitStack
            }
        }

        override fun removeStack(slot: Int): ItemStack {
            val stack = getStack(slot)
            items[slot] = ItemStack.EMPTY
            return stack
        }

        override fun setStack(slot: Int, stack: ItemStack) {
            items[slot] = stack
        }

        override fun markDirty() {}

        override fun canPlayerUse(player: PlayerEntity): Boolean = true
    }

    init {
        for (slotIndex in 0 until guiInventory.size()) {
            // Define which slots are interactive (customize as needed)
            val isInteractive = slotIndex in listOf(37, 39, 41, 43, 46, 48, 50, 52)
            addSlot(InteractiveSlot(guiInventory, slotIndex, isInteractive))
        }

        // Add player inventory slots
        for (i in 0..2) {
            for (j in 0..8) {
                val index = j + i * 9 + 9
                addSlot(Slot(playerInventory, index, 8 + j * 18, 84 + i * 18))
            }
        }
        for (k in 0..8) {
            addSlot(Slot(playerInventory, k, 8 + k * 18, 142))
        }
    }

    override fun canUse(player: PlayerEntity): Boolean {
        return true
    }

    override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
        val isCustomGuiSlot = slotIndex in 0 until guiInventory.size()
        if (isCustomGuiSlot && player is ServerPlayerEntity) {
            val stack = guiInventory.getStack(slotIndex)
            val clickType = if (button == 0) ClickType.LEFT else ClickType.RIGHT
            val context = InteractionContext(slotIndex, clickType, button, stack, player)
            onInteract?.invoke(context)
            return
        }
        super.onSlotClick(slotIndex, button, actionType, player)
    }

    override fun quickMove(player: PlayerEntity, index: Int): ItemStack {
        return ItemStack.EMPTY
    }

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        onClose?.invoke(guiInventory)
        // Clear references to help garbage collection
        onInteract = null
        onClose = null
    }

    /**
     * Updates the entire inventory layout.
     *
     * @param newLayout The new layout to set.
     */
    fun updateInventory(newLayout: List<ItemStack>) {
        newLayout.forEachIndexed { index, itemStack ->
            guiInventory.setStack(index, itemStack)
        }
        sendContentUpdates() // Ensure the updates are sent to the client
    }

    /**
     * Updates a specific slot in the GUI.
     *
     * @param slotIndex The index of the slot to update.
     * @param itemStack The new ItemStack to set.
     */
    fun updateSlot(slotIndex: Int, itemStack: ItemStack) {
        guiInventory.setStack(slotIndex, itemStack)
        sendContentUpdates()  // Ensure the client gets the update
    }
}

/**
 * Custom slot that allows interaction for specific slots only.
 */
class InteractiveSlot(inventory: Inventory, index: Int, private val isInteractive: Boolean) : Slot(inventory, index, 0, 0) {
    override fun canInsert(stack: ItemStack): Boolean {
        return isInteractive // Allow inserting items only in interactive slots
    }

    override fun canTakeItems(player: PlayerEntity): Boolean {
        return isInteractive // Allow taking items only in interactive slots
    }
}
