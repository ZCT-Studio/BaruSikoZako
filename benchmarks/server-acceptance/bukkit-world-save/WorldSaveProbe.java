// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.probe;

import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated world-metadata probe for the public Bukkit save boundary. */
public final class WorldSaveProbe extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!getServer().isPrimaryThread()) throw new IllegalStateException("World save must run on the server thread");
        var world = getServer().getWorlds().get(0);
        var key = new NamespacedKey(this, "marker");
        var data = world.getPersistentDataContainer();
        if (args.length == 1 && args[0].equals("read")) {
            getLogger().info("ZAKO_BUKKIT_MARKER value=" + data.get(key, PersistentDataType.INTEGER));
            return true;
        }
        if (args.length != 2 || !args[0].equals("save")) return false;
        int marker = Integer.parseInt(args[1]);
        data.set(key, PersistentDataType.INTEGER, marker);
        world.save();
        getLogger().info("ZAKO_BUKKIT_WORLD_SAVE_RETURN value=" + data.get(key, PersistentDataType.INTEGER));
        return true;
    }
}
