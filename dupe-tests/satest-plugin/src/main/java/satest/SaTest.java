package satest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only helper: item ledgers and item setup for the ShulkerAnywhere bot tests. */
public final class SaTest extends JavaPlugin {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String reply = run(args);
            sender.sendMessage("SATEST " + reply);
        } catch (Exception e) {
            sender.sendMessage("SATEST ERROR " + e);
            getLogger().log(java.util.logging.Level.WARNING, "satest failed", e);
        }
        return true;
    }

    private String run(String[] args) throws IOException {
        switch (args[0]) {
            case "ledger" -> {
                Player player = Bukkit.getPlayerExact(args[1]);
                Map<String, Object> ledger = ledger(player, args[1]);
                File out = new File(getDataFolder(), args[2] + ".json");
                getDataFolder().mkdirs();
                Files.writeString(out.toPath(), gson.toJson(ledger), StandardCharsets.UTF_8);
                return "OK " + out.getAbsolutePath();
            }
            case "box" -> {
                // satest box <player> <slot> <spec> [amount]; spec "diamond*64@0,iron_ingot*5@13" or "-" for empty
                Player player = Bukkit.getPlayerExact(args[1]);
                int slot = Integer.parseInt(args[2]);
                ItemStack box = ItemStack.of(Material.SHULKER_BOX);
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < 27; i++) {
                    items.add(ItemStack.empty());
                }
                if (!args[3].equals("-")) {
                    for (String part : args[3].split(",")) {
                        String[] at = part.split("@");
                        String[] idCount = at[0].split("\\*");
                        ItemStack item = ItemStack.of(Material.matchMaterial(idCount[0]), Integer.parseInt(idCount[1]));
                        items.set(Integer.parseInt(at[1]), item);
                    }
                }
                box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(items));
                if (args.length > 4) {
                    box.setData(DataComponentTypes.MAX_STACK_SIZE, 64);
                    box.setAmount(Integer.parseInt(args[4]));
                }
                player.getInventory().setItem(slot, box);
                return "OK";
            }
            case "set" -> {
                // satest set <player> <slot> <id> <count>
                Player player = Bukkit.getPlayerExact(args[1]);
                int slot = Integer.parseInt(args[2]);
                int count = Integer.parseInt(args[4]);
                player.getInventory().setItem(slot, count == 0 ? null : ItemStack.of(Material.matchMaterial(args[3]), count));
                return "OK";
            }
            case "clean" -> {
                // removes dropped items and container blocks around the player, clears the inventory
                Player player = Bukkit.getPlayerExact(args[1]);
                for (Entity entity : player.getNearbyEntities(64, 64, 64)) {
                    if (entity instanceof Item) {
                        entity.remove();
                    }
                }
                for (Block block : containersNear(player)) {
                    block.setType(Material.AIR);
                }
                player.getInventory().clear();
                player.setItemOnCursor(null);
                return "OK";
            }
            case "health" -> {
                Player player = Bukkit.getPlayerExact(args[1]);
                player.setHealth(Double.parseDouble(args[2]));
                return "OK";
            }
            case "view" -> {
                Player player = Bukkit.getPlayerExact(args[1]);
                InventoryView view = player.getOpenInventory();
                return view.getType() + " " + view.getTopInventory().getHolder(false);
            }
            default -> {
                return "unknown";
            }
        }
    }

    private Map<String, Object> ledger(Player player, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> totals = new TreeMap<>();
        out.put("online", player != null);
        if (player == null) {
            out.put("totals", totals);
            return out;
        }
        out.put("dead", player.isDead());
        List<Object> inv = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].isEmpty()) {
                Map<String, Object> entry = describe(contents[slot], totals);
                entry.put("slot", slot);
                inv.add(entry);
            }
        }
        out.put("inv", inv);
        ItemStack cursor = player.getItemOnCursor();
        out.put("cursor", cursor.isEmpty() ? null : describe(cursor, totals));
        InventoryView view = player.getOpenInventory();
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("type", view.getType().name());
        Object holder = view.getTopInventory().getHolder(false);
        open.put("holder", holder == null ? null : holder.getClass().getSimpleName());
        List<Object> top = new ArrayList<>();
        ItemStack[] topItems = view.getTopInventory().getContents();
        for (int slot = 0; slot < topItems.length; slot++) {
            if (topItems[slot] != null && !topItems[slot].isEmpty()) {
                top.add(List.of(slot, topItems[slot].getType().getKey().toString(), topItems[slot].getAmount()));
            }
        }
        open.put("top", top);
        out.put("open", open);
        List<Object> entities = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(64, 64, 64)) {
            if (entity instanceof Item item && !item.isDead()) {
                entities.add(describe(item.getItemStack(), totals));
            }
        }
        out.put("entities", entities);
        List<Object> blocks = new ArrayList<>();
        for (Block block : containersNear(player)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pos", List.of(block.getX(), block.getY(), block.getZ()));
            entry.put("type", block.getType().getKey().toString());
            List<Object> items = new ArrayList<>();
            if (block.getState(false) instanceof Container container) {
                ItemStack[] blockItems = container.getInventory().getContents();
                for (int slot = 0; slot < blockItems.length; slot++) {
                    if (blockItems[slot] != null && !blockItems[slot].isEmpty()) {
                        Map<String, Object> item = describe(blockItems[slot], totals);
                        item.put("slot", slot);
                        items.add(item);
                    }
                }
            }
            entry.put("items", items);
            blocks.add(entry);
        }
        out.put("blocks", blocks);
        out.put("totals", totals);
        return out;
    }

    private static List<Block> containersNear(Player player) {
        List<Block> found = new ArrayList<>();
        Block center = player.getLocation().getBlock();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    Block block = center.getRelative(dx, dy, dz);
                    if (block.getType().name().endsWith("SHULKER_BOX") || block.getType() == Material.CHEST) {
                        found.add(block);
                    }
                }
            }
        }
        return found;
    }

    private static Map<String, Object> describe(ItemStack item, Map<String, Integer> totals) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", item.getType().getKey().toString());
        entry.put("count", item.getAmount());
        count(item, 1, totals);
        ItemContainerContents data = item.getData(DataComponentTypes.CONTAINER);
        if (data != null) {
            List<Object> box = new ArrayList<>();
            List<ItemStack> inside = data.contents();
            for (int slot = 0; slot < inside.size(); slot++) {
                ItemStack in = inside.get(slot);
                if (!in.isEmpty()) {
                    box.add(List.of(slot, in.getType().getKey().toString(), in.getAmount()));
                }
            }
            entry.put("box", box);
        }
        io.papermc.paper.datacomponent.item.BundleContents bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<Object> inBundle = new ArrayList<>();
            for (ItemStack in : bundle.contents()) {
                inBundle.add(List.of(in.getType().getKey().toString(), in.getAmount()));
            }
            entry.put("bundle", inBundle);
        }
        return entry;
    }

    /** Adds the item and everything inside it (boxes, bundles, nested) to the totals. */
    private static void count(ItemStack item, int multiplier, Map<String, Integer> totals) {
        int copies = item.getAmount() * multiplier;
        totals.merge(item.getType().getKey().toString(), copies, Integer::sum);
        ItemContainerContents data = item.getData(DataComponentTypes.CONTAINER);
        if (data != null) {
            for (ItemStack in : data.contents()) {
                if (!in.isEmpty()) {
                    count(in, copies, totals);
                }
            }
        }
        io.papermc.paper.datacomponent.item.BundleContents bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack in : bundle.contents()) {
                count(in, copies, totals);
            }
        }
    }
}
