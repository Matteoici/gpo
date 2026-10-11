package fr.smpmarket;

import fr.smpmarket.gui.AuctionsMenu;
import fr.smpmarket.gui.MailboxMenu;
import fr.smpmarket.gui.MainMenu;
import fr.smpmarket.gui.SearchMenu;
import fr.smpmarket.gui.StallMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static fr.smpmarket.Texts.ph;

public final class MarketCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("chercher", "vitrine", "gerer", "colis", "encheres", "encherir", "tp",
            "ami", "retirer-ami", "nom", "aide");
    private static final List<String> ADMIN = List.of("wand", "create", "delete", "setentrance", "list", "reset", "give",
            "end", "showcases", "price", "reload");

    private final SmpMarket plugin;

    MarketCommand(SmpMarket plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    /** "1500", "1 500", "2,5k", "1m". -1 si invalide. */
    public static double parseMoney(String s) {
        if (s == null) return -1;
        String v = s.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace(",", ".").replace("$", "");
        double mult = 1;
        if (v.endsWith("k")) {
            mult = 1_000;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("m")) {
            mult = 1_000_000;
            v = v.substring(0, v.length() - 1);
        }
        try {
            double d = Double.parseDouble(v) * mult;
            return d > 0 && Double.isFinite(d) ? Math.round(d * 100) / 100.0 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
    }

    private Stall myStall(Player p) {
        List<Stall> l = plugin.store().ownedBy(p.getUniqueId());
        if (l.isEmpty()) {
            t().send(p, "stall.none");
            return null;
        }
        return l.get(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            if (!sender.hasPermission("smpmarket.admin")) t().send(sender, "error.no-permission");
            else admin(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (!(sender instanceof Player p)) {
            t().send(sender, "error.player-only");
            return true;
        }
        switch (sub) {
            case "" -> new MainMenu(plugin, p).open();
            case "aide", "help" -> t().sendLines(p, "help");
            case "chercher", "search", "find" -> {
                String q = join(args, 1);
                if (q.isEmpty()) t().send(p, "error.usage", ph("usage", "/marche chercher <objet>"));
                else new SearchMenu(plugin, p, q, 0).open();
            }
            case "vitrine", "sell", "vendre" -> {
                double price = args.length > 1 ? parseMoney(args[1]) : -1;
                if (price <= 0) {
                    t().send(p, "error.usage", ph("usage", "/marche vitrine <prix à l'unité>"));
                    return true;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) {
                    t().send(p, "showcase.look-at-block");
                    return true;
                }
                plugin.shops().create(p, b, price);
            }
            case "gerer", "manage", "boutique" -> {
                Stall st = myStall(p);
                if (st != null) new StallMenu(plugin, p, st).open();
            }
            case "colis", "parcels" -> new MailboxMenu(plugin, p).open();
            case "encheres", "auctions" -> new AuctionsMenu(plugin, p, 0).open();
            case "encherir", "bid" -> {
                if (args.length < 3) {
                    t().send(p, "error.usage", ph("usage", "/marche encherir <stand> <montant>"));
                    return true;
                }
                Stall st = plugin.store().stall(args[1]);
                double amount = parseMoney(args[2]);
                if (st == null) t().send(p, "stall.unknown", ph("stall", args[1]));
                else if (amount <= 0) t().send(p, "error.bad-amount", ph("value", args[2]));
                else plugin.auctions().bid(p, st, amount);
            }
            case "tp", "visiter", "visit" -> {
                if (args.length < 2) {
                    t().send(p, "error.usage", ph("usage", "/marche tp <stand ou joueur>"));
                    return true;
                }
                Stall st = plugin.store().stall(args[1]);
                if (st == null) {
                    for (Stall s : plugin.store().stalls()) {
                        if (s.ownerName != null && s.ownerName.equalsIgnoreCase(args[1])) st = s;
                    }
                }
                if (st == null) t().send(p, "stall.unknown", ph("stall", args[1]));
                else teleport(p, st);
            }
            case "ami", "trust" -> {
                Stall st = myStall(p);
                if (st == null) return true;
                if (args.length < 2) {
                    t().send(p, "error.usage", ph("usage", "/marche ami <joueur>"));
                    return true;
                }
                OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (o == null) {
                    t().send(p, "error.unknown-player", ph("player", args[1]));
                    return true;
                }
                st.trusted.put(o.getUniqueId(), o.getName());
                plugin.store().markDirty();
                t().send(p, "stall.trusted", ph("player", o.getName()));
            }
            case "retirer-ami", "untrust" -> {
                Stall st = myStall(p);
                if (st == null || args.length < 2) return true;
                st.trusted.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(args[1]));
                plugin.store().markDirty();
                t().send(p, "stall.untrusted", ph("player", args[1]));
            }
            case "nom", "rename" -> {
                Stall st = myStall(p);
                if (st == null) return true;
                rename(p, st, join(args, 1));
            }
            default -> t().sendLines(p, "help");
        }
        return true;
    }

    public static void teleport(SmpMarket plugin, Player p, Stall st) {
        Location l = st.entrance();
        if (l == null) return;
        p.teleportAsync(l);
        plugin.texts().send(p, "stall.teleported", plugin.auctions().stallTags(st));
        Fx.sound(p, "entity.enderman.teleport", 1.2f);
    }

    private void teleport(Player p, Stall st) {
        teleport(plugin, p, st);
    }

    public static void rename(SmpMarket plugin, Player p, Stall st, String name) {
        String clean = Texts.MM.stripTags(name == null ? "" : name).trim();
        if (clean.isEmpty() || clean.length() > 28) {
            plugin.texts().send(p, "stall.bad-name");
            return;
        }
        st.name = clean;
        plugin.store().markDirty();
        plugin.visuals().rebuild(st);
        plugin.texts().send(p, "stall.renamed", ph("shop", clean));
    }

    private void rename(Player p, Stall st, String name) {
        rename(plugin, p, st, name);
    }

    // ================================================================= admin

    private void admin(CommandSender sender, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        MarketStore store = plugin.store();
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                t().send(sender, "admin.reloaded");
            }
            case "list" -> {
                t().send(sender, "admin.list-header", ph("count", store.stalls().size()));
                for (Stall st : store.stalls()) {
                    sender.sendMessage(t().get("admin.list-line", plugin.auctions().stallTags(st),
                            ph("owner", st.ownerName == null ? "-" : st.ownerName), ph("price", plugin.settings().money(st.startPrice))));
                }
            }
            case "wand" -> {
                if (!(sender instanceof Player p)) return;
                ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                ItemMeta m = wand.getItemMeta();
                m.displayName(t().get("admin.wand-name"));
                m.lore(t().list("admin.wand-lore"));
                m.getPersistentDataContainer().set(plugin.wandKey(), PersistentDataType.BYTE, (byte) 1);
                wand.setItemMeta(m);
                p.getInventory().addItem(wand);
                t().send(p, "admin.wand-given");
            }
            case "create" -> {
                if (!(sender instanceof Player p)) return;
                if (a.length < 2 || !a[1].matches("[A-Za-z0-9_-]{1,24}")) {
                    t().send(p, "error.usage", ph("usage", "/marche admin create <id> [prix de départ]"));
                    return;
                }
                if (store.stall(a[1]) != null) {
                    t().send(p, "admin.exists", ph("stall", a[1]));
                    return;
                }
                Location[] sel = plugin.selection(p.getUniqueId());
                if (sel[0] == null || sel[1] == null || sel[0].getWorld() != sel[1].getWorld()) {
                    t().send(p, "admin.no-selection");
                    return;
                }
                Cuboid region = Cuboid.of(sel[0], sel[1]);
                // sélection trop plate (deux coins au sol) : on monte automatiquement la zone de 5 blocs
                if (region.maxY() - region.minY() < 3) {
                    region = new Cuboid(region.world(), region.minX(), region.minY(), region.minZ(),
                            region.maxX(), region.minY() + 5, region.maxZ());
                    t().send(p, "admin.raised");
                }
                for (Stall o : store.stalls()) {
                    if (o.region.overlaps(region)) {
                        t().send(p, "admin.overlap", ph("stall", o.id));
                        return;
                    }
                }
                double price = a.length > 2 ? parseMoney(a[2]) : 1000;
                if (price <= 0) price = 1000;
                Stall st = new Stall(a[1].toLowerCase(Locale.ROOT));
                st.region = region;
                Location l = p.getLocation();
                st.world = l.getWorld().getName();
                st.ex = l.getX();
                st.ey = l.getY();
                st.ez = l.getZ();
                st.eyaw = l.getYaw();
                st.startPrice = price;
                st.maxShowcases = plugin.settings().defaultShowcases;
                store.add(st);
                plugin.visuals().ensure(st);
                t().send(p, "admin.created", plugin.auctions().stallTags(st), ph("price", plugin.settings().money(price)));
                sel[0] = null;
                sel[1] = null;
            }
            case "delete", "reset", "setentrance", "give", "end", "showcases", "price" -> {
                if (a.length < 2) {
                    t().send(sender, "error.usage", ph("usage", "/marche admin " + sub + " <stand> ..."));
                    return;
                }
                Stall st = store.stall(a[1]);
                if (st == null) {
                    t().send(sender, "stall.unknown", ph("stall", a[1]));
                    return;
                }
                adminStall(sender, sub, st, a);
            }
            default -> t().sendLines(sender, "admin.help");
        }
    }

    private void adminStall(CommandSender sender, String sub, Stall st, String[] a) {
        MarketStore store = plugin.store();
        Auctions auctions = plugin.auctions();
        switch (sub) {
            case "delete" -> {
                auctions.cancel(st);
                auctions.evict(st);
                plugin.visuals().removeStall(st);
                store.remove(st);
                t().send(sender, "admin.deleted", ph("stall", st.id));
            }
            case "reset" -> {
                auctions.cancel(st);
                auctions.evict(st);
                plugin.visuals().rebuild(st);
                t().send(sender, "admin.reset", ph("stall", st.id));
            }
            case "setentrance" -> {
                if (!(sender instanceof Player p)) return;
                Location l = p.getLocation();
                st.world = l.getWorld().getName();
                st.ex = l.getX();
                st.ey = l.getY();
                st.ez = l.getZ();
                st.eyaw = l.getYaw();
                store.markDirty();
                plugin.visuals().rebuild(st);
                t().send(sender, "admin.entrance-set", ph("stall", st.id));
            }
            case "give" -> {
                if (a.length < 3) {
                    t().send(sender, "error.usage", ph("usage", "/marche admin give <stand> <joueur> [jours]"));
                    return;
                }
                OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(a[2]);
                if (o == null) {
                    t().send(sender, "error.unknown-player", ph("player", a[2]));
                    return;
                }
                double days = a.length > 3 ? parseMoney(a[3]) : -1;
                long lease = days > 0 ? (long) (days * 86_400_000L) : plugin.settings().leaseMs;
                auctions.cancel(st);
                auctions.evict(st);
                auctions.assign(st, o.getUniqueId(), o.getName(), System.currentTimeMillis() + lease);
                plugin.visuals().rebuild(st);
                plugin.notify(o.getUniqueId(), t().get("auction.won", auctions.stallTags(st), ph("amount", "0"),
                        ph("time", Settings.time(lease))));
                t().send(sender, "admin.given", ph("stall", st.id), ph("player", o.getName()));
            }
            case "end" -> {
                if (st.auction == null) {
                    t().send(sender, "admin.no-auction", ph("stall", st.id));
                    return;
                }
                if (st.auction.endsAt == 0 && !st.auction.renewal) {
                    t().send(sender, "admin.no-bid", ph("stall", st.id));
                    return;
                }
                st.auction.endsAt = System.currentTimeMillis();
                auctions.tick();
                t().send(sender, "admin.ended", ph("stall", st.id));
            }
            case "showcases" -> {
                int n = a.length > 2 ? (int) parseMoney(a[2]) : -1;
                if (n <= 0 || n > 54) {
                    t().send(sender, "error.usage", ph("usage", "/marche admin showcases <stand> <nombre>"));
                    return;
                }
                st.maxShowcases = n;
                store.markDirty();
                t().send(sender, "admin.showcases-set", ph("stall", st.id), ph("count", n));
            }
            case "price" -> {
                double v = a.length > 2 ? parseMoney(a[2]) : -1;
                if (v <= 0) {
                    t().send(sender, "error.usage", ph("usage", "/marche admin price <stand> <prix de départ>"));
                    return;
                }
                st.startPrice = v;
                store.markDirty();
                plugin.visuals().refreshSign(st);
                t().send(sender, "admin.price-set", ph("stall", st.id), ph("price", plugin.settings().money(v)));
            }
            default -> {
            }
        }
    }

    // ================================================================= complétion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> opts = new ArrayList<>();
        if (args.length == 1) {
            opts.addAll(SUBS);
            if (sender.hasPermission("smpmarket.admin")) opts.add("admin");
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "encherir", "tp" -> plugin.store().stalls().forEach(s -> opts.add(s.id));
                case "ami", "retirer-ami" -> Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
                case "admin" -> {
                    if (sender.hasPermission("smpmarket.admin")) opts.addAll(ADMIN);
                }
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            if (!List.of("wand", "create", "list", "reload").contains(args[1].toLowerCase(Locale.ROOT))) {
                plugin.store().stalls().forEach(s -> opts.add(s.id));
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }
}
