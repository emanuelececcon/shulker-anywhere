package io.github.emanuelececcon.shulkeranywhere;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * Hears about every change in an open menu right after the server applies it.
 *
 * <p>Bukkit only has events before a click is applied. The server's menu ({@code AbstractContainerMenu})
 * reports every changed slot to its {@code ContainerListener}s in {@code broadcastChanges()}, and the click
 * packet handler calls {@code broadcastChanges()} right after applying the click, in the same call. A
 * listener there lets the box be written before anything else can happen: no other packet, no death, no
 * save, no tick. Bukkit has no API for it, so the listener is added through reflection (Mojang names,
 * which the server uses at runtime) as a proxy of the {@code ContainerListener} interface.
 */
final class MenuHook {

    private final Class<?> craftPlayer;
    private final Method getHandle;
    private final Field containerMenu;
    private final Method getBukkitView;
    private final Method addSlotListener;
    private final Method removeSlotListener;
    private final Class<?> listenerType;

    private MenuHook(Class<?> craftPlayer, Method getHandle, Field containerMenu, Method getBukkitView,
                     Method addSlotListener, Method removeSlotListener, Class<?> listenerType) {
        this.craftPlayer = craftPlayer;
        this.getHandle = getHandle;
        this.containerMenu = containerMenu;
        this.getBukkitView = getBukkitView;
        this.addSlotListener = addSlotListener;
        this.removeSlotListener = removeSlotListener;
        this.listenerType = listenerType;
    }

    /** Looks everything up once; fails when the server's classes are not what this plugin was written for. */
    static MenuHook create() throws ReflectiveOperationException {
        ClassLoader server = Bukkit.getServer().getClass().getClassLoader();
        Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer", false, server);
        Class<?> player = Class.forName("net.minecraft.world.entity.player.Player", false, server);
        Class<?> menu = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", false, server);
        Class<?> listener = Class.forName("net.minecraft.world.inventory.ContainerListener", false, server);
        Class<?> itemStack = Class.forName("net.minecraft.world.item.ItemStack", false, server);

        Method getHandle = craftPlayer.getMethod("getHandle");
        Field containerMenu = player.getField("containerMenu");
        if (!menu.isAssignableFrom(containerMenu.getType())) {
            throw new NoSuchFieldException("Player.containerMenu is a " + containerMenu.getType().getName());
        }
        if (!listener.isInterface()) {
            throw new ClassNotFoundException("ContainerListener is not an interface");
        }
        // The two callbacks broadcastChanges() makes; their absence means the protocol changed.
        listener.getMethod("slotChanged", menu, int.class, itemStack);
        listener.getMethod("dataChanged", menu, int.class, int.class);
        return new MenuHook(craftPlayer, getHandle, containerMenu, menu.getMethod("getBukkitView"),
                menu.getMethod("addSlotListener", listener), menu.getMethod("removeSlotListener", listener),
                listener);
    }

    /**
     * Adds a listener to the menu the player has open, which must be the menu showing {@code top}.
     * {@code onChange} runs inside the server's {@code broadcastChanges()}: it must not throw, and must not
     * open or close inventories.
     */
    Attachment attach(Player player, Inventory top, Runnable onChange) throws ReflectiveOperationException {
        if (!craftPlayer.isInstance(player)) {
            throw new IllegalArgumentException("Not a server player: " + player.getClass().getName());
        }
        Object menu = containerMenu.get(getHandle.invoke(player));
        Object view = getBukkitView.invoke(menu);
        if (!(view instanceof InventoryView inventoryView) || inventoryView.getTopInventory() != top) {
            throw new IllegalStateException("The open menu does not show the box");
        }
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "slotChanged" -> {
                onChange.run();
                yield null;
            }
            case "dataChanged" -> null;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ShulkerAnywhere menu listener";
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
        };
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                handler);
        addSlotListener.invoke(menu, listener);
        return new Attachment(menu, listener);
    }

    /** Removes the listener; the menu is about to be thrown away anyway, so failures don't matter. */
    void detach(Attachment attachment) {
        if (attachment == null) {
            return;
        }
        try {
            removeSlotListener.invoke(attachment.menu(), attachment.listener());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The menu is closed; it drops the listener with itself.
        }
    }

    record Attachment(Object menu, Object listener) {
    }
}
