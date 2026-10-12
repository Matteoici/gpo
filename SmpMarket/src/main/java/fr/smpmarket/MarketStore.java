package fr.smpmarket;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Données du marché : stands (stalls.yml) et colis / messages des joueurs (mailbox.yml).
 * Les objets sont enregistrés tels quels (enchantements, noms...) en base64.
 */
public final class MarketStore {

    private final SmpMarket plugin;
    private final File stallsFile, mailFile;
    private final Map<String, Stall> stalls = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> parcels = new HashMap<>();
    private final Map<UUID, List<String>> notes = new HashMap<>();
    /** Ventes faites pendant l'absence du vendeur : [nombre, argent]. */
    private final Map<UUID, double[]> offlineSales = new HashMap<>();
    private boolean dirty;

    MarketStore(SmpMarket plugin) {
        this.plugin = plugin;
        this.stallsFile = new File(plugin.getDataFolder(), "stalls.yml");
        this.mailFile = new File(plugin.getDataFolder(), "mailbox.yml");
    }

    // ================================================================= stands

    public Collection<Stall> stalls() {
        return stalls.values();
    }

    public Stall stall(String id) {
        return id == null ? null : stalls.get(id.toLowerCase());
    }

    public void add(Stall s) {
        stalls.put(s.id.toLowerCase(), s);
        markDirty();
    }

    public void remove(Stall s) {
        stalls.remove(s.id.toLowerCase());
        markDirty();
    }

    public List<Stall> ownedBy(UUID id) {
        List<Stall> out = new ArrayList<>();
        for (Stall s : stalls.values()) if (s.isOwner(id)) out.add(s);
        return out;
    }

    // ================================================================= colis et messages

    public List<ItemStack> parcels(UUID id) {
        return parcels.computeIfAbsent(id, k -> new ArrayList<>());
    }

    public void addParcel(UUID id, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;
        parcels(id).add(item);
        markDirty();
    }

    /** Ajoute "amount" exemplaires d'un objet, découpés en piles. */
    public void addParcel(UUID id, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxStackSize());
        while (amount > 0) {
            ItemStack it = template.clone();
            int n = Math.min(max, amount);
            it.setAmount(n);
            addParcel(id, it);
            amount -= n;
        }
    }

    public void note(UUID id, String miniMessage) {
        notes.computeIfAbsent(id, k -> new ArrayList<>()).add(miniMessage);
        markDirty();
    }

    public List<String> takeNotes(UUID id) {
        List<String> l = notes.remove(id);
        if (l != null) markDirty();
        return l == null ? List.of() : l;
    }

    public void offlineSale(UUID seller, int count, double money) {
        double[] v = offlineSales.computeIfAbsent(seller, k -> new double[2]);
        v[0] += count;
        v[1] += money;
        markDirty();
    }

    public double[] takeOfflineSales(UUID id) {
        double[] v = offlineSales.remove(id);
        if (v != null) markDirty();
        return v;
    }

    // ================================================================= sauvegarde

    public void markDirty() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    public void save() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Stall s : stalls.values()) {
            String b = "stalls." + s.id + ".";
            Cuboid r = s.region;
            y.set(b + "region", List.of(r.world(), r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ()));
            y.set(b + "entrance", List.of(s.world, s.ex, s.ey, s.ez, (double) s.eyaw));
            y.set(b + "start-price", s.startPrice);
            y.set(b + "max-showcases", s.maxShowcases);
            if (s.owner != null) {
                y.set(b + "owner", s.owner.toString());
                y.set(b + "owner-name", s.ownerName);
                y.set(b + "lease-end", s.leaseEnd);
            }
            y.set(b + "auto-renew", s.autoRenew);
            if (s.name != null) y.set(b + "name", s.name);
            if (s.theme != null) y.set(b + "style.theme", s.theme);
            if (s.background != null) y.set(b + "style.background", s.background);
            if (s.symbol != null) y.set(b + "style.symbol", s.symbol);
            if (s.slogan != null) y.set(b + "style.slogan", s.slogan);
            for (Map.Entry<UUID, String> t : s.trusted.entrySet()) y.set(b + "trusted." + t.getKey(), t.getValue());
            y.set(b + "total-sold", s.totalSold);
            y.set(b + "total-revenue", s.totalRevenue);
            for (Showcase c : s.showcases) {
                String cb = b + "showcases." + c.id + ".";
                y.set(cb + "item", Items.encode(c.template));
                y.set(cb + "price", c.price);
                y.set(cb + "stock", c.stock);
                y.set(cb + "pos", List.of(c.x, c.y, c.z));
                y.set(cb + "sold", c.sold);
                y.set(cb + "revenue", c.revenue);
            }
            Auction a = s.auction;
            if (a != null) {
                y.set(b + "auction.renewal", a.renewal);
                y.set(b + "auction.ends-at", a.endsAt);
                y.set(b + "auction.bids", a.bids);
                if (a.bidder != null) {
                    y.set(b + "auction.bidder", a.bidder.toString());
                    y.set(b + "auction.bidder-name", a.bidderName);
                    y.set(b + "auction.bid", a.bid);
                }
            }
        }
        try {
            y.save(stallsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder stalls.yml : " + e.getMessage());
        }

        YamlConfiguration m = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> e : parcels.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            m.set("parcels." + e.getKey(), e.getValue().stream().map(Items::encode).toList());
        }
        for (Map.Entry<UUID, List<String>> e : notes.entrySet()) m.set("notes." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, double[]> e : offlineSales.entrySet()) {
            m.set("offline-sales." + e.getKey(), List.of(e.getValue()[0], e.getValue()[1]));
        }
        try {
            m.save(mailFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder mailbox.yml : " + e.getMessage());
        }
    }

    public void load() {
        stalls.clear();
        parcels.clear();
        notes.clear();
        offlineSales.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stallsFile);
        ConfigurationSection root = y.getConfigurationSection("stalls");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                try {
                    stalls.put(id.toLowerCase(), readStall(id, root.getConfigurationSection(id)));
                } catch (Exception e) {
                    plugin.getLogger().warning("Stand illisible '" + id + "' : " + e.getMessage());
                }
            }
        }
        YamlConfiguration m = YamlConfiguration.loadConfiguration(mailFile);
        ConfigurationSection p = m.getConfigurationSection("parcels");
        if (p != null) for (String k : p.getKeys(false)) {
            List<ItemStack> list = new ArrayList<>();
            for (String s : p.getStringList(k)) {
                ItemStack it = Items.decode(s);
                if (it != null) list.add(it);
            }
            parcels.put(UUID.fromString(k), list);
        }
        ConfigurationSection n = m.getConfigurationSection("notes");
        if (n != null) for (String k : n.getKeys(false)) notes.put(UUID.fromString(k), new ArrayList<>(n.getStringList(k)));
        ConfigurationSection o = m.getConfigurationSection("offline-sales");
        if (o != null) for (String k : o.getKeys(false)) {
            List<Double> v = o.getDoubleList(k);
            if (v.size() >= 2) offlineSales.put(UUID.fromString(k), new double[]{v.get(0), v.get(1)});
        }
    }

    private Stall readStall(String id, ConfigurationSection c) {
        Stall s = new Stall(id);
        List<?> r = c.getList("region");
        s.region = new Cuboid(String.valueOf(r.get(0)), num(r.get(1)), num(r.get(2)), num(r.get(3)),
                num(r.get(4)), num(r.get(5)), num(r.get(6)));
        List<?> e = c.getList("entrance");
        s.world = String.valueOf(e.get(0));
        s.ex = dbl(e.get(1));
        s.ey = dbl(e.get(2));
        s.ez = dbl(e.get(3));
        s.eyaw = (float) dbl(e.get(4));
        s.startPrice = c.getDouble("start-price", 1000);
        s.maxShowcases = c.getInt("max-showcases", plugin.settings().defaultShowcases);
        String owner = c.getString("owner");
        if (owner != null) {
            s.owner = UUID.fromString(owner);
            s.ownerName = c.getString("owner-name", "?");
            s.leaseEnd = c.getLong("lease-end");
        }
        s.autoRenew = c.getBoolean("auto-renew", true);
        s.name = c.getString("name");
        s.theme = c.getString("style.theme");
        s.background = c.getString("style.background");
        s.symbol = c.getString("style.symbol");
        s.slogan = c.getString("style.slogan");
        ConfigurationSection t = c.getConfigurationSection("trusted");
        if (t != null) for (String k : t.getKeys(false)) s.trusted.put(UUID.fromString(k), t.getString(k, "?"));
        s.totalSold = c.getLong("total-sold");
        s.totalRevenue = c.getDouble("total-revenue");
        ConfigurationSection sc = c.getConfigurationSection("showcases");
        if (sc != null) for (String k : sc.getKeys(false)) {
            ConfigurationSection x = sc.getConfigurationSection(k);
            ItemStack item = Items.decode(x.getString("item"));
            if (item == null) {
                plugin.getLogger().warning("Vitrine " + k + " du stand " + id + " : objet illisible, ignorée.");
                continue;
            }
            List<Integer> pos = x.getIntegerList("pos");
            Showcase show = new Showcase(UUID.fromString(k), item, x.getDouble("price"), x.getInt("stock"),
                    pos.get(0), pos.get(1), pos.get(2));
            show.sold = x.getLong("sold");
            show.revenue = x.getDouble("revenue");
            s.showcases.add(show);
        }
        if (c.isConfigurationSection("auction")) {
            Auction a = new Auction();
            a.renewal = c.getBoolean("auction.renewal");
            a.endsAt = c.getLong("auction.ends-at");
            a.bids = c.getInt("auction.bids");
            String b = c.getString("auction.bidder");
            if (b != null) {
                a.bidder = UUID.fromString(b);
                a.bidderName = c.getString("auction.bidder-name", "?");
                a.bid = c.getDouble("auction.bid");
            }
            s.auction = a;
        }
        return s;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o));
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
    }
}
