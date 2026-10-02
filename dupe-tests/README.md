# Dupe tests

Automated tests for ShulkerAnywhere against a real Paper server. `bot/mcbot.py` is a minimal Minecraft 26.3 client (protocol 777) that behaves like a modified client: it can send any packet at any time, and several packets in one write, so the server handles them back to back in the same tick. `satest-plugin` is a test-only helper plugin that sets up items and writes a ledger of everything the player owns: inventory, cursor, box and bundle contents, items on the ground and placed boxes.

Every test compares the ledger totals before and after; while a box is open, the screen must match the box item. The tests cover:

- opening from the hand and from the inventory screen, clicks, shift-clicks, number keys, drags, Q, bundles
- every way to move the open box: its own slot, number keys, F, Q, middle-click, double-click
- packets a normal client can't send with a screen open: drop, swap hands, place the box, pick block, in the same tick as clicks
- death (with and without keepInventory) and a quit with items on the cursor, a server stop and a crash with a box open
- the box replaced by a command, switching boxes, `/shulkeranywhere disable`, a teleport to another dimension
- refused boxes (stacked, locked, loot, command-made) and creative mode
- random click storms with the box in the hand, the main inventory and the off hand

## Setup

You need Python 3.10+, a JDK 21+ and Maven.

1. Build the plugin (`mvn package` in the project root) and the helper (`mvn package` in `satest-plugin`).
2. Make a server folder, by default `dupe-tests/server/`, with:
   - the Paper 26.3 jar, named `paper.jar` (or set `SA_SERVER_JAR`)
   - `eula.txt` with `eula=true`: you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) yourself
   - `server.properties`:
     ```properties
     online-mode=false
     server-ip=127.0.0.1
     server-port=25599
     enable-rcon=true
     rcon.port=25598
     rcon.password=<a random password>
     white-list=false
     level-type=minecraft\:flat
     generate-structures=false
     spawn-protection=0
     difficulty=peaceful
     view-distance=3
     simulation-distance=3
     network-compression-threshold=-1
     ```
   - `rcon.pw` holding the same RCON password
   - `plugins/` with `shulker-anywhere-<version>.jar` and `satest.jar`
   - `plugins/ShulkerAnywhere/config.yml` with `log-sessions: true` (optional)
3. Run `python bot/tests.py`, or `python bot/tests.py t04 t19` for some tests. The script starts and stops the server itself. Set `SA_JAVA` if `java` is not on the path, and `SA_SERVER_DIR` for another server folder.

The server only listens on 127.0.0.1 and runs in offline mode; never point these scripts at a real server.

## Updating for a new Minecraft version

The packet ids in `mcbot.py` follow the registration order in the server's `GameProtocols`, `ConfigurationProtocols` and `LoginProtocols` classes, and `PROTOCOL` comes from `version.json` in the server jar. A new version usually moves some ids; check them before trusting a green run.
