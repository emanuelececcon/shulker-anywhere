package io.github.emanuelececcon.shulkeranywhere;

import org.bukkit.configuration.file.FileConfiguration;

/** The values of config.yml. */
record Settings(boolean enabled, boolean openFromInventory, boolean openFromHand, boolean sounds,
                boolean logSessions) {

    static Settings load(FileConfiguration config) {
        return new Settings(
                config.getBoolean("enabled", true),
                config.getBoolean("open.from-inventory", true),
                config.getBoolean("open.from-hand", true),
                config.getBoolean("sounds", true),
                config.getBoolean("log-sessions", false));
    }
}
