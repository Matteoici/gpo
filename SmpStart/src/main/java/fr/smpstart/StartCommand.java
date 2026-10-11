package fr.smpstart;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpstart.Texts.ph;

/** /guide (alias /start, /debut, /tuto). */
public final class StartCommand implements TabExecutor {

    private final SmpStart plugin;

    StartCommand(SmpStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Texts t = plugin.texts();
        if (args.length == 0) {
            if (sender instanceof Player p) new GuideMenu(plugin, p).open();
            else t.send(sender, "error.player-only");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "bar" -> {
                if (!(sender instanceof Player p)) {
                    t.send(sender, "error.player-only");
                    return true;
                }
                PlayerData d = plugin.data().get(p.getUniqueId());
                if (d == null || d.finished) {
                    t.send(p, "error.no-guide");
                    return true;
                }
                if (args.length >= 2) d.bar = args[1].equalsIgnoreCase("on");
                else d.bar = !d.bar;
                plugin.data().markDirty();
                plugin.bars().update(p);
                t.send(p, d.bar ? "bar.on" : "bar.off");
            }
            case "protection" -> {
                if (!(sender instanceof Player p)) {
                    t.send(sender, "error.player-only");
                    return true;
                }
                PlayerData d = plugin.data().get(p.getUniqueId());
                if (d == null || d.protectionSec <= 0) {
                    t.send(p, "protection.none");
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
                    d.protectionSec = 0;
                    plugin.data().markDirty();
                    t.send(p, "protection.disabled");
                } else {
                    t.send(p, "protection.status", ph("minutes", Math.max(1, d.protectionSec / 60)));
                }
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadAll();
                t.send(sender, "admin.reloaded", ph("steps", plugin.settings().steps().size()));
            }
            case "reset", "complete", "kit" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    t.send(sender, "error.usage", ph("usage", "/guide " + sub + " <joueur>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    t.send(sender, "error.player-offline");
                    return true;
                }
                admin(sender, sub, target);
            }
            default -> {
                if (sender instanceof Player p) new GuideMenu(plugin, p).open();
            }
        }
        return true;
    }

    private void admin(CommandSender sender, String sub, Player target) {
        Texts t = plugin.texts();
        switch (sub) {
            case "reset" -> {
                PlayerData d = plugin.data().get(target.getUniqueId());
                if (d == null) d = plugin.data().create(target.getUniqueId());
                d.step = 0;
                d.progress = 0;
                d.finished = false;
                d.reminderMin = 0;
                plugin.data().markDirty();
                plugin.bars().update(target);
                t.send(sender, "admin.reset", ph("player", target.getName()));
                plugin.progress().sendCurrent(target);
            }
            case "complete" -> {
                plugin.progress().forceFinish(target);
                t.send(sender, "admin.completed", ph("player", target.getName()));
            }
            default -> {
                plugin.kit().give(target);
                t.send(sender, "admin.kit", ph("player", target.getName()));
            }
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("smpstart.admin")) return true;
        plugin.texts().send(sender, "error.no-permission");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("bar", "protection"));
            if (sender.hasPermission("smpstart.admin")) subs.addAll(List.of("reload", "reset", "complete", "kit"));
            for (String s : subs) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("bar")) out.addAll(List.of("on", "off"));
            else if (sub.equals("protection")) out.add("off");
            else if (sender.hasPermission("smpstart.admin") && List.of("reset", "complete", "kit").contains(sub)) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
            out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
