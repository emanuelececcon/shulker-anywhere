package io.github.emanuelececcon.shulkeranywhere;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class PinRulesTest {

    @Test
    void opensFromHotbarMainInventoryAndOffHandOnly() {
        for (int slot = 0; slot <= 35; slot++) {
            assertTrue(PinRules.isOpenableSlot(slot), "slot " + slot);
        }
        for (int slot = 36; slot <= 39; slot++) {
            assertFalse(PinRules.isOpenableSlot(slot), "armor slot " + slot);
        }
        assertTrue(PinRules.isOpenableSlot(PinRules.OFF_HAND));
        assertFalse(PinRules.isOpenableSlot(-1));
        assertFalse(PinRules.isOpenableSlot(41));
    }

    @Test
    void anyClickOnTheBoxSlotTouchesIt() {
        for (ClickType click : ClickType.values()) {
            assertTrue(PinRules.touchesBox(12, 12, click, -1), click.name());
        }
    }

    @Test
    void clicksInTheBoxViewOrOnOtherSlotsDoNot() {
        assertFalse(PinRules.touchesBox(12, -1, ClickType.LEFT, -1));
        assertFalse(PinRules.touchesBox(12, 13, ClickType.SHIFT_LEFT, -1));
        assertFalse(PinRules.touchesBox(12, -1, ClickType.DROP, -1));
        assertFalse(PinRules.touchesBox(3, 20, ClickType.RIGHT, -1));
    }

    @Test
    void numberKeyOfTheBoxHotbarSlotTouchesIt() {
        assertTrue(PinRules.touchesBox(3, -1, ClickType.NUMBER_KEY, 3));
        assertTrue(PinRules.touchesBox(3, 20, ClickType.NUMBER_KEY, 3));
        assertFalse(PinRules.touchesBox(3, -1, ClickType.NUMBER_KEY, 4));
        assertFalse(PinRules.touchesBox(20, -1, ClickType.NUMBER_KEY, 0));
    }

    @Test
    void numberKeyWithAnImpossibleButtonIsRefused() {
        assertTrue(PinRules.touchesBox(20, -1, ClickType.NUMBER_KEY, -1));
        assertTrue(PinRules.touchesBox(20, -1, ClickType.NUMBER_KEY, 9));
        assertTrue(PinRules.touchesBox(20, -1, ClickType.NUMBER_KEY, 40));
    }

    @Test
    void offHandSwapTouchesABoxInTheOffHand() {
        assertTrue(PinRules.touchesBox(PinRules.OFF_HAND, -1, ClickType.SWAP_OFFHAND, -1));
        assertTrue(PinRules.touchesBox(PinRules.OFF_HAND, 7, ClickType.SWAP_OFFHAND, -1));
        assertFalse(PinRules.touchesBox(7, 8, ClickType.SWAP_OFFHAND, -1));
    }

    @Test
    void unknownAndCreativeClicksAreAlwaysRefused() {
        assertTrue(PinRules.alwaysRefused(ClickType.UNKNOWN));
        assertTrue(PinRules.alwaysRefused(ClickType.CREATIVE));
        assertFalse(PinRules.alwaysRefused(ClickType.LEFT));
        assertFalse(PinRules.alwaysRefused(ClickType.DOUBLE_CLICK));
    }
}
