package fr.smpshop;

import fr.smpshop.gui.CategoryMenu;
import fr.smpshop.gui.MainShopMenu;
import fr.smpshop.gui.SellMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpshop.Texts.ph;

/** /shop, /sell, /worth, /tokens, /shopadmin */
final class Commands implements CommandExecutor, TabCompleter {

    private final SmpShop plugin;

    Commands(SmpShop plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("tokens")) {
            tokens(sender, a);
            return true;
        }
        if (name.equals("shopadmin")) {
            admin(sender, a);
            return true;
        }
        if (!(sender instanceof Player p)) {
            t().send(sender, "players-only");
            return true;
        }
        switch (name) {
            case "shop" -> {
                int fight = plugin.combatLeft(p);
                if (fight > 0) {
                    t().send(p, "shop.in-combat", ph("seconds", fight));
                    return true;
                }
                if (a.length > 0) {
                    Shop.Category c = plugin.shop().category(a[0]);
                    if (c != null) {
                        new CategoryMenu(plugin, p, c).open();
                        return true;
                    }
                }
                new MainShopMenu(plugin, p).open();
            }
            case "sell" -> {
                String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "main", "hand" -> plugin.seller().report(p, plugin.seller().sellHand(p));
                    case "tout", "all", "inventaire", "inventory" ->
                            plugin.seller().report(p, plugin.seller().sellSlots(p, p.getInventory(), 0, 36));
                    default -> new SellMenu(plugin, p).open();
                }
            }
            case "worth" -> worth(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void worth(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) {
            t().send(p, "worth.empty");
            return;
        }
        double unit = plugin.worth().unitPrice(it);
        Component item = Component.translatable(it.translationKey());
        if (unit <= 0) {
            t().send(p, plugin.worth().basePrice(it.getType()) > 0 ? "worth.special" : "worth.none", Texts.pc("item", item));
            return;
        }
        double mult = plugin.worth().multiplier(p);
        t().send(p, "worth.price", Texts.pc("item", item), ph("price", plugin.settings().money(unit * mult)),
                ph("amount", it.getAmount()), ph("total", plugin.settings().money(unit * mult * it.getAmount())),
                ph("stack", plugin.settings().money(unit * mult * it.getMaxStackSize())));
    }

    // ================================================================= jetons

    private void tokens(CommandSender sender, String[] a) {
        boolean admin = sender.hasPermission("smpshop.admin");
        if (a.length >= 3 && admin) {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (target == null) {
                t().send(sender, "unknown-player", ph("player", a[1]));
                return;
            }
            long n;
            try {
                n = Long.parseLong(a[2]);
            } catch (NumberFormatException e) {
                t().send(sender, "usage", ph("usage", "/tokens <give|take|set> <joueur> <nombre>"));
                return;
            }
            Tokens tk = plugin.tokens();
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give", "donner" -> tk.add(target.getUniqueId(), Math.max(0, n));
                case "take", "retirer" -> tk.set(target.getUniqueId(), Math.max(0, tk.get(target.getUniqueId()) - Math.max(0, n)));
                case "set" -> tk.set(target.getUniqueId(), Math.max(0, n));
                default -> {
                    t().send(sender, "usage", ph("usage", "/tokens <give|take|set> <joueur> <nombre>"));
                    return;
                }
            }
            t().send(sender, "tokens.admin", ph("player", target.getName()),
                    ph("tokens", plugin.settings().tokens(tk.get(target.getUniqueId()))));
            Player online = target.getPlayer();
            if (online != null && a[0].toLowerCase(Locale.ROOT).matches("give|donner")) {
                t().send(online, "tokens.received", ph("amount", n), ph("tokens", plugin.settings().tokens(tk.get(target.getUniqueId()))));
            }
            return;
        }
        if (a.length == 1) {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
            if (target == null) {
                t().send(sender, "unknown-player", ph("player", a[0]));
                return;
            }
            t().send(sender, "tokens.other", ph("player", target.getName()),
                    ph("tokens", plugin.settings().tokens(plugin.tokens().get(target.getUniqueId()))));
            return;
        }
        if (sender instanceof Player p) {
            t().send(p, "tokens.balance", ph("tokens", plugin.settings().tokens(plugin.tokens().get(p.getUniqueId()))));
        }
        if (admin) t().send(sender, "usage", ph("usage", "/tokens <give|take|set> <joueur> <nombre>"));
    }

    // ================================================================= admin

    private void admin(CommandSender sender, String[] a) {
        if (!sender.hasPermission("smpshop.admin")) {
            t().send(sender, "no-permission");
            return;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                t().send(sender, "reloaded", ph("prices", plugin.worth().size()), ph("categories", plugin.shop().categories().size()));
            }
            case "inspect", "voir" -> {
                if (!(sender instanceof Player p)) return;
                ItemStack it = p.getInventory().getItemInMainHand();
                if (it.getType().isAir()) {
                    t().send(p, "worth.empty");
                    return;
                }
                p.sendMessage(Component.text("§6Objet : §f" + it.getType() + " x" + it.getAmount()));
                ItemMeta meta = it.getItemMeta();
                if (meta != null && meta.lore() != null) {
                    int i = 0;
                    for (Component line : meta.lore()) {
                        p.sendMessage(Component.text("§7Ligne " + (i++) + " : §f"
                                + PlainTextComponentSerializer.plainText().serialize(line)));
                    }
                } else {
                    p.sendMessage(Component.text("§7Aucune ligne de description."));
                }
                if (meta != null) {
                    for (var k : meta.getPersistentDataContainer().getKeys()) p.sendMessage(Component.text("§7Donnée cachée : §f" + k));
                }
                p.sendMessage(Component.text("§7Prix de vente : §f" + plugin.settings().money(plugin.worth().unitPrice(it))));
            }
            case "clean", "reparer" -> {
                int n = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    n += plugin.cleaner().clean(p.getInventory()) + plugin.cleaner().clean(p.getEnderChest());
                }
                t().send(sender, "cleaned", ph("count", n));
            }
            default -> t().sendLines(sender, "admin-help");
        }
    }

    // ================================================================= complétion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            switch (name) {
                case "shop" -> {
                    for (Shop.Category c : plugin.shop().categories()) out.add(c.id());
                }
                case "sell" -> out.addAll(List.of("main", "tout"));
                case "shopadmin" -> out.addAll(List.of("reload", "inspect", "clean"));
                case "tokens" -> {
                    if (sender.hasPermission("smpshop.admin")) out.addAll(List.of("give", "take", "set"));
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                default -> {
                }
            }
        } else if (a.length == 2 && name.equals("tokens") && sender.hasPermission("smpshop.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
