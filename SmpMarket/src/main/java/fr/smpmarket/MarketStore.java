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
    /** Marchés rangés, par propriétaire (leurs vitrines sont en coordonnées relatives à l'emplacement). */
    private final Map<UUID, Stall> markets = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> parcels = new HashMap<>();
    private final Map<UUID, List<String>> notes = new HashMap<>();
    /** Ventes faites pendant l'absence du vendeur : [nombre, argent]. */
    private final Map<UUID, double[]> offlineSales = new HashMap<>();
    private boolean dirty;
    /** les vieux stands (ancien système) ont été retirés des emplacements libres */
    public boolean oldBuildsCleared, oldBuildsClearedV2;

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

    public Collection<Stall> markets() {
        return markets.values();
    }

    public Stall market(UUID owner) {
        return owner == null ? null : markets.get(owner);
    }

    public void putMarket(Stall m) {
        markets.put(m.owner, m);
        markDirty();
    }

    public Stall takeMarket(UUID owner) {
        Stall m = markets.remove(owner);
        if (m != null) markDirty();
        return m;
    }

    /** Le marché d'un joueur, installé (emplacement) ou rangé. */
    public Stall marketOf(UUID owner) {
        for (Stall s : stalls.values()) if (s.isOwner(owner)) return s;
        return markets.get(owner);
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
        y.set("old-builds-cleared", oldBuildsCleared);
        y.set("old-builds-cleared-v2", oldBuildsClearedV2);
        for (Stall s : stalls.values()) write(y, "stalls." + s.id + ".", s);
        for (Stall s : markets.values()) write(y, "markets." + s.owner + ".", s);
        try {
            y.save(stallsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder stalls.yml : " + e.getMessage());
        }
        saveMail();
    }

    private void write(YamlConfiguration y, String b, Stall s) {
        {
            Cuboid r = s.region;
            if (r != null) {
                y.set(b + "region", List.of(r.world(), r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ()));
                y.set(b + "entrance", List.of(s.world, s.ex, s.ey, s.ez, (double) s.eyaw));
            }
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
            if (s.extraShowcases > 0) y.set(b + "extra-showcases", s.extraShowcases);
            if (s.catalogSlots > 0) y.set(b + "catalog-slots", s.catalogSlots);
            if (s.privateStall) y.set(b + "private", true);
            if (!s.mannequin) y.set(b + "mannequin", false);
            if (s.skinValue != null) {
                y.set(b + "skin.value", s.skinValue);
                y.set(b + "skin.signature", s.skinSignature);
            }
            if (s.npcSpot != null) y.set(b + "npc-spot", List.of(s.npcSpot[0], s.npcSpot[1], s.npcSpot[2], s.npcSpot[3]));
            for (Map.Entry<UUID, String> t : s.trusted.entrySet()) y.set(b + "trusted." + t.getKey(), t.getValue());
            y.set(b + "containers", new ArrayList<>(s.containers));
            y.set(b + "tracks-containers", s.tracksContainers);
            y.set(b + "total-sold", s.totalSold);
            y.set(b + "total-revenue", s.totalRevenue);
            y.set(b + "last-change", s.lastChange);
            if (s.floorY != Integer.MIN_VALUE) y.set(b + "floor-y", s.floorY);
            if (!s.build.isEmpty()) y.set(b + "build", new ArrayList<>(s.build));
            if (s.layout != 0) y.set(b + "layout", s.layout);
            y.set(b + "paid", s.paid);
            for (Showcase c : s.showcases) {
                String cb = b + "showcases." + c.id + ".";
                y.set(cb + "item", Items.encode(c.template));
                y.set(cb + "price", c.price);
                y.set(cb + "stock", c.stock);
                if (c.virtual) y.set(cb + "virtual", true);
                else y.set(cb + "pos", List.of(c.x, c.y, c.z));
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
    }

    private void saveMail() {
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
        markets.clear();
        parcels.clear();
        notes.clear();
        offlineSales.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stallsFile);
        oldBuildsCleared = y.getBoolean("old-builds-cleared", false);
        oldBuildsClearedV2 = y.getBoolean("old-builds-cleared-v2", false);
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
        ConfigurationSection mk = y.getConfigurationSection("markets");
        if (mk != null) {
            for (String id : mk.getKeys(false)) {
                try {
                    Stall st = readStall("m-" + id, mk.getConfigurationSection(id));
                    if (st.owner != null) markets.put(st.owner, st);
                } catch (Exception e) {
                    plugin.getLogger().warning("Marché rangé illisible '" + id + "' : " + e.getMessage());
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
        if (r != null && r.size() >= 7) {
            s.region = new Cuboid(String.valueOf(r.get(0)), num(r.get(1)), num(r.get(2)), num(r.get(3)),
                    num(r.get(4)), num(r.get(5)), num(r.get(6)));
            List<?> e = c.getList("entrance");
            s.world = String.valueOf(e.get(0));
            s.ex = dbl(e.get(1));
            s.ey = dbl(e.get(2));
            s.ez = dbl(e.get(3));
            s.eyaw = (float) dbl(e.get(4));
        }
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
        s.extraShowcases = c.getInt("extra-showcases");
        s.catalogSlots = c.getInt("catalog-slots");
        s.privateStall = c.getBoolean("private");
        s.mannequin = c.getBoolean("mannequin", true);
        s.skinValue = c.getString("skin.value");
        s.skinSignature = c.getString("skin.signature");
        List<Double> spot = c.getDoubleList("npc-spot");
        s.npcSpot = spot.size() == 4 ? new double[]{spot.get(0), spot.get(1), spot.get(2), spot.get(3)} : null;
        ConfigurationSection t = c.getConfigurationSection("trusted");
        if (t != null) for (String k : t.getKeys(false)) s.trusted.put(UUID.fromString(k), t.getString(k, "?"));
        s.containers.addAll(c.getStringList("containers"));
        // stand libre : le suivi commence avec le prochain locataire ; stand déjà loué : fouille complète à la fin
        s.tracksContainers = c.getBoolean("tracks-containers", s.owner == null);
        s.totalSold = c.getLong("total-sold");
        s.totalRevenue = c.getDouble("total-revenue");
        s.lastChange = c.getLong("last-change", System.currentTimeMillis());
        s.floorY = c.getInt("floor-y", Integer.MIN_VALUE);
        s.build.addAll(c.getStringList("build"));
        s.layout = c.getInt("layout", 0);
        s.paid = c.getDouble("paid", 0);
        ConfigurationSection sc = c.getConfigurationSection("showcases");
        if (sc != null) for (String k : sc.getKeys(false)) {
            ConfigurationSection x = sc.getConfigurationSection(k);
            ItemStack item = Items.decode(x.getString("item"));
            if (item == null) {
                plugin.getLogger().warning("Vitrine " + k + " du stand " + id + " : objet illisible, ignorée.");
                continue;
            }
            Showcase show;
            if (x.getBoolean("virtual")) {
                show = Showcase.virtual(UUID.fromString(k), item, x.getDouble("price"), x.getInt("stock"));
            } else {
                List<Integer> pos = x.getIntegerList("pos");
                show = new Showcase(UUID.fromString(k), item, x.getDouble("price"), x.getInt("stock"),
                        pos.get(0), pos.get(1), pos.get(2));
            }
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
