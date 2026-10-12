package fr.smpdrop;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static fr.smpdrop.Texts.pc;
import static fr.smpdrop.Texts.ph;
import static fr.smpdrop.Texts.pp;

final class DropCommand implements TabExecutor {

    private static final List<String> PLAYER = List.of("chances", "top", "suivre", "notif");
    private static final List<String> ADMIN = List.of("start", "here", "stop", "tp", "next", "loot", "reload");

    private final SmpDrop plugin;

    DropCommand(SmpDrop plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Texts t = plugin.texts();
        DropManager m = plugin.manager();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (ADMIN.contains(sub) && !sender.hasPermission("smpdrop.admin")) {
            t.send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "" -> info(sender);
            case "chances" -> chances(sender);
            case "suivre", "follow" -> {
                if (!(sender instanceof org.bukkit.entity.Player p)) return true;
                DropEvent ev = m.current();
                if (ev == null || !ev.active()) {
                    t.send(p, "follow.none");
                    return true;
                }
                t.send(p, ev.toggleFollow(p) ? "follow.on" : "follow.off");
            }
            case "notif", "notify", "notifications" -> {
                if (!(sender instanceof org.bukkit.entity.Player p)) return true;
                boolean on = m.toggleNotify(p.getUniqueId());
                t.send(p, on ? "notify.on" : "notify.off");
                DropEvent ev = m.current();
                if (on && ev != null && ev.active() && !ev.follows(p)) ev.toggleFollow(p);
            }
            case "top" -> top(sender);
            case "start" -> {
                boolean instant = args.length > 1 && args[1].equalsIgnoreCase("now");
                t.send(sender, m.start(instant) ? "admin.started" : "admin.already");
            }
            case "here" -> {
                if (!(sender instanceof Player p)) {
                    t.send(sender, "player-only");
                    return true;
                }
                int sec = args.length > 1 ? parseInt(args[1], 15) : 15;
                Location at = p.getLocation().getBlock().getLocation();
                t.send(sender, m.startAt(at, Math.max(5, sec)) ? "admin.started" : "admin.already");
            }
            case "stop" -> {
                DropEvent ev = m.current();
                if (ev == null) {
                    t.send(sender, "admin.none");
                } else {
                    ev.cancelByAdmin();
                    t.send(sender, "admin.stopped");
                }
            }
            case "tp" -> {
                DropEvent ev = m.current();
                if (!(sender instanceof Player p)) t.send(sender, "player-only");
                else if (ev == null || ev.center() == null) t.send(sender, "admin.none");
                else p.teleport(ev.center().add(2, 1, 0));
            }
            case "next" -> {
                if (args.length < 2) {
                    t.send(sender, "admin.usage", ph("usage", "/largage next <minutes>"));
                    return true;
                }
                long min = Math.max(0, parseInt(args[1], 0));
                m.setNext(min * 60_000L);
                t.send(sender, "admin.next", ph("time", DropEvent.time(min * 60_000L)));
            }
            case "loot" -> {
                if (!(sender instanceof Player p)) {
                    t.send(sender, "player-only");
                    return true;
                }
                List<ItemStack> items = new ArrayList<>();
                for (LootTable.Rolled r : plugin.settings().loot.roll(new Random())) items.addAll(r.items());
                p.openInventory(new DropEvent.LootHolder(t.get("opened.inventory-title"), items, new Random()).getInventory());
                t.send(sender, "admin.loot");
            }
            case "reload" -> {
                plugin.reload();
                t.send(sender, "admin.reloaded");
            }
            default -> info(sender);
        }
        return true;
    }

    private void info(CommandSender sender) {
        Texts t = plugin.texts();
        Settings s = plugin.settings();
        t.sendLines(sender, "info.help", ph("interval", DropEvent.time(s.intervalMs)), ph("seconds", s.openTicks / 20));
        DropEvent ev = plugin.manager().current();
        if (ev != null && ev.center() != null) {
            Location c = ev.center();
            TagResolver dir = ph("arrow", "");
            String dist = "?";
            if (sender instanceof Player p && p.getWorld() == c.getWorld()) dist = (int) p.getLocation().distance(c) + " m";
            t.send(sender, "info.active", ev.coords(), dir, ph("distance", dist));
        } else {
            t.send(sender, "info.none", ph("time", DropEvent.time(plugin.manager().nextDrop() - System.currentTimeMillis())));
        }
    }

    private void chances(CommandSender sender) {
        Texts t = plugin.texts();
        LootTable loot = plugin.settings().loot;
        String rolls = loot.minRolls == loot.maxRolls ? String.valueOf(loot.minRolls) : loot.minRolls + "-" + loot.maxRolls;
        String guaranteed = loot.guaranteed.isEmpty() ? "-" : loot.guaranteed.get(0).display();
        sender.sendMessage(t.get("chances.header", ph("rolls", rolls), pp("guaranteed", guaranteed)));
        List<Rarity> list = new ArrayList<>(loot.rarities.values());
        for (Rarity r : list) {
            sender.sendMessage(t.get("chances.rarity", pp("rarity", r.display()), ph("percent", pct(loot.rarityChance(r)))));
            // détail des objets des deux raretés les plus hautes
            if (r.rank() < list.size() - 2) continue;
            for (LootEntry e : loot.entries) {
                if (e.rarity != r) continue;
                sender.sendMessage(t.get("chances.item", pc("name", e.displayName()), ph("percent", pct(loot.entryChancePerCrate(e)))));
            }
        }
    }

    private void top(CommandSender sender) {
        Texts t = plugin.texts();
        List<Map.Entry<String, Integer>> top = plugin.manager().top(10);
        if (top.isEmpty()) {
            t.send(sender, "top.empty");
            return;
        }
        sender.sendMessage(t.get("top.header"));
        int i = 1;
        for (Map.Entry<String, Integer> e : top) {
            sender.sendMessage(t.get("top.line", ph("rank", i++), ph("player", e.getKey()), ph("count", e.getValue())));
        }
    }

    private static String pct(double p) {
        double v = p * 100;
        if (v >= 10) return String.valueOf(Math.round(v));
        return String.format(Locale.FRANCE, "%.1f", v);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            List<String> all = new ArrayList<>(PLAYER);
            if (sender.hasPermission("smpdrop.admin")) all.addAll(ADMIN);
            for (String s : all) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return List.of("now");
        return List.of();
    }
}
