package io.github.emanuelececcon.shulkeranywhere;

import io.papermc.paper.event.player.PlayerPickItemEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;

/** Protects open boxes: refuses clicks that could move the box, saves on death and quit, closes views. */
final class GuardListener implements Listener {

    private final ShulkerAnywherePlugin plugin;
    private final Sessions sessions;

    GuardListener(ShulkerAnywherePlugin plugin, Sessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /** The session of a box view, found through the view itself so that a stale view is recognised too. */
    private static Session sessionOf(InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof Session session ? session : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Session session = sessionOf(event.getView());
        if (session != null && refuses(session, event)) {
            event.setCancelled(true);
        }
    }

    /** Last word: a plugin that un-cancelled a refused click is overruled; allowed clicks get checked later. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickApplied(InventoryClickEvent event) {
        Session session = sessionOf(event.getView());
        if (session == null) {
            return;
        }
        if (!event.isCancelled() && refuses(session, event)) {
            event.setCancelled(true);
            plugin.getLogger().warning("Another plugin allowed a click that ShulkerAnywhere refused; refused it again");
        }
        if (!event.isCancelled()) {
            sessions.expectChange(session);
        }
    }

    private boolean refuses(Session session, InventoryClickEvent event) {
        if (session.player != event.getWhoClicked() || PinRules.alwaysRefused(event.getClick())) {
            return true;
        }
        int clickedPlayerSlot = event.getClickedInventory() instanceof PlayerInventory ? event.getSlot() : -1;
        if (PinRules.touchesBox(session.slot, clickedPlayerSlot, event.getClick(), event.getHotbarButton())) {
            return true;
        }
        // A double click gathers matching items from every slot onto the cursor; shulker boxes don't stack, but a
        // data pack could make them.
        if (event.getClick() == ClickType.DOUBLE_CLICK && BoxCheck.isShulkerBox(event.getCursor())) {
            return true;
        }
        return !sessions.allowAction(session);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Session session = sessionOf(event.getView());
        if (session != null && refuses(session, event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragApplied(InventoryDragEvent event) {
        Session session = sessionOf(event.getView());
        if (session == null) {
            return;
        }
        if (!event.isCancelled() && refuses(session, event)) {
            event.setCancelled(true);
            plugin.getLogger().warning("Another plugin allowed a drag that ShulkerAnywhere refused; refused it again");
        }
        if (!event.isCancelled()) {
            sessions.expectChange(session);
        }
    }

    private boolean refuses(Session session, InventoryDragEvent event) {
        if (session.player != event.getWhoClicked()) {
            return true;
        }
        InventoryView view = event.getView();
        for (int rawSlot : event.getRawSlots()) {
            if (view.getInventory(rawSlot) instanceof PlayerInventory && view.convertSlot(rawSlot) == session.slot) {
                return true;
            }
        }
        return !sessions.allowAction(session);
    }

    /** Runs before anything else hears about the close, so they already see the final box. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClose(InventoryCloseEvent event) {
        Session session = sessionOf(event.getView());
        if (session != null) {
            sessions.finish(session, "closed (" + event.getReason() + ")");
        }
    }

    /** The drops are the live items and spawn right after this event: save the box into them first. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        sessions.beforeDeath(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathDone(PlayerDeathEvent event) {
        sessions.afterDeath(event.getPlayer());
    }

    /** The server closes the view (and saves) before PlayerQuitEvent; this only catches what slipped through. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer());
        if (session != null) {
            sessions.finish(session, "quit");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode mode = event.getNewGameMode();
        Session session = sessions.get(event.getPlayer());
        if (session != null && mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            Bukkit.getScheduler().runTask(plugin, () -> sessions.closeView(session));
        }
    }

    // A player looking at a screen can't place blocks, use items, touch entities, swap hands or pick blocks; only
    // a modified client sends those packets with a box open. Refusing them keeps the box where it is.

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        refuseWhileOpen(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (sessions.get(event.getPlayer()) != null) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        refuseWhileOpen(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        refuseWhileOpen(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        refuseWhileOpen(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickItem(PlayerPickItemEvent event) {
        refuseWhileOpen(event.getPlayer(), event);
    }

    private void refuseWhileOpen(HumanEntity player, Cancellable event) {
        if (player instanceof Player online && sessions.get(online) != null) {
            event.setCancelled(true);
        }
    }
}
