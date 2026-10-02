package io.github.emanuelececcon.shulkeranywhere;

import org.bukkit.event.inventory.ClickType;

/**
 * Which player inventory slots a box can be opened from, and which clicks in an open box view could
 * move the open box itself.
 */
final class PinRules {

    /** Player inventory index of the off hand. */
    static final int OFF_HAND = 40;
    /** Hotbar 0-8 and main inventory 9-35 come before the armor slots 36-39. */
    private static final int LAST_STORAGE_SLOT = 35;
    private static final int LAST_HOTBAR_SLOT = 8;

    private PinRules() {
    }

    /** Hotbar, main inventory and off hand; never the armor slots. */
    static boolean isOpenableSlot(int slot) {
        return (slot >= 0 && slot <= LAST_STORAGE_SLOT) || slot == OFF_HAND;
    }

    /**
     * Whether a click in the box view touches the slot of the open box.
     *
     * @param boxSlot           player inventory index of the open box
     * @param clickedPlayerSlot player inventory index of the clicked slot, or -1 when the click is in the box
     *                          itself or outside the window
     * @param click             the click type
     * @param hotbarButton      the number key (0-8) of a number key click, otherwise -1
     */
    static boolean touchesBox(int boxSlot, int clickedPlayerSlot, ClickType click, int hotbarButton) {
        if (clickedPlayerSlot >= 0 && clickedPlayerSlot == boxSlot) {
            return true;
        }
        // A number key swaps the hovered slot with that hotbar slot, F swaps it with the off hand.
        if (click == ClickType.NUMBER_KEY && (hotbarButton == boxSlot || hotbarButton < 0
                || hotbarButton > LAST_HOTBAR_SLOT)) {
            return true;
        }
        return click == ClickType.SWAP_OFFHAND && boxSlot == OFF_HAND;
    }

    /** Clicks refused in a box view whatever they touch: they do nothing in vanilla either. */
    static boolean alwaysRefused(ClickType click) {
        return click == ClickType.UNKNOWN || click == ClickType.CREATIVE;
    }
}
