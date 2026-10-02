package io.github.emanuelececcon.shulkeranywhere;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * One box open in a view. The box item in the player's inventory is the only real copy of the items; the
 * view shows them and every change in the view is written back into the box at once.
 */
final class Session implements InventoryHolder {

    enum State {
        /** The view is being opened; nothing is written yet. */
        OPENING,
        /** Changes in the view are written into the box. */
        OPEN,
        /** The player died: the box was saved and dropped or kept, nothing more is written. */
        CLOSING,
        /** The box was moved or changed by something else: nothing more is written, the view gets closed. */
        BROKEN,
        CLOSED
    }

    final Player player;
    final UUID playerId;
    /** Player inventory index of the box: hotbar 0-8, main inventory 9-35 or off hand 40. */
    final int slot;
    final long openedAt = System.currentTimeMillis();

    Inventory view;
    /** Copy of the box item as it was last written or checked; the slot must still hold exactly this. */
    ItemStack expected;
    /** Copy of the view as it was last written into the box. */
    ItemStack[] written;
    State state = State.OPENING;
    MenuHook.Attachment attachment;

    /**
     * The view just before an allowed click, and whether the menu listener reported a change since. If the
     * click changed the view and the listener stayed silent, the listener doesn't work.
     */
    ItemStack[] beforeClick;
    boolean heardSinceClick;
    boolean checkScheduled;

    Session(Player player, int slot) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.slot = slot;
    }

    @Override
    public Inventory getInventory() {
        return view;
    }
}
