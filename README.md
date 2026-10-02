# ShulkerAnywhere

A Paper 26.3 plugin that opens shulker boxes straight from your inventory. Right-click a box in your inventory, or right-click the air with a box in your hand, and its contents open in the normal Shulker Box screen. You don't have to place it first.

Shulker box plugins are a classic source of duplication bugs, so this one is built around a single rule: the box item is the only real copy of its items. Every change you make in the screen is written into the box in the same server call that applies it. [Why items can't be duplicated](#why-items-cant-be-duplicated) explains how, and [Tests](#tests) shows how it was checked.

## How to open a box

- **In the inventory screen**: right-click a shulker box with an empty cursor. This also works in the lower half of a chest or any other container screen, and on another box while one is already open, which switches to that box. Left-click still picks the box up, and a right-click while you hold something on the cursor still does what it always did.
- **In your hand**: right-click while looking at the air, with the box in your main hand or off hand. Right-clicking a block still places the box.

Boxes in the hotbar, the main inventory and the off hand can be opened. Boxes inside a chest or an Ender Chest have to be taken out first.

The screen looks and behaves like a placed box: same title (the box's name if it has one), same rules (no shulker box inside a shulker box), the vanilla open and close sounds. Clicks, shift-clicks, number keys, drags, Q, double-clicks and bundles all work as usual. Only the open box can't be moved while its screen is open: its own slot, the number key of its hotbar slot and F for a box in the off hand are refused.

## Boxes that don't open this way

These boxes still open normally when placed:

- a stack of boxes (only possible with data packs or plugins that make boxes stack)
- a locked box (`lock` component)
- a box whose loot hasn't been generated yet (`container_loot`)
- a box with block entity data or items beyond its 27 slots, which only commands can create

Creative and spectator players can't use it: creative inventories are controlled by the client. A box that is open when its player switches to one of those modes is closed.

## Why items can't be duplicated

Bukkit only fires its click events *before* a click is applied, so most plugins of this kind copy the screen back into the item on the next tick. That leaves a window where the screen and the item disagree, and a death, a quit, a save or a packet from a modified client inside that window duplicates items.

ShulkerAnywhere has no such window:

1. **Written in the same call.** The server reports every changed slot of an open menu to its listeners in `broadcastChanges()`, and the click packet handler calls it right after applying the click. ShulkerAnywhere adds a listener there, so the box item is updated before the server handles anything else. Every save, death, quit or other plugin sees the box and the rest of the inventory agree.
2. **Checked before every click.** Before a click or drag is applied, the box must still be in its slot and exactly the item last written. If anything else changed it (a command, another plugin, a dropped or placed box), the click is refused and the screen closes on the next tick. Nothing is ever written into an item that changed.
3. **Pinned.** Clicks that would move the open box are refused. While a box is open, the packets a normal client can't send with a screen open (placing blocks, using items, touching entities, swapping hands, picking blocks) are refused too.
4. **Safe ends.** On death the box is saved before the drops spawn, the server closes the screen before it saves a quitting player, and on shutdown or `/shulkeranywhere disable` every box is saved and closed. Screens are never opened or closed inside a click event; that happens on the next tick.
5. **Fails closed.** The listener relies on server internals that Bukkit doesn't expose (see [How it works](#how-it-works)). If they are missing, or a click changes a screen without the listener hearing it, opening boxes this way turns off until the next restart, and only placing works.

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/shulkeranywhere status` | `shulkeranywhere.admin` (op) | Whether it's enabled, open boxes, counters |
| `/shulkeranywhere reload` | `shulkeranywhere.admin` (op) | Reloads `config.yml` |
| `/shulkeranywhere disable` | `shulkeranywhere.admin` (op) | Turns it off right away and saves `enabled: false`; open boxes are saved and closed |
| `/shulkeranywhere enable` | `shulkeranywhere.admin` (op) | Turns it back on |

`/sa` is an alias. Opening boxes needs `shulkeranywhere.use`, which everyone has by default.

## Settings

`plugins/ShulkerAnywhere/config.yml`. Apply changes with `/shulkeranywhere reload`.

| Key | What it does | Default |
|---|---|---|
| `enabled` | `false`: nobody can open boxes this way | `true` |
| `open.from-inventory` | Right-click in the inventory screen opens a box | `true` |
| `open.from-hand` | Right-click in the air with a box in hand opens it | `true` |
| `sounds` | Shulker box open and close sounds | `true` |
| `log-sessions` | Logs every open and close with the item count | `false` |

## How it works

| File | What it does |
|---|---|
| `ShulkerAnywherePlugin.java` | Entry point, settings, timers |
| `OpenListener.java` | Right-click in the inventory screen or in the air |
| `GuardListener.java` | Refuses clicks that could move the box, saves on close and death, refuses world actions while a box is open |
| `Sessions.java` | Opening, saving and closing boxes |
| `Session.java` | One open box: its slot, its screen and the copy last written |
| `MenuHook.java` | The listener on the server's menu (reflection) |
| `PinRules.java` | Which slots and clicks touch the open box |
| `BoxCheck.java` | Which boxes may be opened |
| `Contents.java` | The 27 slots of a box |

Bukkit has no event after a click is applied, so `MenuHook` adds a listener to the server's `AbstractContainerMenu` through reflection. The listener is a proxy of the `ContainerListener` interface, looked up by its Mojang names, which Paper uses at runtime. Everything else uses the Paper API: the box contents are read and written through the `minecraft:container` data component.

## Tests

`dupe-tests/` has an automated test against a real Paper 26.3 server. A small packet-level bot plays the role of a modified client: it opens boxes and sends clicks, number keys, F, Q, drags, double-clicks, drops, hand swaps, block placements and pick-blocks, several in the same tick. It also dies with items on the cursor, disconnects, and stops or crashes the server with a box open. A helper plugin counts every item the player owns: inventory, cursor, box and bundle contents, items on the ground and placed boxes. After every step the totals must be unchanged and the open screen must match the box. It finishes with random click storms. See `dupe-tests/README.md`.

The tests were also run against a deliberately naive build that writes the box only when the screen closes, as many plugins of this kind do. They failed it with duplicated items, for example 64 diamonds turning into 128 after a click and a drop in the same tick, or a death with the box open. 1.0.0 passes all 28.

## Build

Requirements: JDK 21 or newer and Maven 3.9+.

```bash
mvn clean package
```

The jar is `target/shulker-anywhere-<version>.jar`. Released jars are in `release/` and on the GitHub releases page.

## Install

Put the jar in `plugins/` and restart the server. Paper 26.3; other versions are refused safely if their internals differ (see point 5 above).

## License

MIT, see [LICENSE](LICENSE).
