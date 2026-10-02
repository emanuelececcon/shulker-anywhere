package io.github.emanuelececcon.shulkeranywhere;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/** The 27 slots of a box, as an array where empty slots are null. */
final class Contents {

    private Contents() {
    }

    /** Deep copy, always 27 long. */
    static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[BoxCheck.SIZE];
        for (int slot = 0; slot < BoxCheck.SIZE && slot < items.length; slot++) {
            copy[slot] = isEmpty(items[slot]) ? null : items[slot].clone();
        }
        return copy;
    }

    /** The items stored in the box item, by slot. */
    static ItemStack[] ofBox(ItemStack box) {
        ItemStack[] slots = new ItemStack[BoxCheck.SIZE];
        List<ItemStack> contents = BoxCheck.contents(box);
        for (int slot = 0; slot < BoxCheck.SIZE && slot < contents.size(); slot++) {
            ItemStack item = contents.get(slot);
            slots[slot] = isEmpty(item) ? null : item;
        }
        return slots;
    }

    /** Same items in the same slots, compared with every component and the count. */
    static boolean same(ItemStack[] a, ItemStack[] b) {
        for (int slot = 0; slot < BoxCheck.SIZE; slot++) {
            ItemStack left = slot < a.length ? a[slot] : null;
            ItemStack right = slot < b.length ? b[slot] : null;
            boolean leftEmpty = isEmpty(left);
            if (leftEmpty != isEmpty(right) || !leftEmpty && !left.equals(right)) {
                return false;
            }
        }
        return true;
    }

    /** The list the container component is built from: one entry per slot, empty slots as empty stacks. */
    static List<ItemStack> asList(ItemStack[] items) {
        List<ItemStack> list = new ArrayList<>(BoxCheck.SIZE);
        for (int slot = 0; slot < BoxCheck.SIZE; slot++) {
            ItemStack item = slot < items.length ? items[slot] : null;
            list.add(isEmpty(item) ? ItemStack.empty() : item);
        }
        return list;
    }

    static int count(ItemStack[] items) {
        int count = 0;
        for (ItemStack item : items) {
            if (!isEmpty(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** "slot 3: 64 x minecraft:diamond, ..." for log lines. */
    static String describe(ItemStack[] items) {
        StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < items.length; slot++) {
            if (!isEmpty(items[slot])) {
                if (!text.isEmpty()) {
                    text.append(", ");
                }
                text.append("slot ").append(slot).append(": ").append(items[slot].getAmount()).append(" x ")
                        .append(items[slot].getType().getKey());
            }
        }
        return text.isEmpty() ? "empty" : text.toString();
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }
}
