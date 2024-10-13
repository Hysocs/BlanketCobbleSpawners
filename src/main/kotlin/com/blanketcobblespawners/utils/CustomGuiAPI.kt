package com.blanketcobblespawners.utils

import io.netty.buffer.Unpooled
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.ItemStack
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
    val PACKET_ID = Identifier("blanketcobblespawners", "update_slot")

    // Open the custom GUI for a player
    fun openGui(
        player: ServerPlayerEntity,
        title: String,
        layout: List<ItemStack>,
        onInteract: (InteractionContext) -> Unit,
        onClose: (Inventory) -> Unit
    ) {
        val factory = SimpleNamedScreenHandlerFactory({ syncId, inv, _ ->
            val screenHandler = CustomScreenHandler(syncId, inv, layout, onInteract, onClose)
            screenHandler
        }, Text.literal(title))
        player.openHandledScreen(factory)
    }

    // Refresh the GUI and send packet updates to the client
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

    // Close the GUI
    fun closeGui(player: ServerPlayerEntity) {
        player.closeHandledScreen()  // This will close the currently open GUI for the player
    }

    // Send a packet to update a specific slot in the GUI
    private fun sendGuiSlotUpdatePacket(player: ServerPlayerEntity, slotIndex: Int, itemStack: ItemStack) {
        val buf = PacketByteBuf(Unpooled.buffer())
        buf.writeInt(slotIndex)
        buf.writeItemStack(itemStack)
        val packet = CustomPayloadS2CPacket(PACKET_ID, buf)
        player.networkHandler.sendPacket(packet)
    }

    // Handle receiving the GUI slot update packet on the client side
    fun handleGuiSlotUpdatePacket(buf: PacketByteBuf, player: ServerPlayerEntity) {
        val slotIndex = buf.readInt()
        val itemStack = buf.readItemStack()

        val screenHandler = player.currentScreenHandler as? CustomScreenHandler
        screenHandler?.updateSlot(slotIndex, itemStack)
    }
}

// Custom ScreenHandler for managing the GUI
class CustomScreenHandler(
    syncId: Int,
    playerInventory: PlayerInventory,
    layout: List<ItemStack>,
    private val onInteract: (InteractionContext) -> Unit,
    private val onClose: (Inventory) -> Unit
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
            // Set honey and honeycomb slots as interactive
            val isInteractive = slotIndex in listOf(37, 39, 41, 43, 46, 48, 50, 52)
            addSlot(InteractiveSlot(guiInventory, slotIndex, isInteractive))
        }

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
            onInteract(context)
            return
        }
        super.onSlotClick(slotIndex, button, actionType, player)
    }

    override fun quickMove(player: PlayerEntity, index: Int): ItemStack {
        return ItemStack.EMPTY
    }

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        onClose(guiInventory)
    }

    // Update the entire inventory
    fun updateInventory(newLayout: List<ItemStack>) {
        newLayout.forEachIndexed { index, itemStack ->
            guiInventory.setStack(index, itemStack)
        }
        sendContentUpdates() // Ensure the updates are sent to the client
    }

    // Update a specific slot in the GUI
    fun updateSlot(slotIndex: Int, itemStack: ItemStack) {
        guiInventory.setStack(slotIndex, itemStack)
        sendContentUpdates()  // Ensure the client gets the update
    }
}

// Custom slot that allows interaction for specific slots only
class InteractiveSlot(inventory: Inventory, index: Int, private val isInteractive: Boolean) : Slot(inventory, index, 0, 0) {
    override fun canInsert(stack: ItemStack): Boolean {
        return isInteractive // Allow inserting items only in interactive slots
    }

    override fun canTakeItems(player: PlayerEntity): Boolean {
        return isInteractive // Allow taking items only in interactive slots
    }
}
