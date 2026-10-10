package fr.smpadmin;

import fr.smpadmin.gui.BansMenu;
import fr.smpadmin.gui.HistoryMenu;
import fr.smpadmin.gui.MainMenu;
import fr.smpadmin.gui.PlayerMenu;
import fr.smpadmin.gui.SanctionMenu;
import fr.smpadmin.gui.SuspectsMenu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static fr.smpadmin.Texts.ph;

/** Toutes les commandes du plugin. */
final class AdminCommand implements TabExecutor {

    private static final List<String> DURATIONS = List.of("30m", "1h", "6h", "1d", "3d", "7d", "14d", "30d", "perm");

    private final SmpAdmin plugin;

    AdminCommand(SmpAdmin plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    private Actions a() {
        return plugin.actions();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] raw) {
        // "-s" n'importe où = sanction discrète
        boolean silent = false;
        List<String> list = new ArrayList<>();
        for (String s : raw) {
            if (s.equalsIgnoreCase("-s")) silent = true;
            else list.add(s);
        }
        String[] args = list.toArray(new String[0]);
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        switch (c) {
            case "admin" -> admin(sender, args);
            case "vanish" -> vanish(sender, args);
            case "ban" -> sanction(sender, args, Punishment.Type.BAN, silent, "/ban <joueur> [durée] [raison]");
            case "mute" -> sanction(sender, args, Punishment.Type.MUTE, silent, "/mute <joueur> [durée] [raison]");
            case "kick" -> sanction(sender, args, Punishment.Type.KICK, silent, "/kick <joueur> [raison]");
            case "warn" -> sanction(sender, args, Punishment.Type.WARN, silent, "/warn <joueur> <raison>");
            case "unban" -> withTarget(sender, args, "/unban <joueur>", tg -> a().unban(sender, tg));
            case "unmute" -> withTarget(sender, args, "/unmute <joueur>", tg -> a().unmute(sender, tg));
            case "banlist" -> banlist(sender);
            case "history" -> withTarget(sender, args, "/history <joueur>", tg -> history(sender, tg));
            case "freeze" -> withOnline(sender, args, "/freeze <joueur>", tg -> a().freeze(sender, tg, !a().isFrozen(tg.getUniqueId())));
            case "sus" -> sus(sender, args);
            case "spec" -> spec(sender, args);
            case "invsee", "ecsee" -> {
                if (!(sender instanceof Player p)) {
                    t().send(sender, "error.player-only");
                    return true;
                }
                withOnline(sender, args, "/" + c + " <joueur>", tg -> plugin.openInventory(p, tg, c.equals("ecsee")));
            }
            case "staffchat" -> staffChat(sender, args);
            case "clearchat" -> a().clearChat(sender);
            case "chatlock" -> a().toggleChatLock(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ helpers

    private interface TargetAction {
        void run(OfflinePlayer target);
    }

    private interface OnlineAction {
        void run(Player target);
    }

    private void withTarget(CommandSender sender, String[] args, String usage, TargetAction action) {
        if (args.length < 1) {
            t().send(sender, "error.usage", ph("usage", usage));
            return;
        }
        OfflinePlayer target = a().resolve(args[0]);
        if (target == null) {
            t().send(sender, "error.unknown-player", ph("player", args[0]));
            return;
        }
        action.run(target);
    }

    private void withOnline(CommandSender sender, String[] args, String usage, OnlineAction action) {
        if (args.length < 1) {
            t().send(sender, "error.usage", ph("usage", usage));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            t().send(sender, "error.offline", ph("player", args[0]));
            return;
        }
        action.run(target);
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
    }

    // ------------------------------------------------------------ commandes

    private void admin(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("smpadmin.reload")) {
                t().send(sender, "error.no-permission");
                return;
            }
            plugin.reload();
            t().send(sender, "reloaded");
            return;
        }
        if (!(sender instanceof Player p)) {
            t().send(sender, "error.player-only");
            return;
        }
        if (args.length == 0) {
            new MainMenu(plugin, p, 0).open();
            return;
        }
        OfflinePlayer target = a().resolve(args[0]);
        if (target == null) t().send(sender, "error.unknown-player", ph("player", args[0]));
        else new PlayerMenu(plugin, p, target).open();
    }

    private void vanish(CommandSender sender, String[] args) {
        if (args.length > 0) {
            if (!sender.hasPermission("smpadmin.vanish.others")) {
                t().send(sender, "error.no-permission");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                t().send(sender, "error.offline", ph("player", args[0]));
                return;
            }
            plugin.vanish().toggle(target);
            t().send(sender, plugin.vanish().is(target) ? "vanish.other-on" : "vanish.other-off", ph("player", target.getName()));
            return;
        }
        if (sender instanceof Player p) plugin.vanish().toggle(p);
        else t().send(sender, "error.player-only");
    }

    /** /ban, /mute, /kick, /warn. Juste le pseudo (en jeu) = ouvre le menu de sanction. */
    private void sanction(CommandSender sender, String[] args, Punishment.Type type, boolean silent, String usage) {
        if (args.length < 1) {
            t().send(sender, "error.usage", ph("usage", usage));
            return;
        }
        OfflinePlayer target = a().resolve(args[0]);
        if (target == null) {
            t().send(sender, "error.unknown-player", ph("player", args[0]));
            return;
        }
        if (args.length == 1 && sender instanceof Player p) {
            new SanctionMenu(plugin, p, target, type, null).open();
            return;
        }
        Settings s = plugin.settings();
        switch (type) {
            case BAN, MUTE -> {
                long duration = type == Punishment.Type.BAN ? s.banDefault : s.muteDefault;
                int reasonFrom = 1;
                // un nombre sans unité ("/ban X 10") est sûrement une erreur : on refuse plutôt que bannir à vie
                if (args.length > 1 && args[1].matches("\\d+")) {
                    t().send(sender, "error.bad-duration", ph("value", args[1]));
                    return;
                }
                if (args.length > 1 && Durations.isDuration(args[1])) {
                    duration = Durations.parse(args[1]);
                    reasonFrom = 2;
                }
                String reason = join(args, reasonFrom);
                if (reason.isEmpty()) reason = "Non précisée";
                if (type == Punishment.Type.BAN) a().ban(sender, target, duration, reason, silent);
                else a().mute(sender, target, duration, reason, silent);
            }
            case KICK -> {
                Player online = target.getPlayer();
                if (online == null) {
                    t().send(sender, "error.offline", ph("player", args[0]));
                    return;
                }
                String reason = join(args, 1);
                a().kick(sender, online, reason.isEmpty() ? "Non précisée" : reason, silent);
            }
            case WARN -> {
                String reason = join(args, 1);
                if (reason.isEmpty()) {
                    t().send(sender, "error.usage", ph("usage", usage));
                    return;
                }
                a().warn(sender, target, reason, silent);
            }
        }
    }

    private void banlist(CommandSender sender) {
        if (sender instanceof Player p) {
            new BansMenu(plugin, p, 0).open();
            return;
        }
        for (Punishment b : plugin.sanctions().activeBans()) {
            sender.sendMessage(b.targetName + " - " + b.reason + " (" + a().remaining(b) + ") #" + b.id);
        }
    }

    private void history(CommandSender sender, OfflinePlayer target) {
        if (sender instanceof Player p) {
            new HistoryMenu(plugin, p, target, 0).open();
            return;
        }
        List<Punishment> h = plugin.sanctions().history(target.getUniqueId());
        String name = target.getName() == null ? "?" : target.getName();
        if (h.isEmpty()) {
            t().send(sender, "history.empty", ph("player", name));
            return;
        }
        sender.sendMessage(t().get("history.header", ph("player", name), ph("count", h.size())));
        for (Punishment p : h) sender.sendMessage(plugin.historyLine(p));
    }

    private void sus(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (sender instanceof Player p) new SuspectsMenu(plugin, p, 0).open();
            else for (StaffState.Suspect s : plugin.state().suspects()) sender.sendMessage(s.name() + " - " + s.reason());
            return;
        }
        if (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("rm")) {
            withTarget(sender, Arrays.copyOfRange(args, 1, args.length), "/sus remove <joueur>", tg -> a().unsus(sender, tg));
            return;
        }
        OfflinePlayer target = a().resolve(args[0]);
        if (target == null) {
            t().send(sender, "error.unknown-player", ph("player", args[0]));
            return;
        }
        String reason = join(args, 1);
        // "/sus Pseudo" sur un suspect existant = le retirer
        if (reason.isEmpty() && plugin.state().suspect(target.getUniqueId()) != null) a().unsus(sender, target);
        else a().sus(sender, target, reason);
    }

    private void spec(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            t().send(sender, "error.player-only");
            return;
        }
        Spectate sp = plugin.spectate();
        if (args.length == 0) {
            if (sp.isWatching(p)) sp.stop(p);
            else sp.startAuto(p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stop", "off" -> sp.stop(p);
            case "next", "suivant" -> sp.next(p, 1);
            case "prev", "precedent", "back" -> sp.next(p, -1);
            case "all", "tour", "auto" -> sp.startAuto(p);
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) t().send(p, "error.offline", ph("player", args[0]));
                else sp.watch(p, target);
            }
        }
    }

    private void staffChat(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                t().send(sender, "error.usage", ph("usage", "/sc <message>"));
                return;
            }
            if (plugin.state().staffChat.remove(p.getUniqueId())) t().send(p, "staffchat.toggle-off");
            else {
                plugin.state().staffChat.add(p.getUniqueId());
                t().send(p, "staffchat.toggle-on");
            }
            return;
        }
        a().staffChat(Actions.name(sender), String.join(" ", args));
    }

    // ------------------------------------------------------------ complétion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            switch (c) {
                case "admin" -> {
                    options.addAll(online());
                    if (sender.hasPermission("smpadmin.reload")) options.add("reload");
                }
                case "unban" -> plugin.sanctions().activeBans().forEach(b -> options.add(b.targetName));
                case "sus" -> {
                    options.addAll(List.of("list", "remove"));
                    options.addAll(online());
                }
                case "spec" -> {
                    options.addAll(List.of("next", "prev", "stop"));
                    options.addAll(online());
                }
                case "clearchat", "chatlock", "banlist" -> {
                }
                case "staffchat" -> {
                }
                default -> options.addAll(online());
            }
        } else if (args.length == 2 && (c.equals("ban") || c.equals("mute"))) {
            options.addAll(DURATIONS);
        } else if (args.length == 2 && c.equals("sus") && args[0].equalsIgnoreCase("remove")) {
            plugin.state().suspects().forEach(s -> options.add(s.name()));
        } else if (args.length >= 2 && (c.equals("ban") || c.equals("mute") || c.equals("kick") || c.equals("warn"))) {
            options.add("-s");
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }

    private List<String> online() {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        return l;
    }
}
