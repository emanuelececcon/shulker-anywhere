package io.github.emanuelececcon.shulkeranywhere;

import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** ShulkerAnywhere: open shulker boxes from your inventory without placing them. */
public final class ShulkerAnywherePlugin extends JavaPlugin {

    private Settings settings;
    private Sessions sessions;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        MenuHook hook = null;
        BoxCheck boxCheck = null;
        try {
            boxCheck = BoxCheck.create();
            hook = MenuHook.create();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "This server version is not supported: shulker boxes can only be opened"
                    + " by placing them", e);
        }
        sessions = new Sessions(this, hook, boxCheck);

        getServer().getPluginManager().registerEvents(new GuardListener(this, sessions), this);
        getServer().getPluginManager().registerEvents(new OpenListener(this, sessions), this);
        PluginCommand command = getCommand("shulkeranywhere");
        if (command != null) {
            ShulkerAnywhereCommand executor = new ShulkerAnywhereCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getScheduler().runTaskTimer(this, sessions::sweep, 20L, 20L);
        if (hook != null && boxCheck != null) {
            getLogger().info(settings.enabled() ? "Shulker boxes can be opened from the inventory"
                    : "Opening shulker boxes from the inventory is disabled in config.yml");
        }
    }

    @Override
    public void onDisable() {
        if (sessions != null) {
            sessions.closeAll("plugin disabled");
        }
    }

    void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig());
        if (!settings.enabled()) {
            sessions.closeAll("disabled");
        }
    }

    void setEnabledInConfig(boolean enabled) {
        getConfig().set("enabled", enabled);
        saveConfig();
        settings = Settings.load(getConfig());
        if (!enabled) {
            sessions.closeAll("disabled");
        }
    }

    Settings settings() {
        return settings;
    }

    Sessions sessions() {
        return sessions;
    }
}
