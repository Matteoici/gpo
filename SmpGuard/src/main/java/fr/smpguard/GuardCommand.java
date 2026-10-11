package fr.smpguard;

import fr.smpguard.gui.PanelMenu;
import fr.smpguard.gui.ReportMenu;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static fr.smpguard.Texts.ph;

final class GuardCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("check", "bait", "alerts", "status", "reset", "reload");

    private final SmpGuard plugin;

    GuardCommand(SmpGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Texts t = plugin.texts();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (sender instanceof Player p) new PanelMenu(plugin, p, 0).open();
                else status(sender);
            }
            case "check", "rapport", "report" -> {
                Player target = target(sender, args, "/anticheat check <joueur>");
                if (target == null) return true;
                if (sender instanceof Player p) new ReportMenu(plugin, p, target).open();
                else textReport(sender, target);
            }
            case "bait", "appat" -> {
                Player target = target(sender, args, "/anticheat bait <joueur> [secondes]");
                if (target == null) return true;
                int sec = plugin.settings().baitSeconds;
                if (args.length > 2) {
                    try {
                        sec = Math.max(10, Math.min(120, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                plugin.startBait(sender, target, sec);
            }
            case "alerts", "alertes" -> {
                if (!(sender instanceof Player p)) return true;
                t.send(p, plugin.alerts().toggle(p) ? "alerts-on" : "alerts-off");
            }
            case "status" -> status(sender);
            case "reset" -> {
                if (!sender.hasPermission("smpguard.admin")) {
                    t.send(sender, "no-permission");
                    return true;
                }
                Player target = target(sender, args, "/anticheat reset <joueur>");
                if (target == null) return true;
                Profile prof = plugin.profile(target);
                if (prof != null) {
                    Arrays.fill(prof.vl, 0);
                    prof.susSent = false;
                }
                t.send(sender, "reset", ph("player", target.getName()));
            }
            case "reload" -> {
                if (!sender.hasPermission("smpguard.admin")) {
                    t.send(sender, "no-permission");
                    return true;
                }
                plugin.reload();
                t.send(sender, "reloaded");
            }
            default -> t.sendLines(sender, "help");
        }
        return true;
    }

    private Player target(CommandSender sender, String[] args, String usage) {
        if (args.length < 2) {
            plugin.texts().send(sender, "usage", ph("usage", usage));
            return null;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) plugin.texts().send(sender, "offline", ph("player", args[1]));
        return p;
    }

    private void status(CommandSender sender) {
        Texts t = plugin.texts();
        int alerts = 0;
        for (Profile p : plugin.profiles()) alerts += p.alerts;
        t.sendLines(sender, "status", ph("players", plugin.profiles().size()), ph("alerts", alerts),
                ph("tps", String.format(Locale.US, "%.1f", Lag.tps())),
                ph("culling", plugin.settings().hideBehindWalls ? "ON (" + plugin.culling().hiddenPairs() + ")" : "OFF"));
    }

    private void textReport(CommandSender sender, Player target) {
        Profile prof = plugin.profile(target);
        if (prof == null) return;
        sender.sendMessage(target.getName() + " | " + Lag.describe(prof) + " | confiance " + Math.round(plugin.lag().weight(prof) * 100) + "%");
        for (Check c : Check.values()) {
            sender.sendMessage(" - " + c.key + " : VL " + String.format(Locale.US, "%.2f", prof.vl[c.ordinal()]));
        }
        for (String s : prof.recent) sender.sendMessage("   " + s);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> opts = new ArrayList<>();
        if (args.length == 1) opts.addAll(SUBS);
        else if (args.length == 2 && !List.of("alerts", "status", "reload").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }
}
