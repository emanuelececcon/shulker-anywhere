package io.github.emanuelececcon.shulkeranywhere;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * Opens boxes, keeps every open box in step with its view and closes them again.
 *
 * <p>The rules that keep items from being duplicated or lost:
 * <ul>
 *   <li>The box item is the only real copy of the items. Every change in the view is written into it in the
 *       same server call that applied the change (see {@link MenuHook}), so whenever anything else looks at
 *       the player's inventory (a save, a death, a quit, another plugin), box and inventory agree.</li>
 *   <li>Before a click is applied the box must be exactly the item last written, in the same slot; otherwise
 *       the click is refused and the view closed. Nothing is ever written into an item that changed.</li>
 *   <li>Inventories are never opened or closed inside click events; that is done on the next tick.</li>
 * </ul>
 */
final class Sessions {

    static final String USE_PERMISSION = "shulkeranywhere.use";
    private static final String UNAVAILABLE = "Opening shulker boxes without placing them is unavailable right now.";

    private final ShulkerAnywherePlugin plugin;
    private final MenuHook hook;
    private final BoxCheck boxCheck;
    private final Map<UUID, Session> byPlayer = new HashMap<>();
    private boolean hookBroken;
    private int openedCount;
    private int brokenCount;

    /** {@code hook} and {@code boxCheck} are null when the server is not supported: then nothing opens. */
    Sessions(ShulkerAnywherePlugin plugin, MenuHook hook, BoxCheck boxCheck) {
        this.plugin = plugin;
        this.hook = hook;
        this.boxCheck = boxCheck;
        this.hookBroken = hook == null || boxCheck == null;
    }

    Session get(Player player) {
        return byPlayer.get(player.getUniqueId());
    }

    /** Whether the player may open boxes now. When not, right-clicks keep their vanilla meaning. */
    boolean usable(Player player) {
        GameMode mode = player.getGameMode();
        return plugin.settings().enabled() && !hookBroken && !player.isDead()
                && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE)
                && player.hasPermission(USE_PERMISSION);
    }

    /**
     * Opens the box in {@code slot} if the slot still holds exactly {@code snapshot}. Never call this inside an
     * inventory click or drag event: opening a menu there is a known way to duplicate items, so clicks schedule
     * it for the next tick.
     */
    void open(Player player, int slot, ItemStack snapshot) {
        if (!player.isOnline() || !usable(player) || !PinRules.isOpenableSlot(slot)) {
            return;
        }
        Session previous = byPlayer.get(player.getUniqueId());
        if (previous != null && previous.slot == slot) {
            return; // already open
        }
        ItemStack box = player.getInventory().getItem(slot);
        if (!BoxCheck.isShulkerBox(box) || !box.equals(snapshot) || !player.getItemOnCursor().isEmpty()) {
            return;
        }
        Refusal refusal = boxCheck.check(box);
        if (refusal != null) {
            tell(player, refusal.message());
            return;
        }
        if (previous != null) {
            // Saves the other box. Its view stays on screen until the new one replaces it, but with its session
            // closed every click in it is refused.
            finish(previous, "switched to another box");
            box = player.getInventory().getItem(slot);
            if (!BoxCheck.isShulkerBox(box) || !box.equals(snapshot)) {
                closeIfShowing(previous);
                return;
            }
        }

        Session session = new Session(player, slot);
        Inventory view = Bukkit.createInventory(session, InventoryType.SHULKER_BOX, title(box));
        session.view = view;
        view.setContents(Contents.ofBox(box));
        session.written = Contents.copy(view.getContents());
        session.expected = box.clone();
        if (!Contents.same(session.written, Contents.ofBox(box))) {
            plugin.getLogger().severe("A box view could not show the box of " + player.getName() + " faithfully: "
                    + Contents.describe(Contents.ofBox(box)));
            if (previous != null) {
                closeIfShowing(previous);
            }
            return;
        }

        byPlayer.put(session.playerId, session);
        InventoryView opened;
        try {
            opened = player.openInventory(view);
        } catch (RuntimeException e) {
            forget(session);
            closeIfShowing(session);
            if (previous != null) {
                closeIfShowing(previous);
            }
            plugin.getLogger().log(Level.SEVERE, "Could not open the box view for " + player.getName(), e);
            return;
        }
        if (opened == null || player.getOpenInventory().getTopInventory() != view) {
            // Another plugin cancelled the InventoryOpenEvent; the server already closed the previous screen.
            forget(session);
            return;
        }
        try {
            session.attachment = hook.attach(player, view, () -> onMenuChange(session));
        } catch (ReflectiveOperationException | RuntimeException e) {
            forget(session);
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN); // nothing in the view has changed yet
            tell(player, UNAVAILABLE);
            hookFailed("Could not add the listener to a box view", e);
            return;
        }
        // Other plugins ran while the view opened (InventoryOpenEvent, closing the previous screen).
        ItemStack now = player.getInventory().getItem(slot);
        if (now == null || !now.equals(session.expected) || !Contents.same(view.getContents(), session.written)) {
            forget(session);
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            plugin.getLogger().warning("Closed the box view of " + player.getName()
                    + " right away: the box or the view changed while it opened. Nothing was written.");
            return;
        }
        session.state = Session.State.OPEN;
        openedCount++;
        sound(player, Sound.BLOCK_SHULKER_BOX_OPEN);
        if (plugin.settings().logSessions()) {
            plugin.getLogger().info(player.getName() + " opened the shulker box in slot " + slot + " ("
                    + Contents.count(session.written) + " items)");
        }
    }

    /**
     * The menu listener: runs inside the server's broadcastChanges(), right after a click was applied and every
     * tick the view changed. It must never throw into the server, nor open or close anything.
     */
    private void onMenuChange(Session session) {
        session.heardSinceClick = true;
        if (session.state != Session.State.OPEN) {
            return;
        }
        try {
            sync(session, "change in the view");
        } catch (Throwable error) {
            breakSession(session, "an error happened while saving the box: " + error);
            plugin.getLogger().log(Level.SEVERE, "Error while saving a shulker box", error);
        }
    }

    /**
     * Writes the view into the box if they differ. Returns false, and breaks the session, when the slot no
     * longer holds the box exactly as last written; then nothing is written.
     */
    boolean sync(Session session, String cause) {
        if (session.state != Session.State.OPEN) {
            return false;
        }
        ItemStack box = session.player.getInventory().getItem(session.slot);
        if (box == null || !box.equals(session.expected)) {
            breakSession(session, "the box in slot " + session.slot + " was moved or changed by something else ("
                    + cause + ")");
            return false;
        }
        ItemStack[] now = session.view.getContents();
        if (Contents.same(now, session.written)) {
            return true;
        }
        // box mirrors the stack in the player's inventory, so this changes that stack itself.
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(Contents.asList(now)));
        ItemStack after = session.player.getInventory().getItem(session.slot);
        if (after == null || !Contents.same(Contents.ofBox(after), now)) {
            breakSession(session, "the box did not take its new contents (" + cause + ")");
            return false;
        }
        session.expected = after.clone();
        session.written = Contents.copy(now);
        return true;
    }

    /** Before a click or drag in the view is applied: the box must be untouched. Pending changes get saved. */
    boolean allowAction(Session session) {
        return session.state == Session.State.OPEN && sync(session, "before a click");
    }

    /** Called for a click or drag that will be applied, to check afterwards that the listener heard it. */
    void expectChange(Session session) {
        session.beforeClick = Contents.copy(session.view.getContents());
        session.heardSinceClick = false;
        if (!session.checkScheduled && plugin.isEnabled()) {
            session.checkScheduled = true;
            Bukkit.getScheduler().runTask(plugin, () -> checkListener(session));
        }
    }

    private void checkListener(Session session) {
        session.checkScheduled = false;
        if (session.state != Session.State.OPEN || session.heardSinceClick || session.beforeClick == null) {
            return;
        }
        if (!Contents.same(session.view.getContents(), session.beforeClick)) {
            // A click changed the view and the listener said nothing: this server does not work as expected.
            sync(session, "listener check");
            hookFailed("A click changed a box view without the menu listener hearing it", null);
        }
    }

    /** First thing in PlayerDeathEvent: the drops hold the live box, so save into it before they spawn. */
    void beforeDeath(Player player) {
        Session session = get(player);
        if (session != null && session.state == Session.State.OPEN) {
            sync(session, "death");
        }
    }

    /** The death went through: the box is dropped or kept as saved, nothing more may be written. */
    void afterDeath(Player player) {
        Session session = get(player);
        if (session != null && session.state == Session.State.OPEN) {
            session.state = Session.State.CLOSING;
        }
    }

    /** Saves what is pending (normally nothing) and forgets the session. Safe to call more than once. */
    void finish(Session session, String cause) {
        if (session.state == Session.State.CLOSED) {
            return;
        }
        if (session.state == Session.State.OPEN) {
            sync(session, cause);
        }
        boolean clean = session.state == Session.State.OPEN || session.state == Session.State.CLOSING;
        forget(session);
        if (clean) {
            sound(session.player, Sound.BLOCK_SHULKER_BOX_CLOSE);
        }
        if (plugin.settings().logSessions()) {
            plugin.getLogger().info(session.player.getName() + " closed the shulker box in slot " + session.slot
                    + " (" + cause + ", " + Contents.count(session.written) + " items)");
        }
    }

    private void forget(Session session) {
        session.state = Session.State.CLOSED;
        if (hook != null) {
            hook.detach(session.attachment);
        }
        byPlayer.remove(session.playerId, session);
    }

    /** Stops writing into the box and closes the view on the next tick. */
    private void breakSession(Session session, String why) {
        if (session.state == Session.State.BROKEN || session.state == Session.State.CLOSED) {
            return;
        }
        boolean unsaved = session.state == Session.State.OPEN
                && !Contents.same(session.view.getContents(), session.written);
        session.state = Session.State.BROKEN;
        brokenCount++;
        if (unsaved) {
            plugin.getLogger().severe("Closing the box view of " + session.player.getName() + ": " + why
                    + ". The view had changes that are NOT in the box. View: "
                    + Contents.describe(session.view.getContents()) + ". Box: " + Contents.describe(session.written));
        } else {
            plugin.getLogger().warning("Closing the box view of " + session.player.getName() + ": " + why
                    + ". Nothing was lost, every change was already in the box.");
        }
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> closeView(session));
        } else {
            closeView(session);
        }
    }

    /** Closes the view if it is still open (the close event finishes the session) and finishes it anyway. */
    void closeView(Session session) {
        closeIfShowing(session);
        finish(session, "closed by ShulkerAnywhere");
    }

    /** A box view must never stay on screen without its session. Not for use inside click events. */
    private static void closeIfShowing(Session session) {
        Player player = session.player;
        if (player.isOnline() && player.getOpenInventory().getTopInventory() == session.view) {
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        }
    }

    /** Saves and closes every open box, e.g. when the plugin is disabled. Not for use inside click events. */
    void closeAll(String cause) {
        for (Session session : new ArrayList<>(byPlayer.values())) {
            finish(session, cause);
            closeIfShowing(session);
        }
        closeOrphans();
    }

    /** Box views whose session is over; there should be none, the guard refuses every click in them anyway. */
    private void closeOrphans() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Session session
                    && byPlayer.get(player.getUniqueId()) != session) {
                plugin.getLogger().warning("Closing a box view of " + player.getName() + " that had no session");
                player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            }
        }
    }

    /** Once a second: closes views that should not be open any more and checks boxes nobody clicked. */
    void sweep() {
        closeOrphans();
        for (Session session : new ArrayList<>(byPlayer.values())) {
            Player player = session.player;
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != session.view) {
                finish(session, "view no longer open");
                continue;
            }
            switch (session.state) {
                case OPEN -> {
                    if (usable(player)) {
                        sync(session, "periodic check");
                    } else {
                        closeView(session);
                    }
                }
                case CLOSING -> {
                    if (!player.isDead()) {
                        closeView(session); // the death was cancelled after all
                    }
                }
                default -> {
                }
            }
        }
    }

    private void hookFailed(String what, Throwable error) {
        if (!hookBroken) {
            plugin.getLogger().log(Level.SEVERE, what + ". Opening shulker boxes without placing them is off until"
                    + " the next restart; open boxes are saved and closed.", error);
        }
        hookBroken = true;
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> closeAll("menu listener not working"));
        }
    }

    List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("Enabled: " + plugin.settings().enabled() + (hookBroken ? " (unavailable: see the log)" : ""));
        lines.add("Open boxes: " + byPlayer.size());
        long now = System.currentTimeMillis();
        for (Session session : byPlayer.values()) {
            lines.add("  " + session.player.getName() + ": slot " + session.slot + ", " + session.state + ", "
                    + (now - session.openedAt) / 1000 + " s, " + Contents.count(session.written) + " items");
        }
        lines.add("Opened since start: " + openedCount + ", closed because of a problem: " + brokenCount);
        return lines;
    }

    private static Component title(ItemStack box) {
        Component name = box.getData(DataComponentTypes.CUSTOM_NAME);
        return name != null ? name : Component.translatable("container.shulkerBox");
    }

    private void sound(Player player, Sound sound) {
        if (plugin.settings().sounds() && player.isOnline()) {
            player.getWorld().playSound(player.getLocation(), sound, SoundCategory.BLOCKS, 0.5F,
                    ThreadLocalRandom.current().nextFloat() * 0.1F + 0.9F);
        }
    }

    private static void tell(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
