package io.github.emanuelececcon.shulkeranywhere;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

/** Decides which shulker box items can be opened without placing them. */
final class BoxCheck {

    /** Slots of a shulker box. */
    static final int SIZE = 27;

    private final DataComponentType lock;
    private final DataComponentType blockEntityData;

    private BoxCheck(DataComponentType lock, DataComponentType blockEntityData) {
        this.lock = lock;
        this.blockEntityData = blockEntityData;
    }

    /** Fails when the server doesn't know a component the checks rely on. */
    static BoxCheck create() {
        return new BoxCheck(component("lock"), component("block_entity_data"));
    }

    private static DataComponentType component(String name) {
        DataComponentType type = Registry.DATA_COMPONENT_TYPE.get(Key.key(Key.MINECRAFT_NAMESPACE, name));
        if (type == null) {
            throw new IllegalStateException("The server has no data component minecraft:" + name);
        }
        return type;
    }

    static boolean isShulkerBox(ItemStack item) {
        return item != null && !item.isEmpty() && Tag.ITEMS_SHULKER_BOXES.isTagged(item.getType());
    }

    /**
     * Why the box can't be opened from the inventory, or null when it can. Anything a placed box would treat
     * differently from what the view shows is refused: a lock, loot still to generate, block entity data
     * (it could carry items of its own), items past slot 27 (a placed box drops them) and oversized stacks.
     */
    Refusal check(ItemStack box) {
        if (box.getAmount() != 1) {
            return Refusal.STACKED;
        }
        if (box.hasData(lock)) {
            return Refusal.LOCKED;
        }
        if (box.hasData(DataComponentTypes.CONTAINER_LOOT)) {
            return Refusal.LOOT;
        }
        if (box.hasData(blockEntityData)) {
            return Refusal.UNUSUAL;
        }
        List<ItemStack> contents = contents(box);
        for (int slot = 0; slot < contents.size(); slot++) {
            ItemStack item = contents.get(slot);
            if (!item.isEmpty() && (slot >= SIZE || item.getAmount() > item.getMaxStackSize())) {
                return Refusal.UNUSUAL;
            }
        }
        return null;
    }

    /** Copies of the box's items by slot. The list stops at the last item, so it can be shorter than 27. */
    static List<ItemStack> contents(ItemStack box) {
        ItemContainerContents data = box.getData(DataComponentTypes.CONTAINER);
        return data == null ? List.of() : data.contents();
    }
}
