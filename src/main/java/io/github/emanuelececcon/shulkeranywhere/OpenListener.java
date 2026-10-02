package io.github.emanuelececcon.shulkeranywhere;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Right-click on a box in the inventory screen, or in the air with a box in hand, opens it. */
final class OpenListener implements Listener {

    private final ShulkerAnywherePlugin plugin;
    private final Sessions sessions;

    OpenListener(ShulkerAnywherePlugin plugin, Sessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /**
     * Right-click with an empty cursor on a box in the player's own inventory, in any screen. The click is
     * cancelled and the box opens on the next tick: opening a menu inside a click event can duplicate items.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getClick() != ClickType.RIGHT || !plugin.settings().openFromInventory()
                || !(event.getWhoClicked() instanceof Player player)
                || !(event.getClickedInventory() instanceof PlayerInventory clicked) || clicked.getHolder() != player) {
            return;
        }
        int slot = event.getSlot();
        ItemStack box = event.getCurrentItem();
        if (!PinRules.isOpenableSlot(slot) || !BoxCheck.isShulkerBox(box) || !event.getCursor().isEmpty()
                || !sessions.usable(player)) {
            return; // the click keeps its vanilla meaning
        }
        event.setCancelled(true);
        ItemStack snapshot = box.clone();
        InventoryView screen = event.getView();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory() == screen) {
                sessions.open(player, slot, snapshot);
            }
        });
    }

    /**
     * Right-click in the air with a box in the main hand or off hand. Bukkit marks air clicks as cancelled
     * (there is no block to use), so cancelled events must be heard; a plugin that blocks using the item in
     * hand still wins.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.useItemInHand() == Event.Result.DENY
                || !plugin.settings().openFromHand()) {
            return;
        }
        Player player = event.getPlayer();
        int slot;
        if (event.getHand() == EquipmentSlot.HAND) {
            slot = player.getInventory().getHeldItemSlot();
        } else if (event.getHand() == EquipmentSlot.OFF_HAND) {
            slot = PinRules.OFF_HAND;
        } else {
            return;
        }
        ItemStack box = player.getInventory().getItem(slot);
        if (!BoxCheck.isShulkerBox(box) || sessions.get(player) != null || !sessions.usable(player)) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        sessions.open(player, slot, box.clone());
    }
}
