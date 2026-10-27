package fr.smpmarket;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.smpmarket.Texts.ph;

/**
 * Emplacements à claim.
 * - Un emplacement libre : n'importe qui le prend (clic droit sur le panneau, ou /marche installer dedans).
 *   Son marché (nom, style, vitrines, stock, catalogue...) apparaît dessus.
 * - Il s'éloigne (ou se déconnecte) : après un petit délai le marché est RANGÉ (sauvegardé avec lui)
 *   et l'emplacement redevient libre pour quelqu'un d'autre.
 * - Rangé, son stock reste achetable à distance (recherche / catalogue).
 */
public final class Claims {

    private final SmpMarket plugin;
    /** propriétaire -> depuis quand il est loin / déconnecté */
    private final Map<UUID, Long> away = new HashMap<>();

    Claims(SmpMarket plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    // ================================================================= installer

    public boolean deploy(Player p, Stall spot) {
        if (spot == null || spot.isPacked()) return false;
        if (spot.isRented()) {
            if (spot.isOwner(p.getUniqueId())) t().send(p, "claim.already-yours");
            else t().send(p, "claim.taken", ph("player", spot.ownerName == null ? "?" : spot.ownerName));
            return false;
        }
        for (Stall s : plugin.store().stalls()) {
            if (s.isOwner(p.getUniqueId())) {
                t().send(p, "claim.already-deployed", plugin.auctions().stallTags(s));
                return false;
            }
        }
        if (spot.auction != null) plugin.auctions().cancel(spot);
        Stall m = plugin.store().takeMarket(p.getUniqueId());
        spot.owner = p.getUniqueId();
        spot.ownerName = p.getName();
        spot.leaseEnd = Long.MAX_VALUE;
        spot.paid = 0;
        if (m != null) {
            copyStyle(m, spot);
            int ox = spot.region.minX(), oy = spot.region.minY(), oz = spot.region.minZ();
            for (Showcase c : m.showcases) {
                if (c.virtual) {
                    spot.showcases.add(c);
                    continue;
                }
                Showcase n = new Showcase(c.id, c.template, c.price, c.stock, c.x + ox, c.y + oy, c.z + oz);
                n.sold = c.sold;
                n.revenue = c.revenue;
                // emplacement plus petit / autrement construit : l'article passe au catalogue (rien n'est perdu)
                if (!spot.region.contains(n.x, n.y, n.z) || !spot.region.contains(n.x, n.y + 1, n.z)) {
                    Showcase v = Showcase.virtual(c.id, c.template, c.price, c.stock);
                    v.sold = c.sold;
                    v.revenue = c.revenue;
                    spot.showcases.add(v);
                } else {
                    spot.showcases.add(n);
                }
            }
        } else {
            resetStyle(spot);
            spot.totalSold = 0;
            spot.totalRevenue = 0;
        }
        spot.touch();
        away.remove(p.getUniqueId());
        plugin.store().save();
        plugin.visuals().rebuild(spot);
        plugin.mannequins().refresh(spot);
        t().send(p, m == null ? "claim.deployed-new" : "claim.deployed", plugin.auctions().stallTags(spot),
                ph("count", spot.itemsForSale()), ph("distance", (int) plugin.settings().leaveDistance));
        Fx.sound(p, "block.barrel.open", 1f);
        Fx.sound(p, "entity.player.levelup", 1.4f);
        return true;
    }

    // ================================================================= ranger

    /** Range le marché de cet emplacement (sauvegardé avec son propriétaire) et libère l'emplacement. */
    public void pack(Stall spot, String reasonKey) {
        if (spot == null || spot.isPacked() || !spot.isRented()) return;
        UUID owner = spot.owner;
        // anciens stands : coffres posés dedans -> dans le colis du propriétaire
        int items = plugin.auctions().collectContainers(spot);
        if (items > 0) plugin.notify(owner, t().get("parcel.received", ph("count", items)));
        Stall m = new Stall("m-" + owner);
        m.owner = owner;
        m.ownerName = spot.ownerName;
        copyStyle(spot, m);
        int ox = spot.region.minX(), oy = spot.region.minY(), oz = spot.region.minZ();
        for (Showcase c : spot.showcases) {
            if (c.virtual) {
                m.showcases.add(c);
                continue;
            }
            Showcase r = new Showcase(c.id, c.template, c.price, c.stock, c.x - ox, c.y - oy, c.z - oz);
            r.sold = c.sold;
            r.revenue = c.revenue;
            m.showcases.add(r);
        }
        m.lastChange = System.currentTimeMillis();
        plugin.store().putMarket(m);

        plugin.visuals().removeShowcases(spot);
        spot.showcases.clear();
        spot.owner = null;
        spot.ownerName = null;
        spot.leaseEnd = 0;
        resetStyle(spot);
        spot.totalSold = 0;
        spot.totalRevenue = 0;
        spot.containers.clear();
        spot.tracksContainers = true;
        away.remove(owner);
        plugin.store().save();
        plugin.visuals().rebuild(spot);
        plugin.mannequins().refresh(spot);
        if (reasonKey != null) plugin.notify(owner, t().get(reasonKey, plugin.auctions().stallTags(spot)));
    }

    private static void copyStyle(Stall from, Stall to) {
        to.name = from.name;
        to.theme = from.theme;
        to.background = from.background;
        to.symbol = from.symbol;
        to.slogan = from.slogan;
        to.extraShowcases = from.extraShowcases;
        to.catalogSlots = from.catalogSlots;
        to.privateStall = from.privateStall;
        to.mannequin = from.mannequin;
        to.skinValue = from.skinValue;
        to.skinSignature = from.skinSignature;
        to.trusted.clear();
        to.trusted.putAll(from.trusted);
        to.totalSold = from.totalSold;
        to.totalRevenue = from.totalRevenue;
        to.npcSpot = null;
    }

    private static void resetStyle(Stall st) {
        st.name = null;
        st.theme = null;
        st.background = null;
        st.symbol = null;
        st.slogan = null;
        st.extraShowcases = 0;
        st.catalogSlots = 0;
        st.privateStall = false;
        st.mannequin = false;
        st.npcSpot = null;
        st.skinValue = null;
        st.skinSignature = null;
        st.trusted.clear();
    }

    // ================================================================= chaque seconde

    void tick() {
        long now = System.currentTimeMillis();
        Settings s = plugin.settings();
        for (Stall st : new java.util.ArrayList<>(plugin.store().stalls())) {
            if (!st.isRented()) continue;
            Player p = Bukkit.getPlayer(st.owner);
            boolean offline = p == null;
            boolean far = !offline && !near(p, st, s.leaveDistance);
            if (!offline && !far) {
                if (away.remove(st.owner) != null) t().send(p, "claim.back");
                continue;
            }
            long since = away.computeIfAbsent(st.owner, k -> now);
            long grace = offline ? s.offlineGraceMs : s.leaveGraceMs;
            long left = grace - (now - since);
            if (left <= 0) {
                pack(st, offline ? "claim.packed-offline" : "claim.packed-far");
                if (p != null) Fx.sound(p, "block.barrel.close", 0.8f);
            } else if (p != null) {
                p.sendActionBar(t().get("claim.leaving", ph("seconds", left / 1000 + 1)));
            }
        }
    }

    private static boolean near(Player p, Stall st, double d) {
        Location l = p.getLocation();
        if (l.getWorld() == null || !l.getWorld().getName().equals(st.region.world())) return false;
        double cx = (st.region.minX() + st.region.maxX() + 1) / 2.0, cz = (st.region.minZ() + st.region.maxZ() + 1) / 2.0;
        double r = Math.max(st.region.sizeX(), st.region.sizeZ()) / 2.0 + d;
        double dx = l.getX() - cx, dz = l.getZ() - cz;
        return dx * dx + dz * dz <= r * r && Math.abs(l.getY() - st.region.minY()) < 40 + d;
    }

    /** Au démarrage : les vieilles enchères sont annulées et remboursées (on ne loue plus, on claim). */
    void migrate() {
        for (Stall st : plugin.store().stalls()) {
            if (st.auction != null) plugin.auctions().cancel(st);
            if (st.isRented()) st.leaseEnd = Long.MAX_VALUE;
        }
        plugin.store().markDirty();
    }

    /** Emplacements libres. */
    public int freeCount() {
        int n = 0;
        for (Stall st : plugin.store().stalls()) if (!st.isRented()) n++;
        return n;
    }

    public static String ownerName(OfflinePlayer o) {
        return o.getName() == null ? "?" : o.getName();
    }
}
