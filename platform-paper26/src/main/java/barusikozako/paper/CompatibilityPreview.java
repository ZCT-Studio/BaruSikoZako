// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Exposes the capability state while save and startup barriers are being validated. */
public final class CompatibilityPreview extends JavaPlugin {
    private final PaperCaptureSource capture = new PaperCaptureSource();

    @Override
    public void onEnable() {
        getLogger().info(PaperCaptureSource.REJECTION);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("status"))) {
            sender.sendMessage("Online capture available: " + capture.available());
            sender.sendMessage(PaperCaptureSource.REJECTION);
        } else if (args[0].equals("backup")) {
            sender.sendMessage(PaperCaptureSource.REJECTION);
        } else if (args[0].equals("restore")) {
            sender.sendMessage("UNSUPPORTED: offline restore requires a verified pre-world-load guard.");
        } else {
            return false;
        }
        return true;
    }
}
