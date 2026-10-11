package fr.smpevents;

import fr.smpevents.board.Board;
import fr.smpevents.board.Holograms;
import fr.smpevents.bounty.Bounty;
import fr.smpevents.gui.BoardMenu;
import fr.smpevents.gui.BountyMenu;
import fr.smpevents.gui.QuestMenu;
import fr.smpevents.koth.Koth;
import fr.smpevents.koth.KothZone;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpevents.Texts.ph;

/** /quetes, /classement, /koth, /prime, /smpevents */
final class Commands implements CommandExecutor, TabCompleter {

    private final SmpEvents plugin;

    Commands(SmpEvents plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    private static boolean admin(CommandSender s) {
        return s.hasPermission("smpevents.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "quetes" -> quests(sender, a);
            case "classement" -> boards(sender, a);
            case "koth" -> koth(sender, a);
            case "prime" -> bounty(sender, a);
            case "smpevents" -> {
                if (!admin(sender)) {
                    t().send(sender, "no-permission");
                } else if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    plugin.reload();
                    t().send(sender, "reloaded");
                } else {
                    t().sendLines(sender, "help-admin");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ================================================================= quêtes

    private void quests(CommandSender sender, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("reset") && admin(sender)) {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (op == null) {
                t().send(sender, "unknown-player", ph("player", a[1]));
                return;
            }
            plugin.quests().reset(plugin.data().of(op));
            t().send(sender, "quests.reset", ph("player", a[1]));
            return;
        }
        if (!(sender instanceof Player p)) {
            t().send(sender, "players-only");
            return;
        }
        if (!plugin.quests().enabled) {
            t().send(p, "quests.disabled");
            return;
        }
        new QuestMenu(plugin, p).open();
    }

    // ================================================================= classements

    private void boards(CommandSender sender, String[] a) {
        if (a.length == 0 || !admin(sender)) {
            if (sender instanceof Player p) new BoardMenu(plugin, p).open();
            else t().send(sender, "players-only");
            return;
        }
        Holograms holos = plugin.holograms();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "placer", "place", "creer", "create" -> {
                if (!(sender instanceof Player p)) {
                    t().send(sender, "players-only");
                    return;
                }
                if (a.length < 2) {
                    t().send(p, "usage", ph("usage", "/classement placer <" + boardKeys() + "> (plusieurs : argent,kills,temps)"));
                    return;
                }
                List<Board> list = new ArrayList<>();
                for (String s : a[1].split(",")) {
                    Board b = Board.of(s);
                    if (b == null) {
                        t().send(p, "boards.unknown", ph("name", s), ph("list", boardKeys()));
                        return;
                    }
                    list.add(b);
                }
                Location at = p.getLocation().clone().add(0, 2.2, 0);
                at.setYaw(0);
                at.setPitch(0);
                Holograms.Holo h = holos.create(at, list);
                t().send(p, "boards.placed", ph("id", h.id));
            }
            case "retirer", "remove", "supprimer" -> {
                Holograms.Holo h = null;
                if (a.length >= 2) h = holos.get(a[1]);
                else if (sender instanceof Player p) h = holos.nearest(p.getLocation(), 8);
                if (h == null || !holos.remove(h.id)) {
                    t().send(sender, "boards.none-near");
                    return;
                }
                t().send(sender, "boards.removed", ph("id", h.id));
            }
            case "liste", "list" -> {
                List<Holograms.Holo> all = holos.all();
                if (all.isEmpty()) {
                    t().send(sender, "boards.list-empty");
                    return;
                }
                for (Holograms.Holo h : all) {
                    List<String> keys = new ArrayList<>();
                    for (Board b : h.boards) keys.add(b.key);
                    sender.sendMessage(t().get("boards.list-line", ph("id", h.id), ph("boards", String.join(", ", keys)),
                            ph("world", h.world), ph("x", (int) h.x), ph("y", (int) h.y), ph("z", (int) h.z))
                            .clickEvent(ClickEvent.runCommand("/execute in " + worldKey(h.world) + " run tp @s "
                                    + h.x + " " + (h.y - 2.2) + " " + h.z)));
                }
            }
            case "scan" -> {
                int n = plugin.boards().startScan(true);
                t().send(sender, "boards.scan", ph("count", n));
            }
            case "menu" -> {
                if (sender instanceof Player p) new BoardMenu(plugin, p).open();
            }
            default -> t().sendLines(sender, "boards.help");
        }
    }

    private static String worldKey(String world) {
        org.bukkit.World w = Bukkit.getWorld(world);
        return w == null ? "minecraft:overworld" : w.getKey().asString();
    }

    private static String boardKeys() {
        List<String> k = new ArrayList<>();
        for (Board b : Board.values()) k.add(b.key);
        return String.join(", ", k);
    }

    // ================================================================= roi de la colline

    private void koth(CommandSender sender, String[] a) {
        Koth k = plugin.koth();
        if (a.length == 0 || !admin(sender) && !a[0].equalsIgnoreCase("info")) {
            info(sender, k);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "creer", "create" -> {
                if (!(sender instanceof Player p)) {
                    t().send(sender, "players-only");
                    return;
                }
                if (a.length < 2) {
                    t().send(p, "usage", ph("usage", "/koth creer <nom> [rayon]"));
                    return;
                }
                double r = 5;
                if (a.length >= 3) {
                    try {
                        r = Math.max(2, Math.min(30, Double.parseDouble(a[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                KothZone z = k.create(a[1], p.getLocation(), r);
                t().send(p, "koth.created", ph("zone", z.name), ph("radius", (int) r));
            }
            case "supprimer", "delete" -> {
                if (a.length < 2 || !k.delete(a[1])) t().send(sender, "koth.unknown");
                else t().send(sender, "koth.deleted", ph("zone", a[1]));
            }
            case "liste", "list" -> {
                if (k.zones().isEmpty()) {
                    t().send(sender, "koth.list-empty");
                    return;
                }
                for (KothZone z : k.zones()) {
                    sender.sendMessage(t().get("koth.list-line", ph("zone", z.name), ph("world", z.world),
                            ph("x", (int) z.x), ph("y", (int) z.y), ph("z", (int) z.z), ph("radius", (int) z.radius)));
                }
            }
            case "lancer", "start" -> {
                if (k.active() != null) {
                    t().send(sender, "koth.already");
                    return;
                }
                if (a.length >= 2) {
                    KothZone z = k.zone(a[1]);
                    if (z == null) t().send(sender, "koth.unknown");
                    else k.start(z);
                    return;
                }
                t().send(sender, "koth.searching");
                k.upcoming(z -> {
                    if (z == null) t().send(sender, "koth.not-found");
                    else if (!k.start(z)) t().send(sender, "koth.already");
                });
            }
            case "stop" -> {
                if (k.active() == null) t().send(sender, "koth.not-running");
                else k.stop(true);
            }
            case "tp" -> {
                if (!(sender instanceof Player p) || a.length < 2) return;
                KothZone z = k.zone(a[1]);
                Location c = z == null ? null : z.center();
                if (c == null) t().send(p, "koth.unknown");
                else p.teleport(c.add(0, 1, 0));
            }
            default -> info(sender, k);
        }
    }

    private void info(CommandSender sender, Koth k) {
        KothZone z = k.active();
        if (z != null) {
            String king = k.kingName();
            t().sendLines(sender, "koth.info-active", ph("zone", z.name), ph("x", (int) z.x), ph("y", (int) z.y),
                    ph("z", (int) z.z), ph("king", king == null ? t().raw("koth.nobody") : king),
                    ph("time", k.kingTime() / 60 + ":" + String.format("%02d", k.kingTime() % 60)),
                    ph("capture", k.captureSeconds / 60 + ":" + String.format("%02d", k.captureSeconds % 60)));
            return;
        }
        ZonedDateTime next = k.next();
        if (next == null || !k.enabled || (k.zones().isEmpty() && k.zoneMode.equals("zones"))) {
            t().sendLines(sender, "koth.info-none");
        } else {
            long ms = next.toInstant().toEpochMilli() - System.currentTimeMillis();
            t().sendLines(sender, "koth.info-next", ph("when", String.format("%02d:%02d", next.getHour(), next.getMinute())),
                    ph("in", Settings.time(ms)), ph("money", plugin.settings().money(k.rewardMoney)),
                    ph("capture", k.captureSeconds / 60 + " min"));
        }
        if (admin(sender)) t().sendLines(sender, "koth.help-admin");
    }

    // ================================================================= primes

    private void bounty(CommandSender sender, String[] a) {
        if (a.length == 0) {
            if (sender instanceof Player p) new BountyMenu(plugin, p, 0).open();
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("liste") || sub.equals("list") || sub.equals("top")) {
            List<Bounty> l = plugin.bounties().sorted();
            if (l.isEmpty()) {
                t().send(sender, "bounty.list-empty");
                return;
            }
            t().send(sender, "bounty.list-header");
            for (int i = 0; i < Math.min(10, l.size()); i++) {
                Bounty b = l.get(i);
                sender.sendMessage(t().get("bounty.list-line", ph("rank", i + 1), ph("player", b.targetName),
                        ph("total", plugin.settings().money(b.total()))));
            }
            return;
        }
        if ((sub.equals("retirer") || sub.equals("annuler") || sub.equals("remove")) && admin(sender)) {
            if (a.length < 2) return;
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (op == null || !plugin.bounties().cancel(op.getUniqueId())) t().send(sender, "bounty.none", ph("player", a[1]));
            else t().send(sender, "bounty.removed", ph("player", a[1]));
            return;
        }
        if (!(sender instanceof Player p)) {
            t().send(sender, "players-only");
            return;
        }
        if (!p.hasPermission("smpevents.bounty")) {
            t().send(p, "no-permission");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            t().send(p, "unknown-player", ph("player", a[0]));
            return;
        }
        if (a.length < 2) {
            Bounty b = plugin.bounties().of(target.getUniqueId());
            if (b == null) t().send(p, "bounty.none", ph("player", target.getName()));
            else t().send(p, "bounty.info", ph("player", b.targetName), ph("total", plugin.settings().money(b.total())),
                    ph("count", b.contributions.size()));
            return;
        }
        double amount = parseAmount(a[1]);
        if (amount <= 0) {
            t().send(p, "usage", ph("usage", "/prime <joueur> <montant>  (ex. 50000, 50k, 1.5m)"));
            return;
        }
        plugin.bounties().place(p, target, amount);
    }

    /** "1500", "1 500", "2,5k", "1m" -> nombre ; -1 si invalide. */
    static double parseAmount(String s) {
        String x = s.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace(",", ".").replace("$", "");
        double mult = 1;
        if (x.endsWith("k")) {
            mult = 1_000;
            x = x.substring(0, x.length() - 1);
        } else if (x.endsWith("m")) {
            mult = 1_000_000;
            x = x.substring(0, x.length() - 1);
        } else if (x.endsWith("b") || x.endsWith("md")) {
            mult = 1_000_000_000;
            x = x.replaceAll("(md|b)$", "");
        }
        try {
            double v = Double.parseDouble(x) * mult;
            return Double.isFinite(v) && v > 0 ? Math.floor(v) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ================================================================= complétion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        boolean adm = admin(sender);
        if (a.length == 1) {
            switch (name) {
                case "classement" -> {
                    if (adm) out.addAll(List.of("placer", "retirer", "liste", "scan"));
                }
                case "koth" -> {
                    out.add("info");
                    if (adm) out.addAll(List.of("creer", "supprimer", "liste", "lancer", "stop", "tp"));
                }
                case "prime" -> {
                    out.add("liste");
                    if (adm) out.add("retirer");
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "quetes" -> {
                    if (adm) out.add("reset");
                }
                case "smpevents" -> out.add("reload");
                default -> {
                }
            }
        } else if (a.length == 2) {
            switch (name) {
                case "classement" -> {
                    if (adm && (a[0].equalsIgnoreCase("placer"))) for (Board b : Board.values()) out.add(b.key);
                    if (adm && a[0].equalsIgnoreCase("retirer")) for (Holograms.Holo h : plugin.holograms().all()) out.add(h.id);
                }
                case "koth" -> {
                    if (adm && List.of("supprimer", "lancer", "tp").contains(a[0].toLowerCase(Locale.ROOT))) {
                        for (KothZone z : plugin.koth().zones()) out.add(z.name);
                    }
                }
                case "prime" -> {
                    if (a[0].equalsIgnoreCase("retirer")) for (Bounty b : plugin.bounties().all()) out.add(b.targetName);
                    else out.addAll(List.of("10000", "50000", "100k", "1m"));
                }
                case "quetes" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                default -> {
                }
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
